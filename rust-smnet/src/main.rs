//! smnet - Iroh transport helper for the Starsector multiplayer mod ("StarMP").
//!
//! The Java mod launches this program and talks to it with one JSON object per line:
//!   stdin  (mod -> smnet): commands
//!   stdout (smnet -> mod): events      stderr: human readable log lines
//!
//! Commands:
//!   {"cmd":"host","name":"Mike","lan":false}      start hosting, emits "ready" with a join code
//!   {"cmd":"join","code":"SM1:...","name":"Bob"}  join a host, emits "connected"
//!   {"cmd":"send","peer":1,"data":"<base64>"}      reliable message (client: peer ignored, goes to host)
//!   {"cmd":"broadcast","data":"<base64>"}          host only: message to every client
//!   {"cmd":"disconnect"}                           leave / stop hosting (process stays alive)
//!   {"cmd":"quit"}                                 close everything and exit
//!   {"cmd":"diag_init",...} / "diag_hdr" / "diag" / "diag_clip" / "diag_clean"   debug log + crash report (diag.rs)
//!
//! Events:
//!   {"ev":"ready","code":"SM1:...","id":"<endpoint id>"}
//!   {"ev":"connected","peer":1,"name":"Bob","you":1}   (client side: peer 0 = host, you = your id)
//!   {"ev":"peer_left","peer":1,"reason":"..."}
//!   {"ev":"message","peer":1,"data":"<base64>"}
//!   {"ev":"latency","peer":1,"ms":23}
//!   {"ev":"stopped"}                                   after disconnect
//!   {"ev":"error","text":"..."}
//!
//! No game state is synchronised here (segment 1: link only).
#![allow(clippy::too_many_arguments)]

mod diag;
mod hw;

use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{
        atomic::{AtomicU32, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};

use anyhow::{anyhow, Context, Result};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use iroh::{
    endpoint::{presets, Connection},
    Endpoint, EndpointAddr, EndpointId, RelayUrl,
};
use serde_json::{json, Value};
use tokio::{
    io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader},
    net::{TcpListener, TcpStream, UdpSocket},
    sync::{mpsc, Mutex},
    task::JoinHandle,
};

const ALPN: &[u8] = b"starmp/1";
const CODE_PREFIX: &str = "SM1:";
const MAX_FRAME: usize = 8 * 1024 * 1024;
const PING_EVERY: Duration = Duration::from_secs(2);
const DEAD_AFTER: Duration = Duration::from_secs(12);

type Out = mpsc::UnboundedSender<Value>;

// ---------------------------------------------------------------- save transfer
// The game mod cannot touch files (Starsector's mod sandbox), so the helper copies save folders.

static SAVE_ROOT: std::sync::Mutex<Option<std::path::PathBuf>> = std::sync::Mutex::new(None);
const CHUNK: usize = 256 * 1024;
/// Largest save we accept (vanilla saves are tens of MB; heavily modded ones a few hundred).
const MAX_SAVE: u64 = 2 * 1024 * 1024 * 1024;

fn safe_component(s: &str) -> bool {
    !s.is_empty() && s != "." && s != ".." && !s.contains('/') && !s.contains('\\') && !s.contains(':')
}

/// Save folders under `root`, newest first (by descriptor.xml / folder modification time).
fn list_saves(root: &std::path::Path) -> Vec<(String, u64)> {
    let mut v = Vec::new();
    if let Ok(rd) = std::fs::read_dir(root) {
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            if !name.starts_with("save_") || !e.path().is_dir() { continue; }
            let p = e.path().join("descriptor.xml");
            let m = std::fs::metadata(&p).or_else(|_| e.metadata()).and_then(|m| m.modified()).ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0);
            v.push((name, m));
        }
    }
    v.sort_by(|a, b| b.1.cmp(&a.1));
    v
}

/// Reads a save folder (recursively) and queues it as file frames to one peer.
fn queue_save(dir: &std::path::Path, folder: &str, tx: &mpsc::UnboundedSender<Value>) -> Result<(usize, u64)> {
    let base = dir.join(folder);
    let mut files: Vec<(String, std::path::PathBuf)> = Vec::new();
    let mut stack = vec![(String::new(), base.clone())];
    while let Some((rel, p)) = stack.pop() {
        for e in std::fs::read_dir(&p).with_context(|| format!("read {}", p.display()))?.flatten() {
            let n = e.file_name().to_string_lossy().to_string();
            let r = if rel.is_empty() { n.clone() } else { format!("{rel}/{n}") };
            if e.path().is_dir() { stack.push((r, e.path())); } else { files.push((r, e.path())); }
        }
    }
    let total: u64 = files.iter().map(|(_, p)| std::fs::metadata(p).map(|m| m.len()).unwrap_or(0)).sum();
    let _ = tx.send(json!({"t":"save_begin","save":folder,"files":files.len(),"bytes":total}));
    for (rel, p) in &files {
        let data = std::fs::read(p).with_context(|| format!("read {}", p.display()))?;
        if data.is_empty() {
            let _ = tx.send(json!({"t":"save_file","save":folder,"path":rel,"off":0,"d":""}));
        }
        for (i, c) in data.chunks(CHUNK).enumerate() {
            let _ = tx.send(json!({"t":"save_file","save":folder,"path":rel,"off":i * CHUNK,"d":B64.encode(c)}));
        }
    }
    let _ = tx.send(json!({"t":"save_done","save":folder,"files":files.len(),"bytes":total}));
    Ok((files.len(), total))
}

/// Receiver side of the save frames. Writes into <root>/.incoming_<save> and swaps it in when complete.
fn handle_save_frame(v: &Value, out: &Out, received: &mut u64) {
    let root = match SAVE_ROOT.lock().ok().and_then(|g| g.clone()) {
        Some(r) => r,
        None => { let _ = out.send(json!({"ev":"save_error","text":"no save folder configured"})); return; }
    };
    let save = v.get("save").and_then(Value::as_str).unwrap_or("");
    if !save.starts_with("save_") || !safe_component(save) {
        let _ = out.send(json!({"ev":"save_error","text":format!("refused save name {save:?}")}));
        return;
    }
    // 0.1.10: one temp folder per helper process - several games on one PC sharing a saves folder (multibox from one
    // install) used to write the same .incoming_ folder, and one finishing deleted it under the other
    // ("copy ... campaign.xml.bak: The system cannot find the file specified")
    let tmp = root.join(format!(".incoming_{save}_{}", std::process::id()));
    let res: Result<()> = (|| {
        match v.get("t").and_then(Value::as_str) {
            Some("save_begin") => {
                let _ = std::fs::remove_dir_all(&tmp);
                std::fs::create_dir_all(&tmp)?;
                *received = 0;
                let _ = out.send(json!({"ev":"save_begin","name":save,"bytes":v.get("bytes").cloned().unwrap_or(json!(0))}));
            }
            Some("save_file") => {
                let rel = v.get("path").and_then(Value::as_str).unwrap_or("");
                if rel.is_empty() || !rel.split('/').all(safe_component) {
                    return Err(anyhow!("refused file path {rel:?}"));
                }
                let target = tmp.join(rel);
                if let Some(parent) = target.parent() { std::fs::create_dir_all(parent)?; }
                let data = B64.decode(v.get("d").and_then(Value::as_str).unwrap_or(""))?;
                let off = v.get("off").and_then(Value::as_u64).unwrap_or(0);
                if off.saturating_add(data.len() as u64) > MAX_SAVE || received.saturating_add(data.len() as u64) > MAX_SAVE {
                    return Err(anyhow!("save is larger than the {} MB limit", MAX_SAVE / (1024 * 1024)));
                }
                use std::io::{Seek, SeekFrom, Write};
                let mut f = std::fs::OpenOptions::new().create(true).write(true).open(&target)?;
                f.seek(SeekFrom::Start(off))?;
                f.write_all(&data)?;
                let before = *received / (1024 * 1024);
                *received += data.len() as u64;
                if *received / (1024 * 1024) != before {
                    let _ = out.send(json!({"ev":"save_progress","name":save,"bytes":*received}));
                }
            }
            Some("save_done") => {
                // Copy the files into place instead of renaming folders: folder renames fail or half-apply under
                // Sandboxie and similar file virtualisation, and on Windows when a file in the old folder is open.
                if !tmp.join("campaign.xml").exists() {
                    return Err(anyhow!("the received save has no campaign.xml - transfer incomplete"));
                }
                let dest = root.join(save);
                std::fs::create_dir_all(&dest)?;
                copy_tree(&tmp, &dest)?;
                if !dest.join("campaign.xml").exists() {
                    return Err(anyhow!("could not write campaign.xml into {}", dest.display()));
                }
                let _ = std::fs::remove_dir_all(&tmp);
                let _ = out.send(json!({"ev":"save_received","name":save,"bytes":*received}));
            }
            _ => {}
        }
        Ok(())
    })();
    if let Err(e) = res {
        let _ = out.send(json!({"ev":"save_error","text":format!("{e:#}")}));
    }
}

/// 0.1.10: notices other StarMP games on this PC using the same saves folder (several windows started from one
/// install). Each helper keeps a marker file `.starmp-instance-<pid>` in the saves folder fresh every 5 s; markers
/// touched in the last 15 s by another pid are other running games. Reports {"ev":"shared_saves","others":n} on change.
/// 0.1.11: this helper's marker file, deleted when the game closes (it used to stay until the next launch).
static INSTANCE_MARKER: std::sync::Mutex<Option<std::path::PathBuf>> = std::sync::Mutex::new(None);
static SHUTTING_DOWN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// 0.1.11: called once the game has closed (stdin ended): remove our `.starmp-instance-<pid>` marker.
fn remove_instance_marker() {
    SHUTTING_DOWN.store(true, std::sync::atomic::Ordering::SeqCst);
    if let Some(p) = INSTANCE_MARKER.lock().ok().and_then(|g| g.clone()) { let _ = std::fs::remove_file(p); }
}

fn shared_saves_watch(root: std::path::PathBuf, out: Out) {
    static STARTED: std::sync::Mutex<Option<std::path::PathBuf>> = std::sync::Mutex::new(None);
    if let Ok(mut g) = STARTED.lock() {
        if g.as_ref() == Some(&root) { return; }
        *g = Some(root.clone());
    }
    std::thread::spawn(move || {
        let me = std::process::id();
        let mine = root.join(format!(".starmp-instance-{me}"));
        if let Ok(mut g) = INSTANCE_MARKER.lock() { *g = Some(mine.clone()); }
        let mut last: i64 = -1;
        loop {
            if SHUTTING_DOWN.load(std::sync::atomic::Ordering::SeqCst) { let _ = std::fs::remove_file(&mine); return; }
            if STARTED.lock().ok().and_then(|g| g.clone()).as_ref() != Some(&root) { let _ = std::fs::remove_file(&mine); return; }
            let _ = std::fs::write(&mine, now_ms().to_string());
            let mut others = 0i64;
            if let Ok(rd) = std::fs::read_dir(&root) {
                for e in rd.flatten() {
                    let name = e.file_name().to_string_lossy().to_string();
                    let Some(pid) = name.strip_prefix(".starmp-instance-") else { continue };
                    if pid == me.to_string() { continue; }
                    let fresh = e.metadata().and_then(|m| m.modified()).ok()
                        .and_then(|t| t.elapsed().ok()).map(|d| d.as_secs() < 15).unwrap_or(false);
                    if fresh { others += 1; } else { let _ = std::fs::remove_file(e.path()); }
                }
            }
            if others != last { last = others; let _ = out.send(json!({"ev":"shared_saves","others":others,"dir":root.display().to_string()})); }
            std::thread::sleep(std::time::Duration::from_secs(5));
        }
    });
}

/// Copies every file under `from` into `to` (overwriting), creating folders as needed.
fn copy_tree(from: &std::path::Path, to: &std::path::Path) -> Result<()> {
    for entry in std::fs::read_dir(from)? {
        let entry = entry?;
        let src = entry.path();
        let dst = to.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            std::fs::create_dir_all(&dst)?;
            copy_tree(&src, &dst)?;
        } else {
            // another game on this PC may be writing the same file right now (shared saves folder): retry a few times
            // 0.1.11: write next to it under a temporary name, then swap it in with one rename - a game reading the save
            // never sees a half-written file (on Windows the rename waits while the game has the file open)
            let part = to.join(format!("{}.starmp-part", entry.file_name().to_string_lossy()));
            let mut tries = 0;
            loop {
                let r = std::fs::copy(&src, &part).and_then(|_| std::fs::rename(&part, &dst));
                match r {
                    Ok(_) => break,
                    Err(e) if tries < 15 => { tries += 1; let _ = e; std::thread::sleep(std::time::Duration::from_millis(200)); }
                    Err(e) => { let _ = std::fs::remove_file(&part); return Err(e).with_context(|| format!("copy {} -> {}", src.display(), dst.display())); }
                }
            }
        }
    }
    Ok(())
}

pub(crate) fn log(msg: impl AsRef<str>) {
    eprintln!("[smnet] {}", msg.as_ref());
}

// ---------------------------------------------------------------- join codes

fn make_code(addr: &EndpointAddr, include_ips: bool) -> String {
    let mut s = format!("{}{}", CODE_PREFIX, addr.id);
    if let Some(r) = addr.relay_urls().next() {
        s.push_str(&format!(";r={}", r));
    }
    if include_ips {
        for ip in addr.ip_addrs() {
            s.push_str(&format!(";a={}", ip));
        }
    }
    s
}

fn parse_code(code: &str) -> Result<EndpointAddr> {
    let code = code.trim();
    let rest = code
        .strip_prefix(CODE_PREFIX)
        .ok_or_else(|| anyhow!("not a StarMP join code (must start with {CODE_PREFIX})"))?;
    let mut parts = rest.split(';');
    let id: EndpointId = parts
        .next()
        .unwrap_or("")
        .trim()
        .parse()
        .map_err(|e| anyhow!("bad endpoint id in join code: {e}"))?;
    let mut addr = EndpointAddr::new(id);
    for p in parts {
        let p = p.trim();
        if let Some(r) = p.strip_prefix("r=") {
            let url: RelayUrl = r.parse().map_err(|e| anyhow!("bad relay url in join code: {e}"))?;
            addr = addr.with_relay_url(url);
        } else if let Some(a) = p.strip_prefix("a=") {
            let sa: SocketAddr = a.parse().map_err(|e| anyhow!("bad address in join code: {e}"))?;
            addr = addr.with_ip_addr(sa);
        }
    }
    Ok(addr)
}

// ---------------------------------------------------------------- framing

async fn write_frame<W: AsyncWriteExt + Unpin>(w: &mut W, v: &Value) -> Result<()> {
    let bytes = serde_json::to_vec(v)?;
    if bytes.len() > MAX_FRAME {
        return Err(anyhow!("frame too large"));
    }
    w.write_all(&(bytes.len() as u32).to_be_bytes()).await?;
    w.write_all(&bytes).await?;
    Ok(())
}

async fn read_frame<R: AsyncReadExt + Unpin>(r: &mut R) -> Result<Value> {
    let mut len = [0u8; 4];
    r.read_exact(&mut len).await?;
    let len = u32::from_be_bytes(len) as usize;
    if len > MAX_FRAME {
        return Err(anyhow!("frame too large ({len})"));
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf).await?;
    Ok(serde_json::from_slice(&buf)?)
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

// ---------------------------------------------------------------- one connected peer

/// Pumps one established link until it ends. `peer` is the id we report to the mod.
async fn run_peer<R, W>(
    conn: Option<Connection>,
    mut send: W,
    mut recv: R,
    peer: u32,
    mut outbound: mpsc::UnboundedReceiver<Value>,
    out: Out,
    accept_saves: bool,
) -> String
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin,
{
    let mut ping = tokio::time::interval(PING_EVERY);
    let mut stat = tokio::time::interval(Duration::from_secs(1)); // 0.1.14: connection numbers once a second, independent of the pings (which queue behind battle data)
    let mut last_rx = Instant::now();
    let mut save_bytes: u64 = 0;
    // read_frame is not cancel-safe (a half-read frame would be lost if another select! branch fired),
    // so frames are read by their own task and handed over through a channel.
    let (in_tx, mut in_rx) = mpsc::unbounded_channel::<Result<Value>>();
    let reader = tokio::spawn(async move {
        loop {
            let f = read_frame(&mut recv).await;
            let stop = f.is_err();
            if in_tx.send(f).is_err() || stop { break; }
        }
    });
    let reason: String = loop {
        tokio::select! {
            frame = in_rx.recv() => {
                let frame = frame.unwrap_or_else(|| Err(anyhow!("reader stopped")));
                match frame {
                    Ok(v) => {
                        last_rx = Instant::now();
                        match v.get("t").and_then(Value::as_str) {
                            Some("ping") => {
                                let ts = v.get("ts").cloned().unwrap_or(Value::Null);
                                if write_frame(&mut send, &json!({"t":"pong","ts":ts})).await.is_err() {
                                    break "send failed".to_string();
                                }
                            }
                            Some("pong") => {
                                if let Some(ts) = v.get("ts").and_then(Value::as_u64) {
                                    let ms = now_ms().saturating_sub(ts);
                                    // which network path the link uses right now (debug log / F10 overlay)
                                    let mut path = "none"; let mut qrtt = 0u64;
                                    // 0.1.13: packets lost / sent on this connection so far (the F9 report turns it into a loss rate)
                                    let (mut lost, mut sent) = (0u64, 0u64);
                                    if let Some(c) = &conn { let st = c.stats(); lost = st.lost_packets; sent = st.udp_tx.datagrams; }
                                    match &conn {
                                        Some(conn) => { for p in conn.paths().iter() {
                                            if p.is_selected() { path = if p.is_relay() { "relay" } else if p.is_ip() { "direct" } else { "other" }; qrtt = p.rtt().as_millis() as u64; }
                                        } }
                                        None => { path = "direct"; } // LAN (plain TCP)
                                    }
                                    let _ = out.send(json!({"ev":"latency","peer":peer,"ms":ms,"path":path,"qrtt":qrtt,"lost":lost,"sent":sent}));
                                }
                            }
                            Some("msg") => {
                                if let Some(d) = v.get("d").and_then(Value::as_str) {
                                    let _ = out.send(json!({"ev":"message","peer":peer,"data":d}));
                                }
                            }
                            Some("save_begin") | Some("save_file") | Some("save_done") => {
                                // Only a guest takes a save, and only from the host. The host never lets a guest
                                // write into its saves folder.
                                if accept_saves { handle_save_frame(&v, &out, &mut save_bytes); }
                                else { log(&format!("ignored save frame from peer {peer}")); }
                            }
                            Some("bye") => break "left".to_string(),
                            _ => {}
                        }
                    }
                    Err(e) => break format!("connection lost ({e})"),
                }
            }
            msg = outbound.recv() => {
                match msg {
                    Some(v) => {
                        if v.get("t").and_then(Value::as_str) == Some("bye") {
                            let _ = write_frame(&mut send, &v).await;
                            break "closed locally".to_string();
                        }
                        if let Err(e) = write_frame(&mut send, &v).await {
                            break format!("send failed ({e})");
                        }
                    }
                    None => break "closed locally".to_string(),
                }
            }
            _ = stat.tick() => {
                if let Some(c) = &conn {
                    let st = c.stats();
                    let mut path = "none"; let mut qrtt = 0u64;
                    for p in c.paths().iter() {
                        if p.is_selected() { path = if p.is_relay() { "relay" } else if p.is_ip() { "direct" } else { "other" }; qrtt = p.rtt().as_millis() as u64; }
                    }
                    let _ = out.send(json!({"ev":"netstat","peer":peer,"path":path,"qrtt":qrtt,"lost":st.lost_packets,"lost_bytes":st.lost_bytes,
                        "sent":st.udp_tx.datagrams,"recv":st.udp_rx.datagrams,"sent_bytes":st.udp_tx.bytes,"recv_bytes":st.udp_rx.bytes}));
                }
            }
            _ = ping.tick() => {
                if last_rx.elapsed() > DEAD_AFTER {
                    break "timed out".to_string();
                }
                if write_frame(&mut send, &json!({"t":"ping","ts":now_ms()})).await.is_err() {
                    break "send failed".to_string();
                }
            }
            _ = async { match &conn { Some(c) => { c.closed().await; } None => std::future::pending::<()>().await } } => break "connection closed".to_string(),
        }
    };
    reader.abort();
    let _ = send.shutdown().await;
    if let Some(c) = &conn { c.close(0u32.into(), b"bye"); }
    reason
}

// ---------------------------------------------------------------- session

struct PeerHandle {
    tx: mpsc::UnboundedSender<Value>,
}

struct Session {
    endpoint: Option<Endpoint>,
    is_host: bool,
    peers: Arc<Mutex<HashMap<u32, PeerHandle>>>,
    tasks: Vec<JoinHandle<()>>,
}

impl Session {
    async fn stop(self) {
        for (_, p) in self.peers.lock().await.iter() {
            let _ = p.tx.send(json!({"t":"bye"}));
        }
        tokio::time::sleep(Duration::from_millis(150)).await;
        for t in &self.tasks {
            t.abort();
        }
        if let Some(ep) = &self.endpoint { ep.close().await; }
    }
}

/// Where the host keeps its identity (secret key) and last port, so a restarted helper gives the same join code.
fn host_state_paths() -> Vec<std::path::PathBuf> {
    let mut v = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() { v.push(dir.join("starmp_host_identity.txt")); }
    }
    v.push(std::env::temp_dir().join("starmp_host_identity.txt"));
    v
}

fn load_host_state() -> (iroh::SecretKey, u16) {
    for p in host_state_paths() {
        if let Ok(txt) = std::fs::read_to_string(&p) {
            let mut lines = txt.lines();
            let hex = lines.next().unwrap_or("").trim();
            let port: u16 = lines.next().and_then(|l| l.trim().parse().ok()).unwrap_or(0);
            if hex.len() == 64 {
                let mut b = [0u8; 32];
                let ok = (0..32).all(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).map(|x| b[i] = x).is_ok());
                if ok { log(&format!("host identity loaded from {}", p.display())); return (iroh::SecretKey::from_bytes(&b), port); }
            }
        }
    }
    (iroh::SecretKey::generate(), 0)
}

fn save_host_state(key: &iroh::SecretKey, port: u16) {
    let hex: String = key.to_bytes().iter().map(|b| format!("{b:02x}")).collect();
    for p in host_state_paths() {
        if std::fs::write(&p, format!("{hex}\n{port}\n")).is_ok() { return; }
    }
    log("warning: could not save the host identity (join code will change after a restart)");
}

async fn bind_host(lan: bool, key: &iroh::SecretKey, port: u16) -> Result<Endpoint> {
    let builder = if lan { Endpoint::builder(presets::N0DisableRelay) } else { Endpoint::builder(presets::N0) };
    let mut builder = builder.alpns(vec![ALPN.to_vec()]).secret_key(key.clone());
    if port != 0 {
        builder = builder
            .clear_ip_transports()
            .bind_addr(format!("0.0.0.0:{port}").as_str()).map_err(|e| anyhow!("{e:?}"))?
            .bind_addr_with_opts("[::]:0", iroh::endpoint::BindOpts::default().set_is_required(false)).map_err(|e| anyhow!("{e:?}"))?;
    }
    builder.bind().await.map_err(|e| anyhow!("{e:#}"))
}

async fn start_host(name: String, lan: bool, out: Out) -> Result<Session> {
    let (key, saved_port) = load_host_state();
    let endpoint = match bind_host(lan, &key, saved_port).await {
        Ok(ep) => ep,
        Err(e) if saved_port != 0 => {
            log(&format!("port {saved_port} not available ({e}); using a new one (join code changes)"));
            bind_host(lan, &key, 0).await.context("bind endpoint")?
        }
        Err(e) => return Err(e).context("bind endpoint"),
    };
    let port = endpoint.bound_sockets().into_iter().find(|a| a.is_ipv4()).map(|a| a.port()).unwrap_or(0);
    save_host_state(&key, port);
    if !lan {
        // Wait (briefly) for the relay so the code carries one; fall back to direct addresses.
        let _ = tokio::time::timeout(Duration::from_secs(8), endpoint.online()).await;
    }
    let mut addr = endpoint.addr();
    if lan || addr.relay_urls().next().is_none() {
        // Direct addresses are discovered shortly after bind; on some systems (seen under Wine) the first
        // read is empty, which produced a join code nobody could use. Wait up to 5 s for at least one.
        for _ in 0..50 {
            if addr.ip_addrs().next().is_some() { break; }
            tokio::time::sleep(Duration::from_millis(100)).await;
            addr = endpoint.addr();
        }
        if addr.ip_addrs().next().is_none() {
            // Fallback: ask the OS which local IP it would route through (no packet is sent), pair it with our port.
            let local_ip = std::net::UdpSocket::bind("0.0.0.0:0")
                .and_then(|s| { s.connect("8.8.8.8:80")?; s.local_addr() })
                .map(|a| a.ip())
                .ok();
            let port = endpoint.bound_sockets().into_iter().find(|a| a.is_ipv4()).map(|a| a.port());
            match (local_ip, port) {
                (Some(ip), Some(port)) if !ip.is_unspecified() => {
                    let sa = std::net::SocketAddr::new(ip, port);
                    log(&format!("no direct addresses discovered; using fallback {sa}"));
                    addr = addr.with_ip_addr(sa);
                }
                _ => log("warning: no direct addresses found for the join code"),
            }
        }
    }
    let has_relay = addr.relay_urls().next().is_some();
    let code = make_code(&addr, lan || !has_relay);
    log(&format!("hosting; relay={} lan={}", has_relay, lan));
    let _ = out.send(json!({"ev":"ready","code":code,"id":endpoint.id().to_string(),"relay":has_relay}));

    let peers: Arc<Mutex<HashMap<u32, PeerHandle>>> = Arc::new(Mutex::new(HashMap::new()));
    let next_id = Arc::new(AtomicU32::new(1));
    let ep2 = endpoint.clone();
    let peers2 = peers.clone();
    let out2 = out.clone();
    let host_name = name.clone();
    let accept_task = tokio::spawn(async move {
        while let Some(incoming) = ep2.accept().await {
            let peers = peers2.clone();
            let out = out2.clone();
            let next_id = next_id.clone();
            let host_name = host_name.clone();
            tokio::spawn(async move {
                let res: Result<()> = async {
                    let accepting = incoming.accept().map_err(|e| anyhow!("{e}"))?;
                    let conn = accepting.await.map_err(|e| anyhow!("{e}"))?;
                    let (send, recv) = conn.accept_bi().await.map_err(|e| anyhow!("{e}"))?;
                    host_serve_peer(Some(conn), send, recv, peers, next_id, out.clone(), host_name).await
                }
                .await;
                if let Err(e) = res {
                    log(&format!("incoming connection failed: {e:#}"));
                    let _ = out.send(json!({"ev":"error","text":format!("incoming connection failed: {e}")}));
                }
            });
        }
    });
    Ok(Session { endpoint: Some(endpoint), is_host: true, peers, tasks: vec![accept_task] })
}


/// Host side of one incoming link (Iroh or LAN): wait for hello, answer welcome, then pump frames until it ends.
async fn host_serve_peer<R, W>(
    conn: Option<Connection>,
    mut send: W,
    mut recv: R,
    peers: Arc<Mutex<HashMap<u32, PeerHandle>>>,
    next_id: Arc<AtomicU32>,
    out: Out,
    host_name: String,
) -> Result<()>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin,
{
    let hello = tokio::time::timeout(Duration::from_secs(10), read_frame(&mut recv))
        .await
        .map_err(|_| anyhow!("hello timed out"))??;
    if hello.get("t").and_then(Value::as_str) != Some("hello") {
        return Err(anyhow!("expected hello"));
    }
    let name = hello.get("name").and_then(Value::as_str).unwrap_or("player").to_string();
    let id = next_id.fetch_add(1, Ordering::SeqCst);
    write_frame(&mut send, &json!({"t":"welcome","id":id,"host_name":host_name})).await?;
    let (tx, rx) = mpsc::unbounded_channel();
    peers.lock().await.insert(id, PeerHandle { tx });
    let _ = out.send(json!({"ev":"connected","peer":id,"name":name,"you":0}));
    let reason = run_peer(conn, send, recv, id, rx, out.clone(), false).await;
    peers.lock().await.remove(&id);
    let _ = out.send(json!({"ev":"peer_left","peer":id,"reason":reason}));
    Ok(())
}

// ---------------------------------------------------------------- LAN mode (0.9.4.0): plain TCP, no Iroh
// Host: a TCP listener plus a small UDP "who is hosting?" responder. Join: a UDP probe (broadcast on every
// local network, plus this PC) finds hosts; the link itself is one TCP connection carrying the same frames.

const LAN_PORT: u16 = 24780;
const LAN_PREFIX: &str = "LAN:";
const LAN_PROBE: &[u8] = b"SSMP-LAN?";
const LAN_REPLY: &[u8] = b"SSMP-LAN!";

/// IPv4 addresses (with prefix length) of this PC's network adapters, without loopback / link-local.
fn local_ipv4s() -> Vec<(std::net::Ipv4Addr, u8)> {
    let mut v = Vec::new();
    let nets = sysinfo::Networks::new_with_refreshed_list();
    for (_, data) in &nets {
        for ipn in data.ip_networks() {
            if let std::net::IpAddr::V4(a) = ipn.addr {
                if a.is_loopback() || a.is_link_local() || a.is_unspecified() { continue; }
                if !v.iter().any(|(x, _)| *x == a) { v.push((a, ipn.prefix)); }
            }
        }
    }
    v
}

fn directed_broadcast(a: std::net::Ipv4Addr, prefix: u8) -> Option<std::net::Ipv4Addr> {
    if prefix == 0 || prefix > 30 { return None; }
    let mask: u32 = u32::MAX << (32 - prefix as u32);
    Some(std::net::Ipv4Addr::from((u32::from(a) & mask) | !mask))
}

async fn start_host_lan(name: String, out: Out) -> Result<Session> {
    let listener = {
        let mut l = None;
        for p in LAN_PORT..LAN_PORT + 10 {
            if let Ok(x) = TcpListener::bind(("0.0.0.0", p)).await { l = Some(x); break; }
        }
        match l { Some(x) => x, None => TcpListener::bind(("0.0.0.0", 0)).await.context("open the LAN port")? }
    };
    let port = listener.local_addr()?.port();
    let ips = local_ipv4s();
    let ip = ips.first().map(|x| x.0.to_string()).unwrap_or_else(|| {
        std::net::UdpSocket::bind("0.0.0.0:0").and_then(|s| { s.connect("8.8.8.8:80")?; s.local_addr() }).map(|a| a.ip().to_string()).unwrap_or_else(|_| "127.0.0.1".into())
    });
    let code = format!("{LAN_PREFIX}{ip}:{port}");
    log(&format!("hosting on the LAN (no Iroh): {code}"));
    let _ = out.send(json!({"ev":"ready","code":code,"id":"","relay":false,"lan":true}));

    let peers: Arc<Mutex<HashMap<u32, PeerHandle>>> = Arc::new(Mutex::new(HashMap::new()));
    let next_id = Arc::new(AtomicU32::new(1));
    let mut tasks = Vec::new();

    // discovery responder (fixed UDP port; a second host on the same PC simply isn't discoverable - join it by address)
    match UdpSocket::bind(("0.0.0.0", LAN_PORT)).await {
        Ok(sock) => {
            let peers = peers.clone();
            let host_name = name.clone();
            tasks.push(tokio::spawn(async move {
                let mut buf = [0u8; 64];
                loop {
                    let Ok((n, from)) = sock.recv_from(&mut buf).await else { continue };
                    if &buf[..n] != LAN_PROBE { continue; }
                    let players = peers.try_lock().map(|p| p.len() + 1).unwrap_or(1);
                    let mut msg = LAN_REPLY.to_vec();
                    msg.extend_from_slice(json!({"name":host_name,"port":port,"players":players}).to_string().as_bytes());
                    let _ = sock.send_to(&msg, from).await;
                }
            }));
        }
        Err(e) => log(&format!("LAN discovery port {LAN_PORT} not available ({e}); joiners must type this PC's address")),
    }

    let peers2 = peers.clone();
    let out2 = out.clone();
    tasks.push(tokio::spawn(async move {
        loop {
            let Ok((stream, from)) = listener.accept().await else { continue };
            let _ = stream.set_nodelay(true);
            log(&format!("LAN connection from {from}"));
            let (recv, send) = stream.into_split();
            let (peers, out, next_id, host_name) = (peers2.clone(), out2.clone(), next_id.clone(), name.clone());
            tokio::spawn(async move {
                if let Err(e) = host_serve_peer(None, send, recv, peers, next_id, out.clone(), host_name).await {
                    log(&format!("incoming LAN connection failed: {e:#}"));
                    let _ = out.send(json!({"ev":"error","text":format!("incoming connection failed: {e}")}));
                }
            });
        }
    }));
    Ok(Session { endpoint: None, is_host: true, peers, tasks })
}

async fn start_join_lan(addr: String, name: String, out: Out) -> Result<Session> {
    let a = addr.trim().strip_prefix(LAN_PREFIX).unwrap_or(addr.trim()).trim().to_string();
    let a = if a.contains(':') { a } else { format!("{a}:{LAN_PORT}") };
    log(&format!("joining {a} over the LAN (no Iroh)"));
    let stream = tokio::time::timeout(Duration::from_secs(10), TcpStream::connect(a.as_str()))
        .await
        .map_err(|_| anyhow!("timed out connecting to {a}"))?
        .map_err(|e| anyhow!("could not connect to {a}: {e}"))?;
    let _ = stream.set_nodelay(true);
    let (mut recv, mut send) = stream.into_split();
    write_frame(&mut send, &json!({"t":"hello","name":name,"v":1})).await?;
    let welcome = tokio::time::timeout(Duration::from_secs(10), read_frame(&mut recv))
        .await
        .map_err(|_| anyhow!("host did not answer"))??;
    if welcome.get("t").and_then(Value::as_str) != Some("welcome") {
        return Err(anyhow!("host sent an unexpected reply"));
    }
    let you = welcome.get("id").and_then(Value::as_u64).unwrap_or(0);
    let host_name = welcome.get("host_name").and_then(Value::as_str).unwrap_or("host");
    let _ = out.send(json!({"ev":"connected","peer":0,"name":host_name,"you":you}));
    let peers: Arc<Mutex<HashMap<u32, PeerHandle>>> = Arc::new(Mutex::new(HashMap::new()));
    let (tx, rx) = mpsc::unbounded_channel();
    peers.lock().await.insert(0, PeerHandle { tx });
    let (peers2, out2) = (peers.clone(), out.clone());
    let task = tokio::spawn(async move {
        let reason = run_peer(None, send, recv, 0, rx, out2.clone(), true).await;
        peers2.lock().await.remove(&0);
        let _ = out2.send(json!({"ev":"peer_left","peer":0,"reason":reason}));
    });
    Ok(Session { endpoint: None, is_host: false, peers, tasks: vec![task] })
}

/// Look for LAN hosts for ~1.3 s and report them as one "lan_hosts" event.
async fn scan_lan(out: Out) {
    let Ok(sock) = UdpSocket::bind(("0.0.0.0", 0)).await else { let _ = out.send(json!({"ev":"lan_hosts","list":""})); return; };
    let _ = sock.set_broadcast(true);
    let mut targets: Vec<SocketAddr> = vec![
        SocketAddr::from(([127, 0, 0, 1], LAN_PORT)),
        SocketAddr::from(([255, 255, 255, 255], LAN_PORT)),
    ];
    for (ip, prefix) in local_ipv4s() {
        if let Some(b) = directed_broadcast(ip, prefix) { targets.push(SocketAddr::new(b.into(), LAN_PORT)); }
    }
    let mut found: Vec<(String, String, u16, u64)> = Vec::new(); // (name, ip, port, players)
    let end = Instant::now() + Duration::from_millis(1300);
    let mut next_probe = Instant::now();
    let mut buf = [0u8; 512];
    while Instant::now() < end {
        if Instant::now() >= next_probe {
            for t in &targets { let _ = sock.send_to(LAN_PROBE, t).await; }
            next_probe += Duration::from_millis(450);
        }
        if let Ok(Ok((n, from))) = tokio::time::timeout(Duration::from_millis(100), sock.recv_from(&mut buf)).await {
            if n > LAN_REPLY.len() && buf[..LAN_REPLY.len()] == *LAN_REPLY {
                if let Ok(v) = serde_json::from_slice::<Value>(&buf[LAN_REPLY.len()..n]) {
                    let name = v.get("name").and_then(Value::as_str).unwrap_or("host").to_string();
                    let port = v.get("port").and_then(Value::as_u64).unwrap_or(LAN_PORT as u64) as u16;
                    let players = v.get("players").and_then(Value::as_u64).unwrap_or(1);
                    let ip = from.ip().to_string();
                    if !found.iter().any(|f| f.1 == ip && f.2 == port) { found.push((name, ip, port, players)); }
                }
            }
        }
    }
    // the same host answers on 127.0.0.1 and on its LAN address: keep the real address
    let snapshot = found.clone();
    found.retain(|f| f.1 != "127.0.0.1" || !snapshot.iter().any(|g| g.1 != "127.0.0.1" && g.0 == f.0 && g.2 == f.2));
    // flat string (the mod's tiny JSON reader doesn't parse arrays): one "name<TAB>addr<TAB>players" per line
    let list: Vec<String> = found.into_iter().map(|(n, ip, p, pl)| format!("{}\t{ip}:{p}\t{pl}", n.replace(['\t', '\n', '\r'], " "))).collect();
    let _ = out.send(json!({"ev":"lan_hosts","list":list.join("\n")}));
}

async fn start_join(code: String, name: String, out: Out) -> Result<Session> {
    let addr = parse_code(&code)?;
    let lan = addr.relay_urls().next().is_none();
    let builder = if lan {
        Endpoint::builder(presets::N0DisableRelay)
    } else {
        Endpoint::builder(presets::N0)
    };
    let endpoint = builder.bind().await.context("bind endpoint")?;
    log(&format!("joining {} (direct-only={})", addr.id, lan));
    let conn = tokio::time::timeout(Duration::from_secs(20), endpoint.connect(addr, ALPN))
        .await
        .map_err(|_| anyhow!("timed out connecting to the host"))?
        .map_err(|e| anyhow!("could not connect: {e}"))?;
    let (mut send, mut recv) = conn.open_bi().await.map_err(|e| anyhow!("{e}"))?;
    write_frame(&mut send, &json!({"t":"hello","name":name,"v":1})).await?;
    let welcome = tokio::time::timeout(Duration::from_secs(10), read_frame(&mut recv))
        .await
        .map_err(|_| anyhow!("host did not answer"))??;
    if welcome.get("t").and_then(Value::as_str) != Some("welcome") {
        return Err(anyhow!("host sent an unexpected reply"));
    }
    let you = welcome.get("id").and_then(Value::as_u64).unwrap_or(0);
    let host_name = welcome.get("host_name").and_then(Value::as_str).unwrap_or("host");
    let _ = out.send(json!({"ev":"connected","peer":0,"name":host_name,"you":you}));

    let peers: Arc<Mutex<HashMap<u32, PeerHandle>>> = Arc::new(Mutex::new(HashMap::new()));
    let (tx, rx) = mpsc::unbounded_channel();
    peers.lock().await.insert(0, PeerHandle { tx });
    let peers2 = peers.clone();
    let out2 = out.clone();
    let task = tokio::spawn(async move {
        let reason = run_peer(Some(conn), send, recv, 0, rx, out2.clone(), true).await;
        peers2.lock().await.remove(&0);
        let _ = out2.send(json!({"ev":"peer_left","peer":0,"reason":reason}));
    });
    Ok(Session { endpoint: Some(endpoint), is_host: false, peers, tasks: vec![task] })
}

// ---------------------------------------------------------------- main loop

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(|a| a == "--hwprobe").unwrap_or(false) {
        hw::probe_main(args.get(2).and_then(|p| p.parse().ok()));
        return Ok(());
    }
    real_main()
}

#[tokio::main]
async fn real_main() -> Result<()> {
    let (out_tx, mut out_rx) = mpsc::unbounded_channel::<Value>();
    // Single writer so events never interleave.
    let writer = tokio::spawn(async move {
        let mut so = tokio::io::stdout();
        while let Some(v) = out_rx.recv().await {
            let mut line = serde_json::to_vec(&v).unwrap_or_default();
            line.push(b'\n');
            if so.write_all(&line).await.is_err() || so.flush().await.is_err() {
                break;
            }
        }
    });

    log(&format!("smnet {} ready (iroh + LAN transports)", env!("CARGO_PKG_VERSION")));
    let _ = out_tx.send(json!({"ev":"hello","version":env!("CARGO_PKG_VERSION")}));

    // Debug log / crash report: its own thread. Its commands are handled straight from the reader below, so they
    // keep flowing even while a join is in progress (which can take ~20 s).
    let (diag_tx, diag_rx) = std::sync::mpsc::channel::<diag::DiagMsg>();
    let diag_thread = std::thread::Builder::new().name("smnet-diag".into()).spawn(move || diag::run(diag_rx)).ok();
    let player_name = std::sync::Arc::new(std::sync::Mutex::new(String::from("Player")));
    let diag_main = diag_tx.clone();
    let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<Value>();
    {
        let out_tx = out_tx.clone();
        let player_name = player_name.clone();
        let diag_tx = diag_tx;
        tokio::spawn(async move {
            let mut hw_started = false;
            let mut lines = BufReader::new(tokio::io::stdin()).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                let line = line.trim();
                if line.is_empty() { continue; }
                let cmd: Value = match serde_json::from_str(line) {
                    Ok(v) => v,
                    Err(e) => { let _ = out_tx.send(json!({"ev":"error","text":format!("bad command: {e}")})); continue; }
                };
                let s = |k: &str| cmd.get(k).and_then(Value::as_str).unwrap_or("").to_string();
                match cmd.get("cmd").and_then(Value::as_str).unwrap_or("") {
                    "diag" => { let _ = diag_tx.send(diag::DiagMsg::Lines(s("d"))); }
                    "diag_hdr" => { let _ = diag_tx.send(diag::DiagMsg::GameHeader(s("text"))); }
                    "diag_init" => {
                        let player = s("player");
                        if let Ok(mut p) = player_name.lock() { *p = player.clone(); }
                        let _ = diag_tx.send(diag::DiagMsg::Config {
                            player, report: s("report"), mod_logs: s("modlogs"), tz_abbr: s("tz"),
                            offset_min: cmd.get("offset").and_then(Value::as_i64).unwrap_or(0),
                        });
                        if !hw_started && cmd.get("hw").and_then(Value::as_bool).unwrap_or(true) {
                            hw_started = true;
                            // separate process: a failure in the probe must never take the helper (and networking) down
                            hw::start_probe(diag_tx.clone(), out_tx.clone(), game_pid(), player_name.clone());
                        }
                    }
                    "diag_clean" => { let _ = diag_tx.send(diag::DiagMsg::Clean); }
                    "diag_clip" => {
                        let (rtx, rrx) = std::sync::mpsc::channel();
                        let _ = diag_tx.send(diag::DiagMsg::Clip(rtx));
                        let o = out_tx.clone();
                        std::thread::spawn(move || {
                            let r = rrx.recv_timeout(Duration::from_secs(10)).unwrap_or_else(|_| "error: timed out".into());
                            let _ = o.send(json!({"ev":"diag_clip","path":r}));
                        });
                    }
                    _ => { if cmd_tx.send(cmd).is_err() { break; } }
                }
            }
            // stdin closed: the game is gone. Dropping diag_tx lets the diag thread finish the crash report.
        });
    }

    let mut session: Option<Session> = None;
    while let Some(cmd) = cmd_rx.recv().await {
        match cmd.get("cmd").and_then(Value::as_str).unwrap_or("") {
            "host" => {
                if session.is_some() {
                    let _ = out_tx.send(json!({"ev":"error","text":"already hosting or connected"}));
                    continue;
                }
                let name = cmd.get("name").and_then(Value::as_str).unwrap_or("host").to_string();
                let lan = cmd.get("lan").and_then(Value::as_bool).unwrap_or(false);
                match if lan { start_host_lan(name, out_tx.clone()).await } else { start_host(name, lan, out_tx.clone()).await } {
                    Ok(s) => session = Some(s),
                    Err(e) => {
                        let _ = out_tx.send(json!({"ev":"error","text":format!("{e:#}")}));
                    }
                }
            }
            "join" => {
                if session.is_some() {
                    let _ = out_tx.send(json!({"ev":"error","text":"already hosting or connected"}));
                    continue;
                }
                let code = cmd.get("code").and_then(Value::as_str).unwrap_or("").to_string();
                let name = cmd.get("name").and_then(Value::as_str).unwrap_or("player").to_string();
                match if code.trim().starts_with(LAN_PREFIX) { start_join_lan(code, name, out_tx.clone()).await } else { start_join(code, name, out_tx.clone()).await } {
                    Ok(s) => session = Some(s),
                    Err(e) => {
                        let _ = out_tx.send(json!({"ev":"error","text":format!("{e:#}")}));
                    }
                }
            }
            "lan_scan" => { tokio::spawn(scan_lan(out_tx.clone())); }
            "send" => {
                if let Some(s) = &session {
                    let peer = if s.is_host { cmd.get("peer").and_then(Value::as_u64).unwrap_or(0) as u32 } else { 0 };
                    let data = cmd.get("data").and_then(Value::as_str).unwrap_or("");
                    if B64.decode(data).is_err() {
                        let _ = out_tx.send(json!({"ev":"error","text":"send: data is not valid base64"}));
                    } else if let Some(p) = s.peers.lock().await.get(&peer) {
                        let _ = p.tx.send(json!({"t":"msg","d":data}));
                    }
                }
            }
            "broadcast" => {
                if let Some(s) = &session {
                    let data = cmd.get("data").and_then(Value::as_str).unwrap_or("");
                    for (_, p) in s.peers.lock().await.iter() {
                        let _ = p.tx.send(json!({"t":"msg","d":data}));
                    }
                }
            }
            "save_root" => {
                let dir = cmd.get("dir").and_then(Value::as_str).unwrap_or("").to_string();
                if let Ok(mut g) = SAVE_ROOT.lock() { *g = Some(std::path::PathBuf::from(&dir)); }
                shared_saves_watch(std::path::PathBuf::from(&dir), out_tx.clone());
                let all = list_saves(std::path::Path::new(&dir));
                let newest = all.first().map(|x| x.0.clone()).unwrap_or_default();
                let saves: Vec<Value> = all.into_iter().take(10).map(|(n, t)| json!({"name":n,"mtime":t})).collect();
                let _ = out_tx.send(json!({"ev":"saves","dir":dir,"newest":newest,"count":saves.len(),"saves":saves}));
            }
            "send_save" => {
                let root = SAVE_ROOT.lock().ok().and_then(|g| g.clone());
                let mut name = cmd.get("name").and_then(Value::as_str).unwrap_or("").to_string();
                match (&session, root) {
                    (Some(s), Some(root)) => {
                        if name.is_empty() { name = list_saves(&root).first().map(|x| x.0.clone()).unwrap_or_default(); }
                        if name.is_empty() || !safe_component(&name) {
                            let _ = out_tx.send(json!({"ev":"save_error","text":"no save to send"}));
                            continue;
                        }
                        let targets: Vec<u32> = match cmd.get("peer").and_then(Value::as_u64) {
                            Some(p) => vec![if s.is_host { p as u32 } else { 0 }],
                            None => s.peers.lock().await.keys().cloned().collect(),
                        };
                        for t in targets {
                            if let Some(p) = s.peers.lock().await.get(&t) {
                                match queue_save(&root, &name, &p.tx) {
                                    Ok((n, b)) => { let _ = out_tx.send(json!({"ev":"save_sent","peer":t,"name":name,"files":n,"bytes":b})); }
                                    Err(e) => { let _ = out_tx.send(json!({"ev":"save_error","text":format!("{e:#}")})); }
                                }
                            }
                        }
                    }
                    _ => { let _ = out_tx.send(json!({"ev":"save_error","text":"not connected or no save folder"})); }
                }
            }
            "disconnect" => {
                if let Some(s) = session.take() {
                    s.stop().await;
                }
                let _ = out_tx.send(json!({"ev":"stopped"}));
            }
            "quit" => {
                remove_instance_marker(); // first: the game may stop us 1.5 s after asking (0.1.11)
                if let Some(s) = session.take() {
                    s.stop().await;
                }
                break;
            }
            other => {
                let _ = out_tx.send(json!({"ev":"error","text":format!("unknown command: {other}")}));
            }
        }
    }
    // stdin closed (the game exited) -> shut down cleanly.
    remove_instance_marker();
    if let Some(s) = session.take() {
        s.stop().await;
    }
    // let the crash report be finished (deleted on a clean exit, completed otherwise)
    let _ = diag_main.send(diag::DiagMsg::Finish);
    if let Some(t) = diag_thread {
        let (dtx, drx) = std::sync::mpsc::channel();
        std::thread::spawn(move || { let _ = t.join(); let _ = dtx.send(()); });
        let _ = drx.recv_timeout(Duration::from_secs(3));
    }
    drop(out_tx);
    let _ = tokio::time::timeout(Duration::from_secs(1), writer).await;
    Ok(())
}

/// The game process that started us (our parent), for its CPU / RAM numbers.
fn game_pid() -> Option<u32> {
    #[cfg(unix)]
    { Some(std::os::unix::process::parent_id()) }
    #[cfg(not(unix))]
    {
        let mut sys = sysinfo::System::new();
        let me = sysinfo::Pid::from_u32(std::process::id());
        sys.refresh_processes(sysinfo::ProcessesToUpdate::Some(&[me]), true);
        sys.process(me).and_then(|p| p.parent()).map(|p| p.as_u32())
    }
}
