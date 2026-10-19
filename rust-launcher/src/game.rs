//! Starsector install layout, the mod list, starting the game, and the launcher's early crash logger.

use std::{
    io::Write,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    time::{Duration, Instant},
};

const SSMP_ID: &str = "starmp";
const TAKEOVER: &str = "in-game logger took over";
pub const LAUNCHER_VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Clone)]
pub struct Paths {
    /// Folder with starsector.exe (Windows) or starsector.sh (Linux).
    pub root: PathBuf,
    /// Where the game runs: starsector-core on Windows, the root on Linux (graphics, starsector.log).
    pub core: PathBuf,
    pub mods: PathBuf,
    pub ssmp: PathBuf,
    pub saves: PathBuf,
    /// 0.1.4: packaged installs (NixOS): the game's own start command (the game folder itself is read-only and the
    /// mods / saves live in a separate data folder, e.g. ~/.local/share/starsector). None = starsector.exe / .sh.
    pub cmd: Option<PathBuf>,
}

pub fn fatal(msg: &str) {
    eprintln!("SSMP launcher: {msg}");
    #[cfg(windows)]
    unsafe {
        use windows::core::PCWSTR;
        use windows::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONERROR, MB_OK};
        let t: Vec<u16> = format!("{msg}\0").encode_utf16().collect();
        let c: Vec<u16> = "SSMP Launcher\0".encode_utf16().collect();
        MessageBoxW(None, PCWSTR(t.as_ptr()), PCWSTR(c.as_ptr()), MB_OK | MB_ICONERROR);
    }
}

impl Paths {
    /// Walks up from this program's folder (normally mods/StarMP/bin) to the Starsector folder.
    pub fn find() -> Result<Paths, String> {
        let exe = std::env::current_exe().map_err(|e| e.to_string())?;
        let mut dir = exe.parent().map(Path::to_path_buf);
        for _ in 0..6 {
            let Some(d) = dir.clone() else { break };
            let win = d.join("starsector.exe").exists() && d.join("starsector-core").is_dir();
            let lin = d.join("starsector.sh").exists();
            if win || lin {
                let core = if win { d.join("starsector-core") } else { d.clone() };
                let mods = d.join("mods");
                let ssmp = exe.parent().and_then(Path::parent).filter(|p| p.join("mod_info.json").exists())
                    .map(Path::to_path_buf).unwrap_or_else(|| mods.join("StarMP"));
                return Ok(Paths { saves: d.join("saves"), root: d, core, mods, ssmp, cmd: None });
            }
            dir = d.parent().map(Path::to_path_buf);
        }
        // 0.1.4: packaged installs (NixOS): this program is in <data>/mods/StarMP/bin and the game is started by the
        // "starsector" command on the PATH, whose real file is <package>/bin/starsector next to <package>/share/starsector
        if !cfg!(windows) {
            let ssmp_dir = exe.parent().and_then(Path::parent).map(Path::to_path_buf);
            let data = ssmp_dir.as_deref().and_then(Path::parent).and_then(Path::parent).map(Path::to_path_buf);
            if let (Some(ssmp), Some(data), Some(cmd)) = (ssmp_dir, data, which("starsector")) {
                if data.join("mods").is_dir() {
                    let real = std::fs::canonicalize(&cmd).unwrap_or_else(|_| cmd.clone());
                    let core = real.parent().and_then(Path::parent).map(|p| p.join("share").join("starsector"))
                        .filter(|s| s.join("graphics").is_dir()).unwrap_or_else(|| data.clone());
                    return Ok(Paths { saves: data.join("saves"), mods: data.join("mods"), root: data, core, ssmp, cmd: Some(cmd) });
                }
            }
        }
        Err(format!("Could not find the Starsector folder above {}.\n\nThe SSMP launcher must stay in mods/StarMP/bin inside your Starsector folder - reinstall SSMP with the installer.", exe.display()))
    }

    pub fn ui_graphics(&self) -> PathBuf { self.core.join("graphics").join("ui") }
    pub fn starsector_log(&self) -> PathBuf {
        // packaged installs write it into the data folder (the game folder is read-only)
        if self.cmd.is_some() && self.root.join("starsector.log").exists() { return self.root.join("starsector.log"); }
        self.core.join("starsector.log")
    }

    /// SSMP's version from its mod_info.json (read every time, so the launcher never needs updating for it).
    /** The mod build has its debug logger switched off. */
    pub fn logger_off(&self) -> bool { self.ssmp.join("bin").join("logger.off").exists() }

    pub fn ssmp_version(&self) -> String {
        let txt = std::fs::read_to_string(self.ssmp.join("mod_info.json")).unwrap_or_default();
        // mod_info.json may contain comments, so look for the field instead of parsing strictly
        if let Some(i) = txt.find("\"version\"") {
            let rest = &txt[i + 9..];
            if let Some(q1) = rest.find('"') { if let Some(q2) = rest[q1 + 1..].find('"') { return rest[q1 + 1..q1 + 1 + q2].to_string(); } }
        }
        "?".into()
    }

    fn enabled_file(&self) -> PathBuf { self.mods.join("enabled_mods.json") }

    fn read_enabled(&self) -> (serde_json::Value, Vec<String>) {
        let v: serde_json::Value = std::fs::read_to_string(self.enabled_file()).ok()
            .and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_else(|| serde_json::json!({"enabledMods": []}));
        let list = v.get("enabledMods").and_then(|l| l.as_array())
            .map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
        (v, list)
    }

    pub fn ssmp_enabled(&self) -> bool { self.read_enabled().1.iter().any(|m| m == SSMP_ID) }

    /// Ticks / unticks SSMP in Starsector's mod list. Every other entry is kept exactly as it was.
    pub fn set_ssmp(&self, on: bool) {
        let (mut v, mut list) = self.read_enabled();
        let had = list.iter().any(|m| m == SSMP_ID);
        if on == had { return; }
        if on { list.push(SSMP_ID.into()); } else { list.retain(|m| m != SSMP_ID); }
        if let Some(o) = v.as_object_mut() { o.insert("enabledMods".into(), serde_json::json!(list)); }
        if let Err(e) = std::fs::write(self.enabled_file(), serde_json::to_string(&v).unwrap_or_default()) {
            fatal(&format!("Could not update {} ({e}). You can still tick or untick SSMP in the Starsector launcher's Mods screen.", self.enabled_file().display()));
        }
    }

    /// SSMP's own settings (player name, where reports go, logging on/off).
    pub fn pref(&self, key: &str) -> Option<String> {
        let common = self.saves.join("common");
        for f in ["starmp_settings.txt.data", "starmp_settings.txt"] {
            if let Ok(t) = std::fs::read_to_string(common.join(f)) {
                for l in t.lines() { if let Some((k, v)) = l.split_once('=') { if k.trim() == key { return Some(v.trim().to_string()); } } }
            }
        }
        None
    }

    /// Starts Starsector the normal way (its own launcher window opens).
    pub fn launch(&self, env: Option<&[(&str, String)]>) -> std::io::Result<Child> {
        let mut c = if let Some(cmd) = &self.cmd { Command::new(cmd) }
            else if cfg!(windows) { Command::new(self.root.join("starsector.exe")) } else { let mut c = Command::new("sh"); c.arg(self.root.join("starsector.sh")); c };
        c.current_dir(&self.root).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
        if let Some(env) = env { for (k, v) in env { c.env(k, v); } }
        c.spawn()
    }

    fn helper(&self) -> PathBuf { self.ssmp.join("bin").join(if cfg!(windows) { "smnet.exe" } else { "smnet-linux" }) }
}

// ------------------------------------------------------------------------------------------------ time

/// A program on the PATH (packaged installs).
fn which(name: &str) -> Option<PathBuf> {
    std::env::var_os("PATH").and_then(|p| std::env::split_paths(&p).map(|d| d.join(name)).find(|f| f.is_file()))
}

fn civil(ms: i64) -> (i64, u32, u32, u32, u32, u32, u32) {
    let secs = ms.div_euclid(1000);
    let msr = ms.rem_euclid(1000) as u32;
    let days = secs.div_euclid(86_400);
    let sod = secs.rem_euclid(86_400) as u32;
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d, sod / 3600, (sod / 60) % 60, sod % 60, msr)
}
fn now_ms() -> i64 { chrono::Utc::now().timestamp_millis() }
fn ymd_hms(ms: i64, sep: char) -> String { let (y, mo, d, h, mi, s, _) = civil(ms); format!("{y:04}-{mo:02}-{d:02} {h:02}{sep}{mi:02}{sep}{s:02}") }

// ------------------------------------------------------------------------------------------------ early logger

/// The crash report, from before Starsector starts until the mod's in-game logger takes the file over.
pub struct PreLog { path: PathBuf, player: String }

impl PreLog {
    fn start(p: &Paths) -> Option<PreLog> {
        if p.pref("debugLog").map(|v| v == "false").unwrap_or(false) { return None; }
        let place = p.pref("crashReport").unwrap_or_else(|| "mods".into());
        if place == "off" { return None; }
        let player = p.pref("name").filter(|n| !n.is_empty()).unwrap_or_else(|| "Player".into());
        let safe: String = player.chars().filter(|c| !"\\/:*?\"<>|".contains(*c) && !c.is_control()).take(32).collect();
        let safe = { let t = safe.trim().trim_end_matches('.').to_string(); if t.is_empty() { "Player".to_string() } else { t } };
        // reports: <log root>/crash logs/<player>/ - the log root is this install's StarMP folder, or the main install's
        // for multibox copies (pref "logRoot", written by the multibox script)
        let root = p.pref("logRoot").filter(|r| !r.trim().is_empty()).map(PathBuf::from).unwrap_or_else(|| p.ssmp.clone());
        let dir = if place == "desktop" { dirs::desktop_dir().filter(|d| d.is_dir()).unwrap_or_else(|| root.join("crash logs").join(&safe)) } else { root.join("crash logs").join(&safe) };
        std::fs::create_dir_all(&dir).ok()?;
        let off_min = chrono::Local::now().offset().local_minus_utc() as i64 / 60;
        let tz = format!("UTC{}{:02}{}", if off_min < 0 { '-' } else { '+' }, off_min.abs() / 60, if off_min % 60 != 0 { format!(":{:02}", off_min.abs() % 60) } else { String::new() });
        let now = now_ms();
        let (_, _, _, h, mi, s, _) = civil(now);
        let path = dir.join(format!("StarMP crash report - DO NOT DELETE - {} - {} {} ({h:02}-{mi:02}-{s:02}Z) #{}.txt", safe, ymd_hms(now + off_min * 60_000, '-'), tz, std::process::id()));
        let hours = -off_min as f64 / 60.0;
        let mut hdr = String::new();
        hdr.push_str("==== StarMP crash report (live, started by the SSMP launcher) ====\n");
        hdr.push_str(&format!("Player:        {player}\n"));
        hdr.push_str(&format!("Local time:    {} {tz}\n", ymd_hms(now + off_min * 60_000, ':')));
        hdr.push_str(&format!("To get UTC:    {} {} hours to local time\n", if hours >= 0.0 { "add" } else { "subtract" }, format!("{}", hours.abs()).trim_end_matches(".0")));
        hdr.push_str(&format!("UTC time:      {}Z\n", ymd_hms(now, ':')));
        hdr.push_str("Line format:   Player : HH:MM:SS:mmmZ (UTC) | host-clock-corrected ms | TAG | data\n");
        hdr.push_str(&format!("---- launcher ----\nSSMP launcher: {LAUNCHER_VERSION}\nSSMP mod:      {}\n", p.ssmp_version()));
        hdr.push_str(&format!("---- system ----\n{}\n==================================\n", hw_header(p)));
        std::fs::write(&path, hdr).ok()?;
        let l = PreLog { path, player };
        l.line("LAUNCHER", "crash logger started (before Starsector)");
        Some(l)
    }

    fn append(&self, text: &str) {
        if let Ok(mut f) = std::fs::OpenOptions::new().append(true).open(&self.path) { let _ = f.write_all(text.as_bytes()); }
    }
    fn stamp(&self) -> String { let (_, _, _, h, mi, s, ms) = civil(now_ms()); format!("{} : {h:02}:{mi:02}:{s:02}:{ms:03}Z |  |", self.player) }
    pub fn line(&self, tag: &str, text: &str) { self.append(&format!("{} {tag} | {text}\n", self.stamp())); }
    fn taken_over(&self) -> bool { std::fs::read_to_string(&self.path).map(|t| t.contains(TAKEOVER)).unwrap_or(false) }
    fn exists(&self) -> bool { self.path.exists() }
}

/// Hardware lines from the helper's probe ("smnet --hwprobe"), the same text the in-game report uses.
fn hw_header(p: &Paths) -> String {
    let mut c = Command::new(p.helper());
    c.arg("--hwprobe").arg("").stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null());
    #[cfg(windows)]
    { use std::os::windows::process::CommandExt; c.creation_flags(0x0800_0000); }
    let Ok(mut child) = c.spawn() else { return "hardware: n/a (helper not found)".into() };
    let out = child.stdout.take();
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        use std::io::BufRead;
        if let Some(o) = out {
            for l in std::io::BufReader::new(o).lines().map_while(Result::ok) {
                if let Ok(v) = serde_json::from_str::<serde_json::Value>(&l) {
                    if v.get("t").and_then(|t| t.as_str()) == Some("hw") { let _ = tx.send(v.get("text").and_then(|t| t.as_str()).unwrap_or("").to_string()); return; }
                }
            }
        }
    });
    let r = rx.recv_timeout(Duration::from_secs(8)).unwrap_or_else(|_| "hardware: n/a (probe gave no answer)".into());
    drop(child.stdin.take());
    let _ = child.kill();
    let _ = child.wait();
    r
}

// ------------------------------------------------------------------------------------------------ multiplayer

/// Multiplayer: crash logger first, then SSMP ticked, then Starsector. Watches the game until the mod's logger takes
/// the report over, or until the game ends (then finishes the report itself).
pub fn multiplayer(p: Paths, lan: bool) {
    // a build with the debug logger switched off ships bin/logger.off: no crash report from here either
    let pre = if p.logger_off() { None } else { PreLog::start(&p) };
    p.set_ssmp(true);
    let mut env: Vec<(&str, String)> = vec![("STARMP_LAUNCHER", LAUNCHER_VERSION.to_string()), ("STARMP_NET", (if lan { "lan" } else { "iroh" }).to_string())];
    if let Some(l) = &pre { env.push(("STARMP_PRELOG", l.path.display().to_string())); }
    let child = match p.launch(Some(&env)) {
        Ok(c) => c,
        Err(e) => { if let Some(l) = &pre { l.line("LAUNCHER", &format!("could not start Starsector: {e}")); } fatal(&format!("Could not start Starsector: {e}")); return; }
    };
    let Some(pre) = pre else { return }; // logging turned off: nothing to watch
    pre.line("LAUNCHER", &format!("Starsector started (pid {}) - SSMP ticked in the mod list, {}", child.id(), if lan { "LAN" } else { "IROH" }));
    watch(&p, &pre, child);
}

fn watch(p: &Paths, pre: &PreLog, mut child: Child) {
    let log = p.starsector_log();
    let mut pos: u64 = std::fs::metadata(&log).map(|m| m.len()).unwrap_or(0);
    let mut partial = String::new();
    let mut fatal_seen = false;
    let mut tracker = Tracker::new(child.id());
    let mut last_tree = Instant::now() - Duration::from_secs(5);
    loop {
        std::thread::sleep(Duration::from_millis(250));
        if !pre.exists() { return; } // deleted by the in-game logger (clean exit) or by the player
        if pre.taken_over() { return; } // the mod's logger owns the report now
        // copy Starsector's new log lines (the log may be rewritten at startup: start over if it shrank)
        if let Ok(meta) = std::fs::metadata(&log) {
            if meta.len() < pos { pos = 0; partial.clear(); }
            if meta.len() > pos {
                if let Ok(mut f) = std::fs::File::open(&log) {
                    use std::io::{Read, Seek, SeekFrom};
                    let mut buf = Vec::new();
                    if f.seek(SeekFrom::Start(pos)).is_ok() && f.take(4_000_000).read_to_end(&mut buf).is_ok() {
                        pos += buf.len() as u64;
                        partial.push_str(&String::from_utf8_lossy(&buf));
                        let mut out = String::new();
                        while let Some(i) = partial.find('\n') {
                            let l: String = partial[..i].trim_end().to_string();
                            partial.drain(..=i);
                            if l.contains("Exception") || l.contains("FATAL") || l.contains("OutOfMemoryError") { fatal_seen = true; }
                            out.push_str(&format!("{} LOG | {}\n", pre.stamp(), scrub(&l)));
                        }
                        if !out.is_empty() { pre.append(&out); }
                    }
                }
            }
        }
        if last_tree.elapsed() >= Duration::from_secs(1) { last_tree = Instant::now(); tracker.refresh(); }
        let child_done = matches!(child.try_wait(), Ok(Some(_)));
        if child_done && !tracker.any_alive() {
            let code = tracker.exit_code().or_else(|| child.try_wait().ok().flatten().and_then(|s| s.code()));
            let clean = code == Some(0) && !fatal_seen;
            if clean {
                let _ = std::fs::remove_file(&pre.path);
            } else {
                pre.append(&format!("\n==== THE GAME ENDED WITHOUT A CLEAN EXIT before the mod's logger started (exit code {}) ====\n",
                    code.map(|c| c.to_string()).unwrap_or_else(|| "unknown".into())));
                pre.append(&tail(&log, 200));
            }
            return;
        }
    }
}

/// The game and every process it starts (Starsector's exe starts Java), so the launcher knows when it has all ended
/// and, on Windows, Java's real exit code.
struct Tracker { pids: Vec<u32>, sys: sysinfo::System, #[cfg(windows)] java: Vec<windows::Win32::Foundation::HANDLE> }

impl Tracker {
    fn new(root: u32) -> Tracker { Tracker { pids: vec![root], sys: sysinfo::System::new(), #[cfg(windows)] java: Vec::new() } }
    fn refresh(&mut self) {
        self.sys.refresh_processes(sysinfo::ProcessesToUpdate::All, true);
        let mut grew = true;
        while grew {
            grew = false;
            let known = self.pids.clone();
            for (pid, pr) in self.sys.processes() {
                let id = pid.as_u32();
                if self.pids.contains(&id) { continue; }
                if pr.parent().map(|pp| known.contains(&pp.as_u32())).unwrap_or(false) {
                    self.pids.push(id); grew = true;
                    #[cfg(windows)]
                    if pr.name().to_string_lossy().to_lowercase().starts_with("java") {
                        use windows::Win32::System::Threading::{OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_SYNCHRONIZE};
                        if let Ok(h) = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE, false, id) } { self.java.push(h); }
                    }
                }
            }
        }
    }
    fn any_alive(&mut self) -> bool {
        self.sys.refresh_processes(sysinfo::ProcessesToUpdate::All, true);
        self.pids.iter().skip(1).any(|id| self.sys.process(sysinfo::Pid::from_u32(*id)).map(|p| !matches!(p.status(), sysinfo::ProcessStatus::Zombie | sysinfo::ProcessStatus::Dead)).unwrap_or(false))
    }
    fn exit_code(&self) -> Option<i32> {
        #[cfg(windows)]
        {
            use windows::Win32::System::Threading::GetExitCodeProcess;
            for h in &self.java { let mut c = 0u32; if unsafe { GetExitCodeProcess(*h, &mut c) }.is_ok() && c != 259 { return Some(c as i32); } }
        }
        None
    }
}

fn scrub(s: &str) -> String {
    let mut out = s.to_string();
    for v in ["USERPROFILE", "HOME"] {
        if let Ok(h) = std::env::var(v) { if h.len() > 3 { out = out.replace(&h, "<home>").replace(&h.replace('\\', "/"), "<home>"); } }
    }
    out
}

fn tail(log: &Path, n: usize) -> String {
    let Ok(b) = std::fs::read(log) else { return "---- starsector.log not found ----\n".into() };
    let t = String::from_utf8_lossy(&b[b.len().saturating_sub(400_000)..]).to_string();
    let lines: Vec<&str> = t.lines().collect();
    let from = lines.len().saturating_sub(n);
    let mut s = format!("---- last {} lines of starsector.log ----\n", lines.len() - from);
    for l in &lines[from..] { s.push_str(&scrub(l)); s.push('\n'); }
    s
}
