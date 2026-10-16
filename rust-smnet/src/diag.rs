//! Debug logging (0.1.5.3): the crash report and the F9 bug report.
//!
//! WHAT THIS DOES, in plain words (for anyone reading the source):
//!  - The game sends us its own debug lines (fleet positions, combat, performance) over the same pipe it already
//!    uses. We keep the last 60 seconds of them in memory.
//!  - While the game runs, a file "StarMP crash report - DO NOT DELETE - ..." in StarMP/crash logs holds the last
//!    30+ seconds. New lines are appended every 250 ms; every 15 s the file is rewritten to drop older lines.
//!  - When the game is closed normally it tells us so ("diag_clean") and the file is deleted. If the game crashes,
//!    freezes or is force-closed, the file stays and gets the end of starsector.log appended (Starsector's own error
//!    message), with the Windows/Linux user name removed from file paths.
//!  - F9 in the game ("diag_clip") writes "StarMP bug report - ..." with the last 60 seconds to StarMP/player logs.
//!  - Nothing here sends anything over the network. Files are only written to those two folders in the mod's own
//!    folder (or the desktop if the player picks that in F8), and only these two kinds of file.
//!
//! Runs on its own thread so a slow disk can never stall networking.

use std::{
    collections::VecDeque,
    io::Write,
    path::PathBuf,
    sync::mpsc::{Receiver, RecvTimeoutError},
    time::{Duration, Instant},
};

pub enum DiagMsg {
    /// Settings from the game: player name, where reports go, local time zone.
    Config { player: String, report: String, mod_logs: String, tz_abbr: String, offset_min: i64 },
    /// Header text from the game (version, role, host clock offset, game date, display, Java memory...).
    GameHeader(String),
    /// Header text from our own hardware probe.
    HwHeader(String),
    /// One or more finished log lines ("\n" separated).
    Lines(String),
    /// F9: write the last 60 s to a bug report file.
    Clip(std::sync::mpsc::Sender<String>),
    /// The game is closing normally: delete the crash report when it ends.
    Clean,
    /// smnet is exiting: finish the report now.
    Finish,
}

const KEEP: Duration = Duration::from_secs(60);
const CRASH_KEEP: Duration = Duration::from_secs(30);
const FLUSH_EVERY: Duration = Duration::from_millis(250);
const COMPACT_EVERY: Duration = Duration::from_secs(15);
const FREEZE_AFTER: Duration = Duration::from_secs(10);

struct State {
    buf: VecDeque<(Instant, String)>,
    pending: String,
    player: String,
    report: String, // "desktop" | "mods" | "off"
    mod_logs: String,
    tz_abbr: String,
    offset_min: i64,
    game_hdr: String,
    hw_hdr: String,
    crash_path: Option<PathBuf>,
    clean: bool,
    last_line: Instant,
    froze_noted: bool,
    started_utc_ms: u64,
}

pub fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// (year, month, day, hour, min, sec, ms) for a unix time in ms (civil calendar, no time-zone database needed).
fn civil(ms: i64) -> (i64, u32, u32, u32, u32, u32, u32) {
    let secs = ms.div_euclid(1000);
    let msr = ms.rem_euclid(1000) as u32;
    let days = secs.div_euclid(86_400);
    let sod = secs.rem_euclid(86_400) as u32;
    // Howard Hinnant's days_from_civil inverse
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = if m <= 2 { y + 1 } else { y };
    (y, m, d, sod / 3600, (sod / 60) % 60, sod % 60, msr)
}

pub fn fmt_time(ms: i64, sep: char) -> String {
    let (y, mo, d, h, mi, s, _) = civil(ms);
    format!("{y:04}-{mo:02}-{d:02} {h:02}{sep}{mi:02}{sep}{s:02}")
}

impl State {
    fn local_ms(&self, utc: u64) -> i64 { utc as i64 + self.offset_min * 60_000 }

    fn offset_text(&self) -> String {
        let o = self.offset_min;
        let sign = if o < 0 { '-' } else { '+' };
        format!("{sign}{:02}:{:02}", o.abs() / 60, o.abs() % 60)
    }

    fn safe_player(&self) -> String {
        let p: String = self.player.chars().filter(|c| !"\\/:*?\"<>|".contains(*c) && !c.is_control()).collect();
        let p = p.trim().trim_end_matches('.').to_string();
        if p.is_empty() { "Player".into() } else { p.chars().take(32).collect() }
    }

    /// "Mike - 2026-10-14 15-33-20 EDT (19-33-20Z)"
    fn name_stamp(&self, utc: u64) -> String {
        let local = fmt_time(self.local_ms(utc), '-');
        let (_, _, _, h, mi, s, _) = civil(utc as i64);
        // process id at the end: two game windows from one install (multiboxing) never share a file
        format!("{} - {} {} ({h:02}-{mi:02}-{s:02}Z) #{}", self.safe_player(), local, self.tz_abbr, std::process::id())
    }

    /// Where a report goes. Default ("mods"): two folders inside the mod's own folder - "crash logs" for the crash
    /// report, "player logs" for F9 bug reports. "desktop" is still selectable in F8; "off" = no crash report.
    /// 0.1.5.7: one subfolder per player name inside "crash logs" / "player logs" (e.g. crash logs/Mike/...).
    fn dir(&self, sub: &str) -> Option<PathBuf> {
        match self.report.as_str() {
            "off" => None,
            "desktop" => desktop_dir().or_else(|| self.player_dir(sub)),
            _ => self.player_dir(sub),
        }
    }

    fn player_dir(&self, sub: &str) -> Option<PathBuf> {
        let d = mods_dir(&self.mod_logs, sub)?.join(self.safe_player());
        std::fs::create_dir_all(&d).ok()?;
        Some(d)
    }

    fn header(&self, kind: &str) -> String {
        let now = now_ms();
        let mut h = String::new();
        h.push_str(&format!("==== StarMP {kind} ====\n"));
        h.push_str(&format!("Player:        {}\n", self.player));
        h.push_str(&format!("Local time:    {} {}  (UTC offset {})\n", fmt_time(self.local_ms(now), ':'), self.tz_abbr, self.offset_text()));
        let o = -self.offset_min;
        let hours = if o % 60 == 0 { format!("{}", o / 60) } else { format!("{:.2}", o as f64 / 60.0) };
        h.push_str(&format!("To get UTC:    {} hours to local time\n", if o >= 0 { format!("add {hours}") } else { format!("subtract {}", hours.trim_start_matches('-')) }));
        h.push_str(&format!("UTC time:      {}Z\n", fmt_time(now as i64, ':')));
        h.push_str(&format!("Session start: {}Z\n", fmt_time(self.started_utc_ms as i64, ':')));
        h.push_str("Line format:   Player : HH:MM:SS:mmmZ (UTC) | host-clock-corrected ms | TAG | data\n");
        if !self.game_hdr.is_empty() { h.push_str("---- game ----\n"); h.push_str(&self.game_hdr); if !self.game_hdr.ends_with('\n') { h.push('\n'); } }
        if !self.hw_hdr.is_empty() { h.push_str("---- system ----\n"); h.push_str(&self.hw_hdr); if !self.hw_hdr.ends_with('\n') { h.push('\n'); } }
        h.push_str("==================================\n");
        h
    }

    fn lines_since(&self, keep: Duration) -> String {
        let cut = Instant::now().checked_sub(keep);
        let mut s = String::new();
        for (t, l) in &self.buf {
            if cut.map(|c| *t >= c).unwrap_or(true) { s.push_str(l); s.push('\n'); }
        }
        s
    }

    fn create_crash_file(&mut self) {
        if self.crash_path.is_some() { return; }
        let Some(dir) = self.dir("crash logs") else { return };
        let p = dir.join(format!("StarMP crash report - DO NOT DELETE - {}.txt", self.name_stamp(self.started_utc_ms)));
        if std::fs::write(&p, self.header("crash report (live)")).is_ok() {
            crate::log(&format!("crash report: {}", p.display()));
            self.crash_path = Some(p);
        } else {
            crate::log(&format!("crash report: cannot write to {}", dir.display()));
        }
    }

    fn append(&mut self) {
        if self.pending.is_empty() { return; }
        if let Some(p) = &self.crash_path {
            if let Ok(mut f) = std::fs::OpenOptions::new().append(true).open(p) {
                if f.write_all(self.pending.as_bytes()).is_ok() { self.pending.clear(); }
            } else {
                // the player deleted or moved it while the game runs: make a new one at the next compaction
                self.pending.clear();
            }
        } else {
            self.pending.clear();
        }
    }

    fn compact(&mut self) {
        let Some(p) = self.crash_path.clone() else { return };
        let text = format!("{}{}", self.header("crash report (live)"), self.lines_since(CRASH_KEEP));
        let tmp = p.with_extension("tmp");
        if std::fs::write(&tmp, text).is_ok() && std::fs::rename(&tmp, &p).is_err() {
            let _ = std::fs::remove_file(&tmp);
        }
        self.pending.clear();
    }

    fn clip(&self) -> String {
        let Some(dir) = self.dir("player logs").or_else(|| self.player_dir("player logs")) else { return "error: no folder for bug reports".into() };
        let p = dir.join(format!("StarMP bug report - {}.txt", self.name_stamp(now_ms())));
        let text = format!("{}{}", self.header("bug report (F9, last 60 seconds)"), self.lines_since(KEEP));
        match std::fs::write(&p, text) {
            Ok(_) => p.display().to_string(),
            Err(e) => format!("error: {e}"),
        }
    }

    fn finish(&mut self) {
        self.append();
        let Some(p) = self.crash_path.clone() else { return };
        if self.clean {
            let _ = std::fs::remove_file(&p);
            crate::log("crash report deleted (clean exit)");
            return;
        }
        let mut tail = format!(
            "\n==== THE GAME ENDED WITHOUT A CLEAN EXIT at {}Z (crash, freeze or force close) ====\n",
            fmt_time(now_ms() as i64, ':')
        );
        tail.push_str(&starsector_log_tail(200));
        if let Ok(mut f) = std::fs::OpenOptions::new().append(true).open(&p) {
            let _ = f.write_all(tail.as_bytes());
        }
        crate::log("crash report kept (no clean exit)");
    }
}

/// <mod folder>/<sub>, created if needed ("crash logs" / "player logs").
fn mods_dir(mod_dir: &str, sub: &str) -> Option<PathBuf> {
    if mod_dir.is_empty() { return None; }
    let p = PathBuf::from(mod_dir).join(sub);
    std::fs::create_dir_all(&p).ok()?;
    Some(p)
}

/// The player's real desktop folder (Windows asks the shell, so a desktop moved into OneDrive is found too).
pub fn desktop_dir() -> Option<PathBuf> {
    let d = dirs::desktop_dir().or_else(|| dirs::home_dir().map(|h| h.join("Desktop")))?;
    if d.is_dir() { Some(d) } else { None }
}

/// Removes the user's home folder and user name from text that may contain file paths.
pub fn scrub(text: &str) -> String {
    let mut out = text.to_string();
    let mut needles: Vec<String> = Vec::new();
    if let Some(h) = dirs::home_dir() { needles.push(h.display().to_string()); }
    for v in ["USERPROFILE", "HOME"] { if let Ok(x) = std::env::var(v) { if x.len() > 3 { needles.push(x); } } }
    needles.sort_by_key(|n| std::cmp::Reverse(n.len()));
    for n in &needles {
        out = replace_ci(&out, n, "<home>");
        out = replace_ci(&out, &n.replace('\\', "/"), "<home>");
    }
    for v in ["USERNAME", "USER", "LOGNAME"] {
        if let Ok(u) = std::env::var(v) {
            if u.len() >= 3 {
                for pre in ["\\Users\\", "/Users/", "/home/", "\\users\\"] {
                    out = replace_ci(&out, &format!("{pre}{u}"), &format!("{pre}<user>"));
                }
            }
        }
    }
    out
}

fn replace_ci(hay: &str, needle: &str, with: &str) -> String {
    if needle.is_empty() { return hay.to_string(); }
    let lh = hay.to_lowercase();
    let ln = needle.to_lowercase();
    if lh.len() != hay.len() { return hay.replace(needle, with); } // non-ASCII case mapping changed lengths
    let mut out = String::with_capacity(hay.len());
    let mut i = 0;
    while let Some(pos) = lh[i..].find(&ln) {
        out.push_str(&hay[i..i + pos]);
        out.push_str(with);
        i += pos + ln.len();
    }
    out.push_str(&hay[i..]);
    out
}

/// Last `n` lines of Starsector's own log (it sits in the game's working folder, which we inherit).
fn starsector_log_tail(n: usize) -> String {
    let cands: Vec<PathBuf> = {
        let cwd = std::env::current_dir().unwrap_or_default();
        vec![cwd.join("starsector.log"), cwd.join("starsector-core").join("starsector.log"), cwd.join("..").join("starsector.log")]
    };
    for p in cands {
        if let Ok(bytes) = std::fs::read(&p) {
            let start = bytes.len().saturating_sub(400_000);
            let text = String::from_utf8_lossy(&bytes[start..]);
            let lines: Vec<&str> = text.lines().collect();
            let from = lines.len().saturating_sub(n);
            let mut s = format!("---- last {} lines of starsector.log ----\n", lines.len() - from);
            for l in &lines[from..] { s.push_str(l); s.push('\n'); }
            return scrub(&s);
        }
    }
    "---- starsector.log not found ----\n".into()
}

pub fn run(rx: Receiver<DiagMsg>) {
    let mut st = State {
        buf: VecDeque::new(),
        pending: String::new(),
        player: "Player".into(),
        report: "mods".into(),
        mod_logs: String::new(),
        tz_abbr: "UTC".into(),
        offset_min: 0,
        game_hdr: String::new(),
        hw_hdr: String::new(),
        crash_path: None,
        clean: false,
        last_line: Instant::now(),
        froze_noted: false,
        started_utc_ms: now_ms(),
    };
    let mut configured = false;
    let mut last_flush = Instant::now();
    let mut last_compact = Instant::now();
    loop {
        match rx.recv_timeout(FLUSH_EVERY) {
            Ok(DiagMsg::Config { player, report, mod_logs, tz_abbr, offset_min }) => {
                let name_changed = configured && player != st.player;
                let place_changed = configured && (report != st.report || mod_logs != st.mod_logs);
                st.player = player; st.report = report; st.mod_logs = mod_logs; st.tz_abbr = tz_abbr; st.offset_min = offset_min;
                if name_changed || place_changed {
                    // new name or place: move the live report
                    if let Some(p) = st.crash_path.take() {
                        let _ = std::fs::remove_file(&p);
                        if let Some(parent) = p.parent() { let _ = std::fs::remove_dir(parent); } // only if now empty
                    }
                }
                // started from the SSMP launcher: continue the report it opened before Starsector started
                if !configured && st.crash_path.is_none() && st.report != "off" {
                    if let Ok(pre) = std::env::var("STARMP_PRELOG") {
                        let pp = PathBuf::from(&pre);
                        if !pre.is_empty() && pp.exists() {
                            crate::log(&format!("crash report: continuing the launcher's {}", pp.display()));
                            let l = format!("{} : {} | | INFO | in-game logger took over the launcher's crash report", st.player, fmt_time(now_ms() as i64, ':'));
                            st.pending.push_str(&l); st.pending.push('\n');
                            st.buf.push_back((Instant::now(), l));
                            st.crash_path = Some(pp);
                        }
                    }
                }
                configured = true;
                st.create_crash_file();
                st.compact();
            }
            Ok(DiagMsg::GameHeader(h)) => st.game_hdr = h,
            Ok(DiagMsg::HwHeader(h)) => st.hw_hdr = h,
            Ok(DiagMsg::Lines(text)) => {
                let now = Instant::now();
                if st.froze_noted {
                    st.froze_noted = false;
                    let l = format!("{} : {} | game data resumed after {:.1} s", st.player, fmt_time(now_ms() as i64, ':'), now.duration_since(st.last_line).as_secs_f32());
                    st.pending.push_str(&l); st.pending.push('\n');
                    st.buf.push_back((now, l));
                }
                st.last_line = now;
                for l in text.split('\n') {
                    if l.is_empty() { continue; }
                    st.pending.push_str(l); st.pending.push('\n');
                    st.buf.push_back((now, l.to_string()));
                }
            }
            Ok(DiagMsg::Clip(reply)) => { let _ = reply.send(st.clip()); }
            Ok(DiagMsg::Clean) => st.clean = true,
            Ok(DiagMsg::Finish) => break,
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => break,
        }
        let now = Instant::now();
        while st.buf.front().map(|(t, _)| now.duration_since(*t) > KEEP).unwrap_or(false) { st.buf.pop_front(); }
        if configured && !st.froze_noted && now.duration_since(st.last_line) > FREEZE_AFTER && !st.buf.is_empty() {
            st.froze_noted = true;
            let l = format!("{} : {} | !! no data from the game for {} s (frozen, loading or alt-tabbed?)", st.player, fmt_time(now_ms() as i64, ':'), FREEZE_AFTER.as_secs());
            st.pending.push_str(&l); st.pending.push('\n');
            st.buf.push_back((now, l));
        }
        if now.duration_since(last_flush) >= FLUSH_EVERY { last_flush = now; st.append(); }
        if now.duration_since(last_compact) >= COMPACT_EVERY {
            last_compact = now;
            if configured && st.crash_path.is_none() { st.create_crash_file(); }
            st.compact();
        }
    }
    st.finish();
}
