//! Kokoro narration server: generates chapter audio for the Webnovel Archiver app over LAN or
//! Tailscale, with the model the app runs on the phone (sherpa-onnx 1.12.21, kokoro-en-v0_19).
//!
//! One shared model serves several sentences at once, and a memory governor shrinks, recycles or
//! unloads it as the machine runs low. Protocol 1, every request but /health needs
//! `Authorization: Bearer <token>`:
//!   GET  /health         -> {"ok": true}
//!   GET  /v1/info        -> {protocol, model, sampleRate, workers, threadsPerWorker, name, server, version, memory}
//!   GET  /v1/status      -> memory, model and work counters
//!   POST /v1/synthesize  -> body {"text", "speakerId"}; 200 audio/wav (mono 16-bit PCM)
//! Errors are JSON {"error"}: 400 bad request, 401 bad token, 500 generation failed, and 503 with
//! `Retry-After` while memory is short. See docs/tts/remote-narration-servers.md.

mod bench_text;
mod engine;
mod http;
mod memory;
mod sherpa;

use engine::{log, Engine, Settings};
use memory::{Policy, MIB};
use std::net::{TcpListener, UdpSocket};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

const USAGE: &str = "Usage: narration-server [options]
  --model-dir DIR        kokoro-en-v0_19 folder (default: ../models/kokoro-en-v0_19 next to the binary)
  --token-file FILE      token file (default: ../token next to the binary)
  --host ADDR            listen address (default 0.0.0.0)
  --port N               port (default 8790)
  --name NAME            name shown in the app (default: host name)
  --threads N            inference threads (default: one per performance core)
  --max-slots N          most sentences generating at once (default: 4 on 8+ cores)
  --reserve-mb N         keep this much memory available for everything else (default: 20% of RAM, 1.5-4 GB)
  --critical-mb N        stop and unload below this much available (default: a third of the reserve)
  --idle-unload-s N      unload the model after this many idle seconds (default 300; 0 = never)
  --verbose              log every request
  --addresses            print this machine's addresses and exit
  --bench [ROUNDS]       measure speed and memory at each slot count, then exit";

struct Args {
    model_dir: PathBuf,
    token_file: PathBuf,
    host: String,
    port: u16,
    name: String,
    threads: usize,
    max_slots: usize,
    reserve_mb: Option<u64>,
    critical_mb: Option<u64>,
    idle_unload: u64,
    verbose: bool,
    addresses: bool,
    bench: Option<usize>,
}

fn parse_args() -> Result<Args, String> {
    let home = std::env::current_exe()
        .ok()
        .and_then(|exe| exe.canonicalize().ok())
        .and_then(|exe| exe.parent().and_then(|bin| bin.parent()).map(|dir| dir.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."));
    let (threads, slots) = default_layout();
    let mut args = Args {
        model_dir: home.join("models").join(http::MODEL_NAME),
        token_file: home.join("token"),
        host: "0.0.0.0".into(),
        port: 8790,
        name: host_name(),
        threads,
        max_slots: slots,
        reserve_mb: None,
        critical_mb: None,
        idle_unload: 300,
        verbose: false,
        addresses: false,
        bench: None,
    };
    let mut raw = std::env::args().skip(1).peekable();
    while let Some(flag) = raw.next() {
        let mut value = |name: &str| raw.next().ok_or(format!("{name} needs a value"));
        let number = |text: String, name: &str| text.parse::<u64>().map_err(|_| format!("{name} needs a number"));
        match flag.as_str() {
            "--model-dir" => args.model_dir = PathBuf::from(value(&flag)?),
            "--token-file" => args.token_file = PathBuf::from(value(&flag)?),
            "--host" => args.host = value(&flag)?,
            "--port" => args.port = number(value(&flag)?, &flag)?.try_into().map_err(|_| "Bad port")?,
            "--name" => args.name = value(&flag)?,
            "--threads" => args.threads = number(value(&flag)?, &flag)?.max(1) as usize,
            // --workers: the Python server's name for it.
            "--max-slots" | "--workers" => args.max_slots = number(value(&flag)?, &flag)?.clamp(1, 16) as usize,
            "--reserve-mb" => args.reserve_mb = Some(number(value(&flag)?, &flag)?),
            "--critical-mb" => args.critical_mb = Some(number(value(&flag)?, &flag)?),
            "--idle-unload-s" => args.idle_unload = number(value(&flag)?, &flag)?,
            "--verbose" => args.verbose = true,
            "--addresses" => args.addresses = true,
            "--bench" => {
                let rounds = match raw.peek() {
                    Some(next) if !next.starts_with("--") => raw.next().and_then(|n| n.parse().ok()).unwrap_or(2),
                    _ => 2,
                };
                args.bench = Some(rounds);
            }
            "-h" | "--help" => return Err(USAGE.to_string()),
            other => return Err(format!("Unknown option {other}\n{USAGE}")),
        }
    }
    Ok(args)
}

/// Threads = performance cores; slots measured best at 4 on 8 cores (Ryzen Z1 Extreme: 6.9x real
/// time in 1.3 GB, against 6.4x in 2.4 GB for four separate model copies).
fn default_layout() -> (usize, usize) {
    let cores = performance_cores();
    let slots = if cores >= 8 {
        4
    } else if cores >= 4 {
        2
    } else {
        1
    };
    (cores.max(1), slots)
}

#[cfg(target_os = "linux")]
fn performance_cores() -> usize {
    let logical = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4);
    let smt = std::fs::read_to_string("/sys/devices/system/cpu/smt/active").map(|v| v.trim() == "1").unwrap_or(false);
    if smt {
        (logical / 2).max(1)
    } else {
        logical
    }
}

#[cfg(target_os = "macos")]
fn performance_cores() -> usize {
    extern "C" {
        fn sysctlbyname(
            name: *const std::ffi::c_char,
            old: *mut std::ffi::c_void,
            len: *mut usize,
            new: *mut std::ffi::c_void,
            newlen: usize,
        ) -> i32;
    }
    let read = |name: &str| -> Option<usize> {
        let name = std::ffi::CString::new(name).ok()?;
        let mut value: i32 = 0;
        let mut size = std::mem::size_of::<i32>();
        // SAFETY: value is a writable i32.
        let ok = unsafe { sysctlbyname(name.as_ptr(), &mut value as *mut i32 as *mut _, &mut size, std::ptr::null_mut(), 0) } == 0;
        (ok && value > 0).then_some(value as usize)
    };
    // Apple silicon: the performance cluster only; efficiency cores slow the others down.
    read("hw.perflevel0.physicalcpu").or_else(|| read("hw.physicalcpu")).unwrap_or(4)
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
fn performance_cores() -> usize {
    std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4)
}

fn host_name() -> String {
    let from_command = std::process::Command::new("hostname")
        .arg("-s")
        .output()
        .ok()
        .map(|out| String::from_utf8_lossy(&out.stdout).trim().to_string())
        .filter(|name| !name.is_empty());
    from_command
        .or_else(|| std::fs::read_to_string("/etc/hostname").ok().map(|name| name.trim().to_string()))
        .map(|name| name.split('.').next().unwrap_or("").to_string())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "computer".to_string())
}

/// This machine's LAN and Tailscale IPv4 addresses, for the app's form.
fn addresses() -> Vec<(String, String)> {
    let mut found = Vec::new();
    // No packet is sent: connecting a UDP socket only picks the outgoing interface.
    if let Ok(probe) = UdpSocket::bind("0.0.0.0:0") {
        if probe.connect("192.0.2.1:9").is_ok() {
            if let Ok(local) = probe.local_addr() {
                found.push(("LAN".to_string(), local.ip().to_string()));
            }
        }
    }
    if let Ok(out) = std::process::Command::new("tailscale").args(["ip", "-4"]).output() {
        for line in String::from_utf8_lossy(&out.stdout).lines().map(str::trim).filter(|l| !l.is_empty()) {
            found.push(("Tailscale".to_string(), line.to_string()));
        }
    }
    found.retain(|(_, address)| !address.starts_with("127."));
    found.dedup();
    found
}

fn policy(args: &Args) -> Policy {
    let total = memory::snapshot().total;
    let mut policy = Policy::for_machine(total, args.max_slots);
    if let Some(mb) = args.reserve_mb {
        policy.reserve = mb * MIB;
    }
    if let Some(mb) = args.critical_mb {
        policy.critical = mb * MIB;
    }
    policy.critical = policy.critical.min(policy.reserve);
    policy
}

fn main() {
    let args = match parse_args() {
        Ok(args) => args,
        Err(message) => {
            eprintln!("{message}");
            std::process::exit(2);
        }
    };
    if args.addresses {
        for (kind, address) in addresses() {
            println!("  Address ({kind}): {address}:{}", args.port);
        }
        return;
    }
    memory::tune_allocator();
    if let Some(rounds) = args.bench {
        bench(&args, rounds);
        return;
    }
    let token = match std::fs::read_to_string(&args.token_file) {
        Ok(token) => token.trim().to_string(),
        Err(_) => fail(&format!("Token file {} not found. Run setup.sh first.", args.token_file.display())),
    };
    if token.len() < 8 {
        fail("The token must be at least 8 characters.");
    }
    if !args.model_dir.join("model.onnx").is_file() {
        fail(&format!("Model not found in {}. Run setup.sh first.", args.model_dir.display()));
    }
    let policy = policy(&args);
    let settings = Settings {
        model_dir: args.model_dir.clone(),
        threads: args.threads,
        policy,
        idle_unload: if args.idle_unload == 0 { Duration::MAX } else { Duration::from_secs(args.idle_unload) },
        verbose: args.verbose,
    };
    let listener = TcpListener::bind((args.host.as_str(), args.port))
        .unwrap_or_else(|error| fail(&format!("Cannot listen on port {}: {error}", args.port)));
    let shared = Arc::new(http::Shared {
        engine: Engine::new(settings),
        token,
        name: args.name.clone(),
        threads: args.threads,
        sample_rate: 24_000,
        keep_awake: http::KeepAwake::new(),
    });
    let governed = shared.clone();
    std::thread::Builder::new()
        .name("governor".into())
        .spawn(move || loop {
            governed.engine.govern();
            std::thread::sleep(Duration::from_secs(1));
        })
        .unwrap_or_else(|_| fail("Could not start the memory governor"));
    log(&format!(
        "Narration server '{}' ready on port {} (sherpa-onnx {}, up to {} sentences at once x {} threads; reserve {} MB, stop below {} MB; model loads on first use)",
        args.name,
        args.port,
        sherpa::version(),
        policy.max_slots,
        args.threads,
        policy.reserve / MIB,
        policy.critical / MIB
    ));
    for (kind, address) in addresses() {
        log(&format!("  App address ({kind}): {address}:{}", args.port));
    }
    http::serve(listener, shared);
}

fn fail(message: &str) -> ! {
    eprintln!("{message}");
    std::process::exit(1);
}

/// Speed and peak memory at 1..=max slots on one shared model, to pick --max-slots and --threads.
fn bench(args: &Args, rounds: usize) {
    use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
    println!(
        "sherpa-onnx {}, {} threads, {} round(s) of {} sentences",
        sherpa::version(),
        args.threads,
        rounds,
        bench_text::SENTENCES.len()
    );
    let work: Vec<&str> = (0..rounds.max(1)).flat_map(|_| bench_text::SENTENCES.iter().copied()).collect();
    println!("slots  speed   peak MB");
    for slots in 1..=args.max_slots {
        let before = memory::snapshot().own;
        let tts = match sherpa::Tts::load(&args.model_dir, args.threads) {
            Ok(tts) => Arc::new(tts),
            Err(error) => fail(&error),
        };
        let _ = tts.generate("Warm up.", 0);
        let next = AtomicUsize::new(0);
        let samples = AtomicU64::new(0);
        let peak = AtomicU64::new(0);
        let done = AtomicBool::new(false);
        let started = Instant::now();
        std::thread::scope(|scope| {
            let workers: Vec<_> = (0..slots)
                .map(|_| {
                    scope.spawn(|| loop {
                        let index = next.fetch_add(1, Ordering::SeqCst);
                        let Some(text) = work.get(index) else { break };
                        if let Ok((_, pcm)) = tts.generate(text, 1) {
                            samples.fetch_add(pcm.len() as u64 / 2, Ordering::SeqCst);
                        }
                    })
                })
                .collect();
            scope.spawn(|| {
                while !done.load(Ordering::SeqCst) {
                    peak.fetch_max(memory::snapshot().own, Ordering::SeqCst);
                    std::thread::sleep(Duration::from_millis(100));
                }
            });
            for worker in workers {
                let _ = worker.join();
            }
            done.store(true, Ordering::SeqCst);
        });
        let audio = samples.load(Ordering::SeqCst) as f64 / f64::from(tts.sample_rate.max(1));
        let speed = audio / started.elapsed().as_secs_f64();
        println!("{slots:>5}  {speed:>5.2}x  {:>7}", peak.load(Ordering::SeqCst).saturating_sub(before) / MIB + before / MIB);
        drop(tts);
        memory::release_free_heap();
    }
}
