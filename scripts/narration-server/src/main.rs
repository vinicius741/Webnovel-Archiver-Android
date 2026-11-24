//! Narration server: generates chapter audio for the Webnovel Archiver app over LAN or Tailscale,
//! with the models the app runs on the phone: Kokoro v0.19 and v1.0 (sherpa-onnx 1.12.21) and
//! Phonon 31M (ptts 0.7.1).
//!
//! Each installed model is shared by several sentences at once, loads when a chapter first asks for
//! it, and unloads after idling; a memory governor shrinks, recycles or unloads models as the machine
//! runs low. Protocol 1, every request but /health needs `Authorization: Bearer <token>`:
//!   GET  /health                -> {"ok": true}
//!   GET  /v1/info[?model=NAME]  -> {protocol, model, models, sampleRate, workers, threadsPerWorker, name, server, version, memory}
//!   GET  /v1/status             -> memory, model and work counters, and per model under `models`
//!   POST /v1/synthesize         -> body {"text", "model", "speakerId" (Kokoro) or "voice" (Phonon)};
//!                                  200 audio/wav (mono 16-bit PCM). No model means kokoro-en-v0_19.
//! Errors are JSON {"error"}: 400 bad request, 401 bad token, 500 generation failed, and 503 with
//! `Retry-After` while memory is short. See docs/tts/remote-narration-servers.md.

mod bench_text;
mod engine;
mod fast;
mod http;
mod memory;
mod models;
mod openvino;
mod phonon;
mod sherpa;
mod worker;

use engine::{log, Engine, Settings};
use memory::{Policy, MIB};
use models::{Kind, VoiceRef};
use std::net::{TcpListener, UdpSocket};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

const USAGE: &str = "Usage: narration-server [options]
  --models-dir DIR       folder with one subfolder per model (default: ../models next to the binary)
  --model-dir DIR        kokoro-en-v0_19 folder, if it is elsewhere
  --token-file FILE      token file (default: ../token next to the binary)
  --host ADDR            listen address (default 0.0.0.0)
  --port N               port (default 8790)
  --name NAME            name shown in the app (default: host name)
  --threads N            inference threads (default: one per performance core)
  --max-slots N          most sentences generating at once (default: 4 on 8+ cores)
  --reserve-mb N         grow slots only while this much stays available (default: 12% of RAM, 1-3 GB)
  --critical-mb N        stop and unload below this much available (default: 4% of RAM, 384-768 MB);
                         below half of it, end the model's process without waiting
  --idle-unload-s N      unload the model after this many idle seconds (default 300; 0 = never)
  --no-fast              run Kokoro v1.0 at full precision even where its fast path applies
  --verbose              log every request
  --addresses            print this machine's addresses and exit
  --bench [ROUNDS]       measure speed and memory at each slot count, then exit
  --model NAME           the model --bench measures (default kokoro-en-v0_19)";

struct Args {
    models_dir: PathBuf,
    legacy_dir: Option<PathBuf>,
    bench_model: Kind,
    token_file: PathBuf,
    host: String,
    port: u16,
    name: String,
    threads: usize,
    max_slots: usize,
    reserve_mb: Option<u64>,
    critical_mb: Option<u64>,
    idle_unload: u64,
    no_fast: bool,
    /// Where OpenVINO's libraries are, for Kokoro v1.0's fast path.
    openvino_dir: PathBuf,
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
        models_dir: home.join("models"),
        legacy_dir: None,
        bench_model: http::LEGACY_MODEL,
        token_file: home.join("token"),
        host: "0.0.0.0".into(),
        port: 8790,
        name: host_name(),
        threads,
        max_slots: slots,
        reserve_mb: None,
        critical_mb: None,
        idle_unload: 300,
        no_fast: false,
        openvino_dir: home.join("lib").join("openvino"),
        verbose: false,
        addresses: false,
        bench: None,
    };
    let mut raw = std::env::args().skip(1).peekable();
    while let Some(flag) = raw.next() {
        let mut value = |name: &str| raw.next().ok_or(format!("{name} needs a value"));
        let number = |text: String, name: &str| text.parse::<u64>().map_err(|_| format!("{name} needs a number"));
        match flag.as_str() {
            "--models-dir" => args.models_dir = PathBuf::from(value(&flag)?),
            "--model-dir" => args.legacy_dir = Some(PathBuf::from(value(&flag)?)),
            "--model" => {
                let name = value(&flag)?;
                args.bench_model = Kind::from_name(&name).ok_or(format!("No model {name}"))?;
            }
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
            "--no-fast" => args.no_fast = true,
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
    let from_command = output_within(std::process::Command::new("hostname").arg("-s"), Duration::from_secs(3))
        .map(|name| name.trim().to_string())
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
    // On a Mac the command lives inside the app and is on no PATH. Started by launchd, the app's
    // command never returns (seen on macOS 26), hence the time limit.
    let tailscale =
        ["tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale", "/opt/homebrew/bin/tailscale", "/usr/local/bin/tailscale"];
    let listed =
        tailscale.iter().find_map(|command| output_within(std::process::Command::new(command).args(["ip", "-4"]), Duration::from_secs(3)));
    for line in listed.unwrap_or_default().lines().map(str::trim) {
        // Only addresses: a stopped Tailscale may print a message instead.
        if line.parse::<std::net::Ipv4Addr>().is_ok() {
            found.push(("Tailscale".to_string(), line.to_string()));
        }
    }
    found.retain(|(_, address)| !address.starts_with("127."));
    found.dedup();
    found
}

/// Runs a command and returns what it printed when it succeeded. A command still running after
/// [limit] is killed, so a helper that hangs cannot hang the server.
fn output_within(command: &mut std::process::Command, limit: Duration) -> Option<String> {
    use std::io::Read;
    use std::process::Stdio;
    let mut child = command.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn().ok()?;
    let deadline = Instant::now() + limit;
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                // A few lines at most, so they fit in the pipe and reading after the exit is safe.
                let mut text = String::new();
                child.stdout.take()?.read_to_string(&mut text).ok()?;
                return status.success().then_some(text);
            }
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(20)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return None;
            }
        }
    }
}

impl Args {
    fn model_dir(&self, kind: Kind) -> PathBuf {
        match (&self.legacy_dir, kind) {
            (Some(dir), Kind::KokoroV019) => dir.clone(),
            _ => self.models_dir.join(kind.name()),
        }
    }

    /// Kokoro v1.0's fast path on this machine, or why it is off.
    fn fast(&self) -> Result<fast::Options, String> {
        if self.no_fast {
            return Err("turned off with --no-fast".to_string());
        }
        fast::Options::detect(&self.openvino_dir, &self.model_dir(Kind::KokoroV1), self.max_slots)
    }
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
    policy.emergency = policy.critical / 2;
    policy
}

fn main() {
    let raw: Vec<String> = std::env::args().skip(1).collect();
    if raw.first().map(String::as_str) == Some("--worker") {
        // A model's own process, started by the server (see worker.rs).
        worker::run(&raw);
    }
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
        // Phonon's runtime computes on its own pool; one thread per performance core, like Kokoro.
        // The server sets it in each worker instead, so its own process never starts that pool.
        xn::set_num_threads(args.threads);
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
    let policy = policy(&args);
    let installed: Vec<Kind> = Kind::ALL.into_iter().filter(|kind| kind.installed(&args.model_dir(*kind))).collect();
    if installed.is_empty() {
        fail(&format!("No model found in {}. Run setup.sh first.", args.models_dir.display()));
    }
    let fast = args.fast();
    let engines: Vec<Engine> = installed
        .iter()
        .map(|kind| {
            Engine::new(Settings {
                kind: *kind,
                model_dir: args.model_dir(*kind),
                threads: args.threads,
                fast: fast.as_ref().ok().filter(|_| *kind == Kind::KokoroV1).cloned(),
                policy,
                idle_unload: if args.idle_unload == 0 { Duration::MAX } else { Duration::from_secs(args.idle_unload) },
                verbose: args.verbose,
            })
        })
        .collect();
    let phonon_voices = Kind::Phonon.named_voices(&args.model_dir(Kind::Phonon));
    let listener = TcpListener::bind((args.host.as_str(), args.port))
        .unwrap_or_else(|error| fail(&format!("Cannot listen on port {}: {error}", args.port)));
    let shared = Arc::new(http::Shared {
        engines,
        phonon_voices,
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
            let snapshot = memory::snapshot();
            for engine in &governed.engines {
                engine.govern_with(snapshot);
            }
            std::thread::sleep(Duration::from_secs(1));
        })
        .unwrap_or_else(|_| fail("Could not start the memory governor"));
    log(&format!(
        "Narration server '{}' ready on port {} with {} (sherpa-onnx {}, ptts 0.7.1; up to {} sentences at once x {} threads; reserve {} MB, stop below {} MB; each model loads in its own process on first use)",
        args.name,
        args.port,
        installed.iter().map(|kind| kind.name()).collect::<Vec<_>>().join(", "),
        sherpa::version(),
        policy.max_slots,
        args.threads,
        policy.reserve / MIB,
        policy.critical / MIB
    ));
    if installed.contains(&Kind::KokoroV1) {
        match &fast {
            Ok(options) => log(&format!("  {}: fast path on (OpenVINO, {} generator)", Kind::KokoroV1.name(), options.precision)),
            Err(why) => log(&format!("  {}: fast path off ({why})", Kind::KokoroV1.name())),
        }
    }
    // Looked up beside the server, never before it: finding them runs other programs.
    let port = args.port;
    let _ = std::thread::Builder::new().name("addresses".into()).spawn(move || {
        for (kind, address) in addresses() {
            log(&format!("  App address ({kind}): {address}:{port}"));
        }
    });
    http::serve(listener, shared);
}

fn fail(message: &str) -> ! {
    eprintln!("{message}");
    std::process::exit(1);
}

/// Speed and peak memory at 1..=max slots on one shared model, to pick --max-slots and --threads.
fn bench(args: &Args, rounds: usize) {
    use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
    let kind = args.bench_model;
    let dir = args.model_dir(kind);
    let voice = match kind {
        Kind::KokoroV019 => VoiceRef::Speaker(1),
        Kind::KokoroV1 => VoiceRef::Speaker(3),
        Kind::Phonon => match kind.named_voices(&dir).into_iter().next() {
            Some(name) => VoiceRef::Named(name),
            None => fail(&format!("No voices in {}", dir.display())),
        },
    };
    let fast = args.fast().ok().filter(|_| kind == Kind::KokoroV1);
    println!(
        "{} ({:?}), sherpa-onnx {}, {} threads, {} round(s) of {} sentences{}",
        kind.name(),
        voice,
        sherpa::version(),
        args.threads,
        rounds,
        bench_text::SENTENCES.len(),
        fast.as_ref().map(|options| format!(", fast path ({})", options.precision)).unwrap_or_default()
    );
    let work: Vec<&str> = (0..rounds.max(1)).flat_map(|_| bench_text::SENTENCES.iter().copied()).collect();
    println!("slots  speed   peak MB");
    for slots in 1..=args.max_slots {
        let before = memory::snapshot().own;
        let tts = match kind.load(&dir, args.threads, fast.as_ref()) {
            Ok(tts) => tts,
            Err(error) => fail(&error),
        };
        let _ = tts.generate("Warm up.", &voice);
        let next = AtomicUsize::new(0);
        let samples = AtomicU64::new(0);
        let peak = AtomicU64::new(0);
        let sample_rate = AtomicU64::new(24_000);
        let done = AtomicBool::new(false);
        let started = Instant::now();
        std::thread::scope(|scope| {
            let workers: Vec<_> = (0..slots)
                .map(|_| {
                    scope.spawn(|| loop {
                        let index = next.fetch_add(1, Ordering::SeqCst);
                        let Some(text) = work.get(index) else { break };
                        if let Ok((rate, pcm)) = tts.generate(text, &voice) {
                            samples.fetch_add(pcm.len() as u64 / 2, Ordering::SeqCst);
                            sample_rate.store(u64::from(rate), Ordering::SeqCst);
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
        let audio = samples.load(Ordering::SeqCst) as f64 / sample_rate.load(Ordering::SeqCst).max(1) as f64;
        let speed = audio / started.elapsed().as_secs_f64();
        println!("{slots:>5}  {speed:>5.2}x  {:>7}", peak.load(Ordering::SeqCst).saturating_sub(before) / MIB + before / MIB);
        drop(tts);
        memory::release_free_heap();
    }
}
