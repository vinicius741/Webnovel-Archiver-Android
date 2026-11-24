//! Each loaded model lives in its own child process, a "worker": the same binary started with
//! `--worker`. Unloading the model ends that process, so everything it held goes back to the system
//! at once: the native runtimes' heap and globals (which a long-running process never returned,
//! 320 MB after a few days on Bazzite), and any of its pages sitting in swap. The parent, which serves
//! HTTP and runs the governor, never loads a model and stays at a few MB.
//!
//! Parent and worker speak in frames over the worker's stdin and stdout:
//! `u64 id, u8 kind, u32 length, payload`, little-endian, at most [MAX_FRAME] bytes of payload.
//! - parent → worker: [GENERATE] with JSON `{"text", "speaker" | "voice"}`.
//! - worker → parent: [READY] once (JSON `{"protocol", "fast"}`) or [ERROR] if the model does not
//!   load; then per request [AUDIO] (`u32 sample rate` + 16-bit PCM) or [ERROR] (a message).
//!
//! The worker ends itself when its stdin closes, which happens when the parent drops it, exits or is
//! killed. A worker killed by the kernel (out of memory) reads as [Lost] to whoever waits on it.

use crate::models::{Kind, Synth, VoiceRef};
use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{self, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// Bumped when the frames change; a worker from another build refuses to talk.
pub const PROTOCOL: u32 = 1;
const GENERATE: u8 = 1;
const READY: u8 = 2;
const AUDIO: u8 = 3;
const ERROR: u8 = 4;
/// Largest payload either side accepts; a sentence of 5000 characters is ~10 MB of PCM.
const MAX_FRAME: usize = 64 * 1024 * 1024;
/// A load that has not finished by then is stuck (thrashing, most likely) and is killed.
const LOAD_TIMEOUT: Duration = Duration::from_secs(90);
/// A sentence that takes longer means the worker hung; it is killed and the sentence retried later.
const SENTENCE_TIMEOUT: Duration = Duration::from_secs(300);

/// Why a worker could not do what was asked.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WorkerError {
    /// The worker is gone (killed for memory, crashed, could not start): waiting may help.
    Lost(String),
    /// The worker answered with an error: the model files or the request are at fault.
    Failed(String),
}

impl std::fmt::Display for WorkerError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            WorkerError::Lost(message) | WorkerError::Failed(message) => f.write_str(message),
        }
    }
}

/// What a worker loads.
#[derive(Clone, Debug)]
pub struct Spec {
    pub kind: Kind,
    pub model_dir: PathBuf,
    pub threads: usize,
    /// Kokoro v1.0's fast path, when this load takes it.
    pub fast: Option<crate::fast::Options>,
}

type Reply = Result<(u32, Vec<u8>), WorkerError>;

/// Requests waiting for their reply; once the worker is gone, nothing more is accepted.
#[derive(Default)]
struct Pending {
    waiting: HashMap<u64, Sender<Reply>>,
    gone: Option<String>,
}

/// A running worker, seen from the parent.
pub struct Worker {
    pid: u32,
    child: Mutex<Option<Child>>,
    stdin: Mutex<Option<ChildStdin>>,
    pending: Arc<Mutex<Pending>>,
    next_id: AtomicU64,
    /// Whether Kokoro v1.0's fast path really loaded (it falls back to the normal path by itself).
    pub fast: bool,
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

impl Worker {
    /// Starts a worker and waits until its model has loaded.
    pub fn start(spec: &Spec) -> Result<Worker, WorkerError> {
        let mut command = Command::new(own_executable());
        command
            .arg("--worker")
            .args(["--model", spec.kind.name()])
            .arg("--model-dir")
            .arg(&spec.model_dir)
            .args(["--threads", &spec.threads.to_string()])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit());
        if let Some(fast) = &spec.fast {
            command.arg("--fast-library").arg(&fast.library).args([
                "--fast-precision",
                fast.precision,
                "--fast-streams",
                &fast.streams.to_string(),
            ]);
        }
        if cfg!(target_os = "macos") {
            // Set here so the worker starts with it and does not restart itself (see tune_allocator).
            command.env("MallocLargeCache", "0");
        }
        let mut child = command.spawn().map_err(|error| WorkerError::Lost(format!("could not start a worker: {error}")))?;
        let pid = child.id();
        let (Some(stdin), Some(stdout)) = (child.stdin.take(), child.stdout.take()) else {
            kill(&mut child);
            return Err(WorkerError::Lost("the worker has no pipes".to_string()));
        };
        let pending = Arc::new(Mutex::new(Pending::default()));
        let (ready_tx, ready_rx) = mpsc::channel::<Result<bool, WorkerError>>();
        let routed = pending.clone();
        let spawned = std::thread::Builder::new().name("worker-reader".into()).spawn(move || read_replies(stdout, routed, ready_tx));
        if spawned.is_err() {
            kill(&mut child);
            return Err(WorkerError::Lost("could not start the worker's reader".to_string()));
        }
        let fast = match ready_rx.recv_timeout(LOAD_TIMEOUT) {
            Ok(Ok(fast)) => fast,
            Ok(Err(error)) => {
                kill(&mut child);
                return Err(error);
            }
            Err(_) => {
                kill(&mut child);
                return Err(WorkerError::Lost(format!("the model did not load within {} s", LOAD_TIMEOUT.as_secs())));
            }
        };
        Ok(Worker { pid, child: Mutex::new(Some(child)), stdin: Mutex::new(Some(stdin)), pending, next_id: AtomicU64::new(1), fast })
    }

    /// Speaks [text] in the worker. Several calls may run at once.
    pub fn generate(&self, text: &str, voice: &VoiceRef) -> Reply {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = mpsc::channel();
        {
            let mut pending = lock(&self.pending);
            if let Some(why) = &pending.gone {
                return Err(WorkerError::Lost(why.clone()));
            }
            pending.waiting.insert(id, tx);
        }
        let body = match voice {
            VoiceRef::Speaker(speaker) => serde_json::json!({"text": text, "speaker": speaker}),
            VoiceRef::Named(name) => serde_json::json!({"text": text, "voice": name}),
        };
        let written = match lock(&self.stdin).as_mut() {
            Some(stdin) => write_frame(stdin, id, GENERATE, &[&serde_json::to_vec(&body).unwrap_or_default()]),
            None => Err(io::ErrorKind::BrokenPipe.into()),
        };
        if let Err(error) = written {
            lock(&self.pending).waiting.remove(&id);
            return Err(WorkerError::Lost(format!("the worker stopped listening ({error})")));
        }
        match rx.recv_timeout(SENTENCE_TIMEOUT) {
            Ok(reply) => reply,
            Err(_) => {
                // Hung: kill it, and the reader answers everyone else waiting with Lost.
                self.kill();
                Err(WorkerError::Lost(format!("the worker took over {} s on one sentence", SENTENCE_TIMEOUT.as_secs())))
            }
        }
    }

    /// Whether the worker is still running.
    pub fn alive(&self) -> bool {
        lock(&self.pending).gone.is_none()
    }

    /// Ends the worker now; sentences in it fail with [WorkerError::Lost].
    pub fn kill(&self) {
        if let Some(child) = lock(&self.child).as_mut() {
            let _ = child.kill();
        }
    }

    /// The worker's memory in RAM and in swap, in bytes; None once it has exited.
    pub fn memory(&self) -> Option<(u64, u64)> {
        crate::memory::process_memory(self.pid)
    }

    pub fn pid(&self) -> u32 {
        self.pid
    }

    /// A stand-in with no process behind it, for the engine's tests.
    #[cfg(test)]
    pub fn fake() -> Worker {
        Worker {
            pid: 0,
            child: Mutex::new(None),
            stdin: Mutex::new(None),
            pending: Arc::new(Mutex::new(Pending::default())),
            next_id: AtomicU64::new(1),
            fast: false,
        }
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        // It holds nothing worth saving: end it at once, and reap it beside the caller (the governor,
        // say), since a process with gigabytes in swap can take a while to exit.
        let Some(mut child) = lock(&self.child).take() else { return };
        let _ = child.kill();
        let shared = Arc::new(Mutex::new(Some(child)));
        let reaped = shared.clone();
        let spawned = std::thread::Builder::new().name("worker-reaper".into()).spawn(move || {
            if let Some(mut child) = lock(&reaped).take() {
                let _ = child.wait();
            }
        });
        if spawned.is_err() {
            // No thread to spare: wait here rather than leave a zombie.
            if let Some(mut child) = lock(&shared).take() {
                let _ = child.wait();
            }
        }
    }
}

fn kill(child: &mut Child) {
    let _ = child.kill();
    let _ = child.wait();
}

/// This program, as it is running: on Linux the file this process was started from, even if
/// setup.sh has since installed a newer binary in its place.
fn own_executable() -> PathBuf {
    if cfg!(target_os = "linux") && Path::new("/proc/self/exe").exists() {
        return PathBuf::from("/proc/self/exe");
    }
    std::env::current_exe().unwrap_or_else(|_| PathBuf::from("narration-server"))
}

/// The parent's reader: the first frame says whether the model loaded, the rest answer requests.
/// When the worker's output ends, it is gone: everyone still waiting is told so.
fn read_replies(mut stdout: impl Read, pending: Arc<Mutex<Pending>>, ready: Sender<Result<bool, WorkerError>>) {
    let mut ready = Some(ready);
    let why = loop {
        let (id, kind, payload) = match read_frame(&mut stdout) {
            Ok(Some(frame)) => frame,
            Ok(None) => break "the worker exited".to_string(),
            Err(error) => break format!("the worker's output broke ({error})"),
        };
        if let Some(ready) = ready.take() {
            let result = match kind {
                READY => parse_ready(&payload),
                ERROR => Err(WorkerError::Failed(String::from_utf8_lossy(&payload).into_owned())),
                _ => Err(WorkerError::Failed("the worker spoke out of turn".to_string())),
            };
            let _ = ready.send(result);
            continue;
        }
        let reply = match kind {
            AUDIO if payload.len() >= 4 => {
                let rate = u32::from_le_bytes(payload[..4].try_into().unwrap_or_default());
                Ok((rate, payload[4..].to_vec()))
            }
            ERROR => Err(WorkerError::Failed(String::from_utf8_lossy(&payload).into_owned())),
            _ => Err(WorkerError::Failed("the worker sent a malformed reply".to_string())),
        };
        if let Some(tx) = lock(&pending).waiting.remove(&id) {
            let _ = tx.send(reply);
        }
    };
    if let Some(ready) = ready.take() {
        let _ = ready.send(Err(WorkerError::Lost(format!("{why} while loading the model"))));
    }
    let mut pending = lock(&pending);
    pending.gone = Some(why.clone());
    for (_, tx) in pending.waiting.drain() {
        let _ = tx.send(Err(WorkerError::Lost(why.clone())));
    }
}

fn parse_ready(payload: &[u8]) -> Result<bool, WorkerError> {
    let value: serde_json::Value =
        serde_json::from_slice(payload).map_err(|_| WorkerError::Failed("the worker's greeting is not JSON".to_string()))?;
    let protocol = value["protocol"].as_u64().unwrap_or(0);
    if protocol != u64::from(PROTOCOL) {
        return Err(WorkerError::Failed(format!(
            "the worker speaks protocol {protocol}, the server {PROTOCOL}; restart the server after an upgrade"
        )));
    }
    Ok(value["fast"].as_bool().unwrap_or(false))
}

fn write_frame(out: &mut impl Write, id: u64, kind: u8, parts: &[&[u8]]) -> io::Result<()> {
    let length: usize = parts.iter().map(|part| part.len()).sum();
    if length > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "frame too large"));
    }
    let mut header = [0u8; 13];
    header[..8].copy_from_slice(&id.to_le_bytes());
    header[8] = kind;
    header[9..].copy_from_slice(&(length as u32).to_le_bytes());
    out.write_all(&header)?;
    for part in parts {
        out.write_all(part)?;
    }
    out.flush()
}

/// One frame, or None at a clean end of stream (between frames).
fn read_frame(input: &mut impl Read) -> io::Result<Option<(u64, u8, Vec<u8>)>> {
    let mut header = [0u8; 13];
    let mut filled = 0;
    while filled < header.len() {
        match input.read(&mut header[filled..]) {
            Ok(0) if filled == 0 => return Ok(None),
            Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
            Ok(read) => filled += read,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(error) => return Err(error),
        }
    }
    let id = u64::from_le_bytes(header[..8].try_into().unwrap_or_default());
    let length = u32::from_le_bytes(header[9..].try_into().unwrap_or_default()) as usize;
    if length > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidData, format!("a frame of {length} bytes")));
    }
    let mut payload = vec![0u8; length];
    input.read_exact(&mut payload)?;
    Ok(Some((id, header[8], payload)))
}

// ---------------------------------------------------------------------------------------------
// The worker process

/// Runs this process as a worker (`--worker ...`); never returns.
pub fn run(args: &[String]) -> ! {
    // First, as it may restart the process (macOS, when the parent did not set the allocator up).
    crate::memory::tune_allocator();
    // Frames go to the original stdout; anything a native library prints goes to stderr instead.
    let mut out = take_stdout();
    raise_oom_score();
    let parsed = parse_worker_args(args);
    let (kind, dir, threads, fast) = match parsed {
        Ok(parsed) => parsed,
        Err(message) => {
            let _ = write_frame(&mut out, 0, ERROR, &[message.as_bytes()]);
            exit_now(2);
        }
    };
    xn::set_num_threads(threads);
    let (tts, fast_loaded) = match kind.load_reporting(&dir, threads, fast.as_ref()) {
        Ok(loaded) => loaded,
        Err(message) => {
            let _ = write_frame(&mut out, 0, ERROR, &[message.as_bytes()]);
            exit_now(1);
        }
    };
    let hello = serde_json::json!({"protocol": PROTOCOL, "fast": fast_loaded});
    if write_frame(&mut out, 0, READY, &[&serde_json::to_vec(&hello).unwrap_or_default()]).is_err() {
        exit_now(0);
    }
    let out = Arc::new(Mutex::new(out));
    let running = Arc::new(AtomicUsize::new(0));
    let stdin = io::stdin();
    let mut input = stdin.lock();
    loop {
        let (id, kind, payload) = match read_frame(&mut input) {
            Ok(Some(frame)) => frame,
            // The parent is gone or dropped this worker: leave at once. Running native destructors
            // would only fault swapped-out pages back in to free them.
            _ => exit_now(0),
        };
        if kind != GENERATE {
            continue;
        }
        let (tts, sentence_out, sentence_running) = (tts.clone(), out.clone(), running.clone());
        running.fetch_add(1, Ordering::SeqCst);
        let spawned = std::thread::Builder::new().name("sentence".into()).stack_size(8 * 1024 * 1024).spawn(move || {
            // A panic in the model's code answers its sentence as an error instead of leaving the
            // parent to wait the full sentence timeout.
            let reply = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| speak(&*tts, &payload)))
                .unwrap_or_else(|_| Err("the model panicked on this sentence".to_string()));
            // The last sentence out hands freed heap back before replying, so the next one cannot
            // start in between (the fast path frees OpenVINO's buffers the same way).
            if sentence_running.fetch_sub(1, Ordering::SeqCst) == 1 {
                crate::memory::release_free_heap();
            }
            let written = match &reply {
                Ok((rate, pcm)) => write_frame(&mut *lock(&sentence_out), id, AUDIO, &[&rate.to_le_bytes(), pcm]),
                Err(message) => write_frame(&mut *lock(&sentence_out), id, ERROR, &[message.as_bytes()]),
            };
            if written.is_err() {
                exit_now(0);
            }
        });
        if spawned.is_err() {
            let _ = write_frame(&mut *lock(&out), id, ERROR, &[b"the worker could not start a thread"]);
            running.fetch_sub(1, Ordering::SeqCst);
        }
    }
}

fn speak(tts: &dyn Synth, payload: &[u8]) -> Result<(u32, Vec<u8>), String> {
    let request: serde_json::Value = serde_json::from_slice(payload).map_err(|_| "a malformed request".to_string())?;
    let text = request["text"].as_str().ok_or("a request without text")?;
    let voice = match (request["speaker"].as_u64(), request["voice"].as_str()) {
        (Some(speaker), _) => VoiceRef::Speaker(speaker as u32),
        (None, Some(name)) => VoiceRef::Named(name.to_string()),
        _ => return Err("a request without a voice".to_string()),
    };
    tts.generate(text, &voice)
}

type WorkerArgs = (Kind, PathBuf, usize, Option<crate::fast::Options>);

fn parse_worker_args(args: &[String]) -> Result<WorkerArgs, String> {
    let mut kind = None;
    let mut dir = None;
    let mut threads = 1usize;
    let (mut library, mut precision, mut streams) = (None, None, 1usize);
    let mut raw = args.iter();
    while let Some(flag) = raw.next() {
        let mut value = || raw.next().cloned().ok_or(format!("{flag} needs a value"));
        match flag.as_str() {
            "--worker" => {}
            "--model" => {
                let name = value()?;
                kind = Some(Kind::from_name(&name).ok_or(format!("No model {name}"))?);
            }
            "--model-dir" => dir = Some(PathBuf::from(value()?)),
            "--threads" => threads = value()?.parse().map_err(|_| "--threads needs a number")?,
            "--fast-library" => library = Some(PathBuf::from(value()?)),
            "--fast-precision" => {
                precision = Some(match value()?.as_str() {
                    "bf16" => "bf16",
                    "f16" => "f16",
                    other => return Err(format!("Unknown precision {other}")),
                })
            }
            "--fast-streams" => streams = value()?.parse().map_err(|_| "--fast-streams needs a number")?,
            other => return Err(format!("Unknown worker option {other}")),
        }
    }
    let fast = match (library, precision) {
        (Some(library), Some(precision)) => Some(crate::fast::Options { library, precision, streams: streams.max(1) }),
        _ => None,
    };
    Ok((kind.ok_or("--model is required")?, dir.ok_or("--model-dir is required")?, threads.max(1), fast))
}

/// Moves the protocol off fd 1 (to a new descriptor that children would not inherit) and points
/// fd 1 at stderr.
fn take_stdout() -> std::fs::File {
    use std::os::fd::FromRawFd;
    extern "C" {
        fn fcntl(fd: i32, cmd: i32, ...) -> i32;
        fn dup2(old: i32, new: i32) -> i32;
    }
    #[cfg(target_os = "linux")]
    const F_DUPFD_CLOEXEC: i32 = 1030;
    #[cfg(not(target_os = "linux"))]
    const F_DUPFD_CLOEXEC: i32 = 67;
    // Rust's own stdout buffer is empty: nothing has been printed yet.
    // SAFETY: plain descriptor calls on descriptors this process owns.
    unsafe {
        let moved = fcntl(1, F_DUPFD_CLOEXEC, 3);
        if moved < 0 {
            exit_now(3);
        }
        dup2(2, 1);
        std::fs::File::from_raw_fd(moved)
    }
}

/// Makes the kernel's out-of-memory killer pick this worker before anything else on the machine.
fn raise_oom_score() {
    #[cfg(target_os = "linux")]
    let _ = std::fs::write("/proc/self/oom_score_adj", "1000");
}

/// Ends the process without running destructors or exit handlers.
fn exit_now(code: i32) -> ! {
    extern "C" {
        fn _exit(code: i32) -> !;
    }
    // SAFETY: plain libc call.
    unsafe { _exit(code) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_round_trip() {
        let mut buffer = Vec::new();
        write_frame(&mut buffer, 42, AUDIO, &[&24_000u32.to_le_bytes(), &[1, 2, 3, 4]]).unwrap();
        write_frame(&mut buffer, 43, ERROR, &[b"nope"]).unwrap();
        let mut reader = &buffer[..];
        let (id, kind, payload) = read_frame(&mut reader).unwrap().unwrap();
        assert_eq!((id, kind), (42, AUDIO));
        assert_eq!(&payload[4..], &[1, 2, 3, 4]);
        let (id, kind, payload) = read_frame(&mut reader).unwrap().unwrap();
        assert_eq!((id, kind, payload.as_slice()), (43, ERROR, &b"nope"[..]));
        assert!(read_frame(&mut reader).unwrap().is_none());
    }

    #[test]
    fn frames_are_capped_and_truncation_is_an_error() {
        let mut header = Vec::new();
        header.extend_from_slice(&1u64.to_le_bytes());
        header.push(AUDIO);
        header.extend_from_slice(&((MAX_FRAME + 1) as u32).to_le_bytes());
        assert!(read_frame(&mut &header[..]).is_err());
        let mut buffer = Vec::new();
        write_frame(&mut buffer, 1, AUDIO, &[&[0u8; 100]]).unwrap();
        buffer.truncate(50);
        assert!(read_frame(&mut &buffer[..]).is_err());
        assert!(read_frame(&mut &buffer[..5]).is_err());
    }

    #[test]
    fn a_worker_that_goes_away_answers_everyone_with_lost() {
        let pending = Arc::new(Mutex::new(Pending::default()));
        let (tx, rx) = mpsc::channel();
        lock(&pending).waiting.insert(7, tx);
        let (ready_tx, ready_rx) = mpsc::channel();
        let mut stream = Vec::new();
        write_frame(&mut stream, 0, READY, &[br#"{"protocol":1,"fast":true}"#]).unwrap();
        read_replies(&stream[..], pending.clone(), ready_tx);
        assert_eq!(ready_rx.recv().unwrap(), Ok(true));
        assert!(matches!(rx.recv().unwrap(), Err(WorkerError::Lost(_))));
        assert!(lock(&pending).gone.is_some());
    }

    #[test]
    fn a_worker_that_dies_while_loading_is_lost_and_one_that_reports_is_failed() {
        let (ready_tx, ready_rx) = mpsc::channel();
        read_replies(&[][..], Arc::new(Mutex::new(Pending::default())), ready_tx);
        assert!(matches!(ready_rx.recv().unwrap(), Err(WorkerError::Lost(_))));
        let (ready_tx, ready_rx) = mpsc::channel();
        let mut stream = Vec::new();
        write_frame(&mut stream, 0, ERROR, &[b"model.onnx is missing"]).unwrap();
        read_replies(&stream[..], Arc::new(Mutex::new(Pending::default())), ready_tx);
        assert_eq!(ready_rx.recv().unwrap(), Err(WorkerError::Failed("model.onnx is missing".to_string())));
        let (ready_tx, ready_rx) = mpsc::channel();
        let mut stream = Vec::new();
        write_frame(&mut stream, 0, READY, &[br#"{"protocol":99}"#]).unwrap();
        read_replies(&stream[..], Arc::new(Mutex::new(Pending::default())), ready_tx);
        assert!(matches!(ready_rx.recv().unwrap(), Err(WorkerError::Failed(_))));
    }

    #[test]
    fn replies_reach_the_request_that_asked() {
        let pending = Arc::new(Mutex::new(Pending::default()));
        let (tx_a, rx_a) = mpsc::channel();
        let (tx_b, rx_b) = mpsc::channel();
        lock(&pending).waiting.insert(1, tx_a);
        lock(&pending).waiting.insert(2, tx_b);
        let mut stream = Vec::new();
        write_frame(&mut stream, 0, READY, &[br#"{"protocol":1}"#]).unwrap();
        write_frame(&mut stream, 2, ERROR, &[b"bad text"]).unwrap();
        write_frame(&mut stream, 1, AUDIO, &[&24_000u32.to_le_bytes(), &[9, 9]]).unwrap();
        let (ready_tx, _ready_rx) = mpsc::channel();
        read_replies(&stream[..], pending, ready_tx);
        assert_eq!(rx_a.recv().unwrap(), Ok((24_000, vec![9, 9])));
        assert_eq!(rx_b.recv().unwrap(), Err(WorkerError::Failed("bad text".to_string())));
    }

    #[test]
    fn worker_options_parse() {
        let args: Vec<String> =
            "--worker --model kokoro-multi-lang-v1_0 --model-dir /m --threads 8 --fast-library /ov --fast-precision bf16 --fast-streams 2"
                .split(' ')
                .map(str::to_string)
                .collect();
        let (kind, dir, threads, fast) = parse_worker_args(&args).unwrap();
        assert_eq!((kind, dir, threads), (Kind::KokoroV1, PathBuf::from("/m"), 8));
        let fast = fast.unwrap();
        assert_eq!((fast.precision, fast.streams), ("bf16", 2));
        assert!(parse_worker_args(&["--model".to_string(), "x".to_string()]).is_err());
    }
}
