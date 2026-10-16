//! Hardware and performance probe (0.1.5.3) for the debug log and the F10 overlay.
//!
//! WHAT THIS READS (read-only, nothing is changed and nothing is sent over the network):
//!  - once: CPU model / cores / threads / clock, RAM size + stick count + rated and actual speed, GPU model / VRAM /
//!    driver, whether the game's drive is an SSD or HDD, network adapter type (Wi-Fi / Ethernet) and link speed,
//!    OS version.
//!  - every second: the game's CPU use and RAM use, total CPU use, live CPU clock, GPU use and VRAM in use.
//! It does NOT list other running programs, installed software, files, or anything outside these numbers.
//! Everything is best effort: a value Windows doesn't give us shows as "n/a".

use std::{
    sync::mpsc::Sender,
    time::{Duration, Instant},
};

use serde_json::{json, Value};
use sysinfo::{Pid, ProcessesToUpdate, System};

use crate::diag::DiagMsg;

/// The probe runs as a SEPARATE child process ("smnet --hwprobe <pid>"): if anything in the OS calls fails hard (a
/// missing Windows function aborts the whole process, which no panic handler can catch), only the probe dies and
/// networking carries on. It prints one JSON line per result on stdout and exits when smnet closes its stdin.
pub fn probe_main(game_pid: Option<u32>) {
    std::thread::spawn(|| { // parent gone -> stop
        let mut b = [0u8; 64];
        use std::io::Read;
        let mut si = std::io::stdin();
        loop { match si.read(&mut b) { Ok(0) | Err(_) => std::process::exit(0), _ => {} } }
    });
    let emit = |kind: &str, text: &str| {
        use std::io::Write;
        let mut so = std::io::stdout();
        let _ = writeln!(so, "{}", json!({"t":kind,"text":text}));
        let _ = so.flush();
    };
    run(&emit, game_pid);
}

/// smnet side: start the probe child and pass its results on (debug log + F10 overlay).
pub fn start_probe(diag: Sender<DiagMsg>, out: tokio::sync::mpsc::UnboundedSender<Value>, game_pid: Option<u32>, player: std::sync::Arc<std::sync::Mutex<String>>) {
    let Ok(exe) = std::env::current_exe() else { return };
    let mut cmd = std::process::Command::new(exe);
    cmd.arg("--hwprobe").arg(game_pid.map(|p| p.to_string()).unwrap_or_default())
        .stdin(std::process::Stdio::piped()).stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::null());
    #[cfg(windows)]
    { use std::os::windows::process::CommandExt; cmd.creation_flags(0x0800_0000); } // CREATE_NO_WINDOW
    let mut child = match cmd.spawn() { Ok(c) => c, Err(e) => { crate::log(&format!("hw probe: cannot start ({e})")); return; } };
    let stdout = child.stdout.take();
    std::thread::spawn(move || {
        let _keep_stdin_open = child.stdin.take(); // the probe exits when this closes (smnet gone)
        if let Some(so) = stdout {
            use std::io::BufRead;
            for line in std::io::BufReader::new(so).lines().map_while(Result::ok) {
                let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
                let text = v.get("text").and_then(Value::as_str).unwrap_or("").to_string();
                match v.get("t").and_then(Value::as_str) {
                    Some("hw") => { let _ = diag.send(DiagMsg::HwHeader(text.clone())); let _ = out.send(json!({"ev":"hw","text":text})); }
                    Some("sys") => {
                        let name = player.lock().map(|p| p.clone()).unwrap_or_default();
                        let ts = crate::diag::now_ms() as i64;
                        let (_, _, _, h, mi, s, ms) = civil_hms(ts);
                        let _ = diag.send(DiagMsg::Lines(format!("{name} : {h:02}:{mi:02}:{s:02}:{ms:03}Z | | SYS | {text}")));
                        let _ = out.send(json!({"ev":"sys","text":text}));
                    }
                    _ => {}
                }
            }
        }
        let st = child.wait().map(|s| s.to_string()).unwrap_or_else(|e| e.to_string());
        crate::log(&format!("hw probe ended ({st}) - hardware numbers stop, everything else continues"));
        let _ = diag.send(DiagMsg::Lines(format!("{} : -- | | SYS | hardware probe stopped ({st})", player.lock().map(|p| p.clone()).unwrap_or_default())));
    });
}

fn run(emit: &dyn Fn(&str, &str), game_pid: Option<u32>) {
    let mut sys = System::new();
    sys.refresh_cpu_all();
    sys.refresh_memory();
    std::thread::sleep(Duration::from_millis(300));
    sys.refresh_cpu_all();

    let mut lines: Vec<String> = Vec::new();
    let cpus = sys.cpus();
    let brand = cpus.first().map(|c| c.brand().trim().to_string()).unwrap_or_else(|| "n/a".into());
    let threads = cpus.len();
    let cores = System::physical_core_count().map(|c| c.to_string()).unwrap_or_else(|| "n/a".into());
    let base_mhz = cpus.first().map(|c| c.frequency()).unwrap_or(0);
    lines.push(format!("CPU:     {brand} | {cores} cores, {threads} threads | clock at start {:.2} GHz", base_mhz as f64 / 1000.0));
    let total_gb = sys.total_memory() as f64 / 1_073_741_824.0;
    let os = System::long_os_version().unwrap_or_else(|| "n/a".into());
    let arch = System::cpu_arch();

    let extra = platform::static_info();
    lines.push(format!("RAM:     {:.1} GB{}", total_gb, extra.ram));
    for g in &extra.gpus { lines.push(format!("GPU:     {g}")); }
    if extra.gpus.is_empty() { lines.push("GPU:     n/a (see GL renderer in the game section)".into()); }
    lines.push(format!("Drive:   {}", extra.drive));
    for n in &extra.net { lines.push(format!("Network: {n}")); }
    if extra.net.is_empty() { lines.push("Network: n/a".into()); }
    lines.push(format!("OS:      {os} ({arch})"));
    let hdr = lines.join("\n");
    emit("hw", &hdr);

    let pid = game_pid.map(Pid::from_u32);
    let mut live = platform::Live::new(base_mhz);
    let mut next = Instant::now();
    loop {
        next += Duration::from_secs(1);
        let now = Instant::now();
        if next > now { std::thread::sleep(next - now); } else { next = now; }
        sys.refresh_cpu_usage();
        sys.refresh_memory();
        let mut game_cpu = f32::NAN; let mut game_mb = 0u64;
        if let Some(p) = pid {
            sys.refresh_processes(ProcessesToUpdate::Some(&[p]), true);
            if let Some(pr) = sys.process(p) { game_cpu = pr.cpu_usage(); game_mb = pr.memory() / 1_048_576; }
        }
        let total_cpu = sys.global_cpu_usage();
        let used_gb = sys.used_memory() as f64 / 1_073_741_824.0;
        let l = live.sample();
        let clock = if l.clock_mhz > 0.0 { format!("{:.2} GHz", l.clock_mhz / 1000.0) } else {
            let f = sys.cpus().iter().map(|c| c.frequency()).max().unwrap_or(0);
            if f > 0 { format!("{:.2} GHz", f as f64 / 1000.0) } else { "n/a".into() }
        };
        let game = if game_cpu.is_nan() { "n/a".into() } else { format!("{:.0}% of PC ({:.1} threads)", game_cpu / threads.max(1) as f32, game_cpu / 100.0) };
        let gpu = if l.gpu_pct >= 0.0 { format!("{:.0}%", l.gpu_pct) } else { "n/a".into() };
        let vram = if l.vram_mb >= 0.0 { format!("{:.1} GB", l.vram_mb / 1024.0) } else { "n/a".into() };
        let text = format!("cpu {:.0}% clock {} | game cpu {} ram {:.1} GB | PC ram {:.1}/{:.1} GB | gpu {} vram {}",
            total_cpu, clock, game, game_mb as f64 / 1024.0, used_gb, total_gb, gpu, vram);
        emit("sys", &text);
    }
}

fn civil_hms(ms: i64) -> (i64, u32, u32, u32, u32, u32, u32) {
    let secs = ms.div_euclid(1000);
    let sod = secs.rem_euclid(86_400) as u32;
    (0, 0, 0, sod / 3600, (sod / 60) % 60, sod % 60, ms.rem_euclid(1000) as u32)
}

pub struct Static { pub ram: String, pub gpus: Vec<String>, pub drive: String, pub net: Vec<String> }
pub struct Sample { pub clock_mhz: f64, pub gpu_pct: f64, pub vram_mb: f64 }

// ------------------------------------------------------------------------------------------------ Windows
#[cfg(windows)]
mod platform {
    use super::{Sample, Static};
    use std::collections::HashMap;
    use wmi::{Variant, WMIConnection};

    fn v_str(m: &HashMap<String, Variant>, k: &str) -> String {
        match m.get(k) {
            Some(Variant::String(s)) => s.trim().to_string(),
            Some(Variant::UI1(x)) => x.to_string(), Some(Variant::UI2(x)) => x.to_string(),
            Some(Variant::UI4(x)) => x.to_string(), Some(Variant::UI8(x)) => x.to_string(),
            Some(Variant::I2(x)) => x.to_string(), Some(Variant::I4(x)) => x.to_string(), Some(Variant::I8(x)) => x.to_string(),
            Some(Variant::Bool(b)) => b.to_string(),
            _ => String::new(),
        }
    }
    fn v_u64(m: &HashMap<String, Variant>, k: &str) -> u64 { v_str(m, k).parse().unwrap_or(0) }

    fn query(ns: Option<&str>, q: &str) -> Vec<HashMap<String, Variant>> {
        let r = (|| -> Result<Vec<HashMap<String, Variant>>, Box<dyn std::error::Error>> {
            let com = wmi::COMLibrary::new()?;
            let con = match ns { Some(n) => WMIConnection::with_namespace_path(n, com)?, None => WMIConnection::new(com)? };
            Ok(con.raw_query(q)?)
        })();
        match r { Ok(v) => v, Err(e) => { crate::log(&format!("hw: wmi query failed ({q}): {e}")); Vec::new() } }
    }

    pub fn static_info() -> Static {
        // RAM sticks: rated vs configured (actual) speed
        let sticks = query(None, "SELECT Capacity, Speed, ConfiguredClockSpeed FROM Win32_PhysicalMemory");
        let mut ram = String::new();
        if !sticks.is_empty() {
            let rated = sticks.iter().map(|m| v_u64(m, "Speed")).max().unwrap_or(0);
            let actual = sticks.iter().map(|m| v_u64(m, "ConfiguredClockSpeed")).max().unwrap_or(0);
            let sizes: Vec<String> = sticks.iter().map(|m| format!("{}", v_u64(m, "Capacity") / 1_073_741_824)).collect();
            ram = format!(", {} stick(s) [{} GB], running {} MT/s (rated {})", sticks.len(), sizes.join("+"),
                if actual > 0 { actual.to_string() } else { "n/a".into() }, if rated > 0 { rated.to_string() } else { "n/a".into() });
        }
        // GPUs: name + driver from WMI, real VRAM from DXGI (WMI caps it at 4 GB)
        let vram = super::dxgi::adapters();
        let mut gpus = Vec::new();
        for m in query(None, "SELECT Name, DriverVersion FROM Win32_VideoController") {
            let name = v_str(&m, "Name");
            let mem = vram.iter().find(|(n, _)| n.trim() == name).map(|(_, b)| format!("{:.1} GB VRAM", *b as f64 / 1_073_741_824.0)).unwrap_or_else(|| "VRAM n/a".into());
            gpus.push(format!("{name} | {mem} | driver {}", v_str(&m, "DriverVersion")));
        }
        // the game's drive: partition (drive letter) -> physical disk -> SSD/HDD
        let letter = std::env::current_dir().ok().and_then(|p| p.to_string_lossy().chars().next()).unwrap_or('C').to_ascii_uppercase();
        let storage = "ROOT\\Microsoft\\Windows\\Storage";
        let mut drive = format!("{letter}: n/a");
        let parts = query(Some(storage), &format!("SELECT DiskNumber, DriveLetter FROM MSFT_Partition"));
        let disk_no = parts.iter().find(|m| v_str(m, "DriveLetter").eq_ignore_ascii_case(&letter.to_string())).map(|m| v_str(m, "DiskNumber"));
        if let Some(dn) = disk_no {
            for m in query(Some(storage), "SELECT DeviceId, MediaType, BusType, FriendlyName FROM MSFT_PhysicalDisk") {
                if v_str(&m, "DeviceId") == dn {
                    let media = match v_u64(&m, "MediaType") { 3 => "HDD", 4 => "SSD", 5 => "SCM", _ => "unknown type" };
                    let bus = match v_u64(&m, "BusType") { 17 => " NVMe", 11 => " SATA", 7 => " USB", _ => "" };
                    drive = format!("{letter}: {media}{bus} ({})", v_str(&m, "FriendlyName"));
                }
            }
        }
        // network adapters that are up and physical
        let mut net = Vec::new();
        for m in query(Some("ROOT\\StandardCimv2"), "SELECT InterfaceDescription, NdisPhysicalMedium, ReceiveLinkSpeed, MediaConnectState, HardwareInterface FROM MSFT_NetAdapter") {
            if v_u64(&m, "MediaConnectState") != 1 || v_str(&m, "HardwareInterface") != "true" { continue; }
            let kind = match v_u64(&m, "NdisPhysicalMedium") { 9 => "Wi-Fi", 14 | 0 => "Ethernet", 10 => "Bluetooth", 8 => "mobile broadband", _ => "other" };
            let speed = v_u64(&m, "ReceiveLinkSpeed");
            net.push(format!("{kind}, link {} | {}", if speed > 0 { format!("{} Mbps", speed / 1_000_000) } else { "n/a".into() }, v_str(&m, "InterfaceDescription")));
        }
        Static { ram, gpus, drive, net }
    }

    pub struct Live { pdh: Option<super::pdh::Pdh>, base_mhz: f64 }
    impl Live {
        pub fn new(_sysinfo_mhz: u64) -> Live {
            let base = query(None, "SELECT MaxClockSpeed FROM Win32_Processor").first().map(|m| v_u64(m, "MaxClockSpeed")).unwrap_or(0) as f64;
            Live { pdh: super::pdh::Pdh::open(), base_mhz: base }
        }
        pub fn sample(&mut self) -> Sample {
            let mut s = Sample { clock_mhz: 0.0, gpu_pct: -1.0, vram_mb: -1.0 };
            if let Some(p) = &mut self.pdh {
                let (perf, gpu, vram) = p.collect();
                if perf > 0.0 && self.base_mhz > 0.0 { s.clock_mhz = self.base_mhz * perf / 100.0; }
                if gpu >= 0.0 { s.gpu_pct = gpu.min(100.0); }
                if vram >= 0.0 { s.vram_mb = vram / 1_048_576.0; }
            }
            s
        }
    }
}

#[cfg(windows)]
mod dxgi {
    use windows::Win32::Graphics::Dxgi::{CreateDXGIFactory1, IDXGIFactory1};
    /// (adapter name, dedicated VRAM bytes)
    pub fn adapters() -> Vec<(String, u64)> {
        let mut v = Vec::new();
        unsafe {
            let Ok(f) = CreateDXGIFactory1::<IDXGIFactory1>() else { return v };
            let mut i = 0;
            while let Ok(a) = f.EnumAdapters1(i) {
                if let Ok(d) = a.GetDesc1() {
                    let n = String::from_utf16_lossy(&d.Description);
                    v.push((n.trim_end_matches('\0').to_string(), d.DedicatedVideoMemory as u64));
                }
                i += 1;
            }
        }
        v
    }
}

#[cfg(windows)]
mod pdh {
    use windows::core::PCWSTR;
    use windows::Win32::System::Performance::*;

    pub struct Pdh { q: PDH_HQUERY, perf: PDH_HCOUNTER, gpu: Option<PDH_HCOUNTER>, vram: Option<PDH_HCOUNTER> }

    fn wide(s: &str) -> Vec<u16> { s.encode_utf16().chain(std::iter::once(0)).collect() }

    impl Pdh {
        pub fn open() -> Option<Pdh> {
            unsafe {
                let mut q = PDH_HQUERY::default();
                if PdhOpenQueryW(PCWSTR::null(), 0, &mut q) != 0 { return None; }
                let add = |path: &str| -> Option<PDH_HCOUNTER> {
                    let w = wide(path);
                    let mut c = PDH_HCOUNTER::default();
                    if PdhAddEnglishCounterW(q, PCWSTR(w.as_ptr()), 0, &mut c) == 0 { Some(c) } else { None }
                };
                let perf = add("\\Processor Information(_Total)\\% Processor Performance")?;
                let gpu = add("\\GPU Engine(*engtype_3D)\\Utilization Percentage");
                let vram = add("\\GPU Adapter Memory(*)\\Dedicated Usage");
                let _ = PdhCollectQueryData(q);
                Some(Pdh { q, perf, gpu, vram })
            }
        }

        fn sum(c: PDH_HCOUNTER) -> f64 {
            unsafe {
                let mut size = 0u32; let mut count = 0u32;
                let _ = PdhGetFormattedCounterArrayW(c, PDH_FMT_DOUBLE, &mut size, &mut count, None);
                if size == 0 { return -1.0; }
                let mut buf = vec![0u8; size as usize];
                if PdhGetFormattedCounterArrayW(c, PDH_FMT_DOUBLE, &mut size, &mut count, Some(buf.as_mut_ptr() as *mut PDH_FMT_COUNTERVALUE_ITEM_W)) != 0 { return -1.0; }
                let items = std::slice::from_raw_parts(buf.as_ptr() as *const PDH_FMT_COUNTERVALUE_ITEM_W, count as usize);
                items.iter().map(|it| it.FmtValue.Anonymous.doubleValue).filter(|x| x.is_finite()).sum()
            }
        }

        /// (% processor performance, 3D engine % summed over engines, dedicated VRAM bytes in use)
        pub fn collect(&mut self) -> (f64, f64, f64) {
            unsafe {
                if PdhCollectQueryData(self.q) != 0 { return (0.0, -1.0, -1.0); }
                let mut v = PDH_FMT_COUNTERVALUE::default();
                let perf = if PdhGetFormattedCounterValue(self.perf, PDH_FMT_DOUBLE, None, &mut v) == 0 { v.Anonymous.doubleValue } else { 0.0 };
                let gpu = self.gpu.map(Self::sum).unwrap_or(-1.0);
                let vram = self.vram.map(Self::sum).unwrap_or(-1.0);
                (perf, gpu, vram)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Linux / other
#[cfg(not(windows))]
mod platform {
    use super::{Sample, Static};

    pub fn static_info() -> Static {
        let mut drive = "n/a".to_string();
        if let Ok(cwd) = std::env::current_dir() {
            // rotational flag of the block device holding the game folder (best effort: first disk listed)
            if let Ok(rd) = std::fs::read_dir("/sys/block") {
                for e in rd.flatten() {
                    let n = e.file_name().to_string_lossy().to_string();
                    if n.starts_with("loop") || n.starts_with("ram") { continue; }
                    if let Ok(r) = std::fs::read_to_string(e.path().join("queue/rotational")) {
                        drive = format!("{} ({n}, game folder {})", if r.trim() == "1" { "HDD" } else { "SSD" }, crate::diag::scrub(&cwd.display().to_string()));
                        break;
                    }
                }
            }
        }
        let mut net = Vec::new();
        if let Ok(rd) = std::fs::read_dir("/sys/class/net") {
            for e in rd.flatten() {
                let n = e.file_name().to_string_lossy().to_string();
                if n == "lo" { continue; }
                let up = std::fs::read_to_string(e.path().join("operstate")).map(|s| s.trim() == "up").unwrap_or(false);
                if !up { continue; }
                let wifi = e.path().join("wireless").exists();
                let speed = std::fs::read_to_string(e.path().join("speed")).ok().and_then(|s| s.trim().parse::<i64>().ok()).filter(|s| *s > 0);
                net.push(format!("{}, link {} | {n}", if wifi { "Wi-Fi" } else { "Ethernet/other" }, speed.map(|s| format!("{s} Mbps")).unwrap_or_else(|| "n/a".into())));
            }
        }
        Static { ram: String::new(), gpus: Vec::new(), drive, net }
    }

    pub struct Live;
    impl Live {
        pub fn new(_base: u64) -> Live { Live }
        pub fn sample(&mut self) -> Sample { Sample { clock_mhz: 0.0, gpu_pct: -1.0, vram_mb: -1.0 } }
    }
}
