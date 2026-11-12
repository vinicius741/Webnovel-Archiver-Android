//! A small HTTP/1.1 server: one thread per connection (the app holds at most ~10), keep-alive, and
//! bounded reads so a stray client cannot make it buffer much.

use crate::engine::{log, Busy, Engine};

use crate::memory::Level;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const PROTOCOL: u32 = 1;
pub const MODEL_NAME: &str = "kokoro-en-v0_19";
const MAX_HEADER_BYTES: usize = 16 * 1024;
const MAX_BODY_BYTES: usize = 64 * 1024;
const MAX_TEXT_CHARS: usize = 5000;
/// kokoro-en-v0_19's voices.
pub const SPEAKERS: i64 = 11;
const MAX_CONNECTIONS: usize = 32;
/// An idle keep-alive connection is closed after this long.
const IDLE_TIMEOUT: Duration = Duration::from_secs(75);
/// How long a sentence may wait for a slot while memory allows fewer slots than requests.
const SLOT_PATIENCE: Duration = Duration::from_secs(60);

pub struct Shared {
    pub engine: Engine,
    pub token: String,
    pub name: String,
    pub threads: usize,
    pub sample_rate: u32,
    pub keep_awake: KeepAwake,
}

pub fn serve(listener: TcpListener, shared: Arc<Shared>) {
    let open = Arc::new(AtomicUsize::new(0));
    for stream in listener.incoming() {
        let stream = match stream {
            Ok(stream) => stream,
            Err(error) => {
                // Out of file descriptors and the like: back off instead of spinning.
                log(&format!("Accept failed: {error}"));
                std::thread::sleep(Duration::from_millis(100));
                continue;
            }
        };
        let guard = OpenConnection::new(&open);
        if guard.count > MAX_CONNECTIONS {
            let mut stream = stream;
            let _ = respond_json(&mut stream, 503, &serde_json::json!({"error": "Too many connections"}), false, &[]);
            continue;
        }
        let shared = shared.clone();
        // The guard moves into the thread and gives the slot back however it ends (even a panic);
        // if the thread cannot start, the closure and its guard are dropped here.
        let spawned = std::thread::Builder::new().name("conn".into()).stack_size(8 * 1024 * 1024).spawn(move || {
            let _guard = guard;
            let _ = connection(stream, &shared);
        });
        if spawned.is_err() {
            log("Could not start a connection thread");
        }
    }
}

/// Counts a connection while it lives.
struct OpenConnection {
    open: Arc<AtomicUsize>,
    count: usize,
}

impl OpenConnection {
    fn new(open: &Arc<AtomicUsize>) -> OpenConnection {
        let count = open.fetch_add(1, Ordering::SeqCst) + 1;
        OpenConnection { open: open.clone(), count }
    }
}

impl Drop for OpenConnection {
    fn drop(&mut self) {
        self.open.fetch_sub(1, Ordering::SeqCst);
    }
}

struct Request {
    method: String,
    path: String,
    authorization: String,
    keep_alive: bool,
    body: Vec<u8>,
}

fn connection(stream: TcpStream, shared: &Arc<Shared>) -> io::Result<()> {
    stream.set_nodelay(true)?;
    stream.set_read_timeout(Some(IDLE_TIMEOUT))?;
    stream.set_write_timeout(Some(Duration::from_secs(60)))?;
    let mut writer = stream.try_clone()?;
    let mut reader = BufReader::with_capacity(8 * 1024, stream);
    loop {
        let request = match read_request(&mut reader) {
            Ok(Some(request)) => request,
            Ok(None) => return Ok(()),
            Err(error) if error.kind() == io::ErrorKind::InvalidData => {
                return respond_json(&mut writer, 400, &serde_json::json!({"error": error.to_string()}), false, &[]);
            }
            Err(error) => return Err(error),
        };
        let keep_alive = request.keep_alive;
        handle(request, shared, &mut writer)?;
        if !keep_alive {
            return Ok(());
        }
    }
}

/// Reads one request; None when the client closed the connection between requests.
fn read_request(reader: &mut BufReader<TcpStream>) -> io::Result<Option<Request>> {
    let invalid = |message: &str| io::Error::new(io::ErrorKind::InvalidData, message.to_string());
    let mut line = String::new();
    let mut header_bytes = 0;
    let first = read_line(reader, &mut line, &mut header_bytes)?;
    if first == 0 {
        return Ok(None);
    }
    let mut parts = line.split_whitespace();
    let method = parts.next().unwrap_or("").to_string();
    let path = parts.next().unwrap_or("").to_string();
    let version = parts.next().unwrap_or("").to_string();
    if method.is_empty() || !path.starts_with('/') || !version.starts_with("HTTP/1.") {
        return Err(invalid("Malformed request line"));
    }
    let mut length = 0usize;
    let mut authorization = String::new();
    let mut keep_alive = version == "HTTP/1.1";
    loop {
        line.clear();
        if read_line(reader, &mut line, &mut header_bytes)? == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Connection closed mid-request"));
        }
        let header = line.trim_end_matches(['\r', '\n']);
        if header.is_empty() {
            break;
        }
        let Some((name, value)) = header.split_once(':') else { return Err(invalid("Malformed header")) };
        let value = value.trim();
        match name.trim().to_ascii_lowercase().as_str() {
            "content-length" => length = value.parse().map_err(|_| invalid("Bad Content-Length"))?,
            "authorization" => authorization = value.to_string(),
            "connection" => {
                let value = value.to_ascii_lowercase();
                if value.contains("close") {
                    keep_alive = false;
                } else if value.contains("keep-alive") {
                    keep_alive = true;
                }
            }
            "transfer-encoding" => return Err(invalid("Chunked bodies are not supported")),
            _ => {}
        }
    }
    if length > MAX_BODY_BYTES {
        return Err(invalid("Body too large"));
    }
    let mut body = vec![0u8; length];
    reader.read_exact(&mut body)?;
    Ok(Some(Request { method, path, authorization, keep_alive, body }))
}

fn read_line(reader: &mut BufReader<TcpStream>, line: &mut String, total: &mut usize) -> io::Result<usize> {
    let read = reader.by_ref().take((MAX_HEADER_BYTES - *total) as u64 + 1).read_line(line)?;
    *total += read;
    if *total > MAX_HEADER_BYTES {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Headers too large"));
    }
    Ok(read)
}

/// Case-insensitive, constant time: tokens are typed on a phone keyboard that may capitalize them.
fn authorized(header: &str, token: &str) -> bool {
    let given = header.to_ascii_lowercase().into_bytes();
    let expected = format!("bearer {}", token.to_ascii_lowercase()).into_bytes();
    let mut difference = given.len() ^ expected.len();
    for (index, byte) in expected.iter().enumerate() {
        difference |= (given.get(index).copied().unwrap_or(0) ^ byte) as usize;
    }
    difference == 0
}

fn handle(request: Request, shared: &Arc<Shared>, out: &mut TcpStream) -> io::Result<()> {
    let keep = request.keep_alive;
    if shared.engine.settings().verbose {
        log(&format!("{} {} ({} bytes)", request.method, request.path, request.body.len()));
    }
    let path = request.path.split('?').next().unwrap_or("");
    if request.method == "GET" && path == "/health" {
        return respond_json(out, 200, &serde_json::json!({"ok": true}), keep, &[]);
    }
    if !authorized(&request.authorization, &shared.token) {
        return respond_json(out, 401, &serde_json::json!({"error": "Wrong or missing token"}), keep, &[]);
    }
    match (request.method.as_str(), path) {
        ("GET", "/v1/info") => {
            // The app asks for this before its first sentence: load the model meanwhile.
            let preload = shared.clone();
            let _ = std::thread::Builder::new().name("preload".into()).spawn(move || preload.engine.preload());
            let status = shared.engine.status();
            let info = serde_json::json!({
                "protocol": PROTOCOL,
                "model": MODEL_NAME,
                "sampleRate": shared.sample_rate,
                "workers": shared.engine.settings().policy.max_slots,
                "threadsPerWorker": shared.threads,
                "name": shared.name,
                "server": "rust",
                "version": env!("CARGO_PKG_VERSION"),
                "memory": status["memory"],
            });
            respond_json(out, 200, &info, keep, &[])
        }
        ("GET", "/v1/status") => respond_json(out, 200, &shared.engine.status(), keep, &[]),
        ("POST", "/v1/synthesize") => synthesize(&request.body, shared, out, keep),
        _ => respond_json(out, 404, &serde_json::json!({"error": "Not found"}), keep, &[]),
    }
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct SynthesizeRequest {
    text: String,
    speaker_id: i64,
}

fn synthesize(body: &[u8], shared: &Shared, out: &mut TcpStream, keep: bool) -> io::Result<()> {
    let Ok(request) = serde_json::from_slice::<SynthesizeRequest>(body) else {
        return respond_json(out, 400, &serde_json::json!({"error": "Expected JSON {text, speakerId}"}), keep, &[]);
    };
    if request.text.trim().is_empty() || request.text.chars().count() > MAX_TEXT_CHARS {
        return respond_json(out, 400, &serde_json::json!({"error": "Text is empty or too long"}), keep, &[]);
    }
    if !(0..SPEAKERS).contains(&request.speaker_id) {
        return respond_json(out, 400, &serde_json::json!({"error": format!("No speaker {}", request.speaker_id)}), keep, &[]);
    }
    shared.keep_awake.touch();
    let queued = Instant::now();
    let lease = match shared.engine.acquire(SLOT_PATIENCE) {
        Ok(lease) => lease,
        Err(busy) => return respond_busy(out, &busy, shared, keep),
    };
    if client_gone(out) {
        drop(lease);
        return Err(io::Error::new(io::ErrorKind::ConnectionAborted, "The client hung up while waiting"));
    }
    let started = Instant::now();
    let result = lease.tts().generate(&request.text, request.speaker_id as u32);
    drop(lease);
    let generate_ms = started.elapsed().as_millis() as u64;
    match result {
        Ok((rate, pcm)) => {
            let audio_ms = (pcm.len() as u64 / 2) * 1000 / rate.max(1) as u64;
            shared.engine.record(audio_ms, generate_ms, true);
            let slots = shared.engine.slots_now().to_string();
            let generate = generate_ms.to_string();
            let queue = (started - queued).as_millis().to_string();
            let headers = [
                ("Content-Type", "audio/wav"),
                ("X-Generate-Ms", generate.as_str()),
                ("X-Queue-Ms", queue.as_str()),
                ("X-Slots", slots.as_str()),
            ];
            let header = wav_header(rate, pcm.len());
            respond(out, 200, &headers, &[&header, &pcm], keep)
        }
        Err(error) => {
            shared.engine.record(0, generate_ms, false);
            respond_json(out, 500, &serde_json::json!({"error": format!("Generation failed: {error}")}), keep, &[])
        }
    }
}

fn respond_busy(out: &mut TcpStream, busy: &Busy, shared: &Shared, keep: bool) -> io::Result<()> {
    let message = format!("{} {}", shared.name, busy.message);
    if busy.broken {
        return respond_json(out, 500, &serde_json::json!({"error": message}), keep, &[]);
    }
    let seconds = busy.retry_after.as_secs().max(1).to_string();
    let body = serde_json::json!({
        "error": message,
        "retryAfterMs": busy.retry_after.as_millis() as u64,
        "memory": busy.level.name(),
        "availableMb": busy.available_mb,
        "stopped": busy.level == Level::Critical,
    });
    respond_json(out, 503, &body, keep, &[("Retry-After", seconds.as_str())])
}

/// True when the client has hung up (the app cancelled: paused, reordered, changed place), so a
/// sentence that waited for a slot is not generated for nobody.
fn client_gone(stream: &TcpStream) -> bool {
    if stream.set_nonblocking(true).is_err() {
        return false;
    }
    let mut probe = [0u8; 1];
    let gone = matches!(stream.peek(&mut probe), Ok(0));
    let _ = stream.set_nonblocking(false);
    gone
}

pub fn wav_header(sample_rate: u32, pcm_bytes: usize) -> [u8; 44] {
    let mut h = [0u8; 44];
    let data = pcm_bytes as u32;
    h[0..4].copy_from_slice(b"RIFF");
    h[4..8].copy_from_slice(&(36 + data).to_le_bytes());
    h[8..16].copy_from_slice(b"WAVEfmt ");
    h[16..20].copy_from_slice(&16u32.to_le_bytes());
    h[20..22].copy_from_slice(&1u16.to_le_bytes()); // PCM
    h[22..24].copy_from_slice(&1u16.to_le_bytes()); // mono
    h[24..28].copy_from_slice(&sample_rate.to_le_bytes());
    h[28..32].copy_from_slice(&(sample_rate * 2).to_le_bytes());
    h[32..34].copy_from_slice(&2u16.to_le_bytes());
    h[34..36].copy_from_slice(&16u16.to_le_bytes());
    h[36..40].copy_from_slice(b"data");
    h[40..44].copy_from_slice(&data.to_le_bytes());
    h
}

fn respond_json(out: &mut TcpStream, status: u16, body: &serde_json::Value, keep: bool, extra: &[(&str, &str)]) -> io::Result<()> {
    let bytes = serde_json::to_vec(body).unwrap_or_default();
    let mut headers = vec![("Content-Type", "application/json")];
    headers.extend_from_slice(extra);
    respond(out, status, &headers, &[&bytes], keep)
}

fn respond(out: &mut TcpStream, status: u16, headers: &[(&str, &str)], body: &[&[u8]], keep: bool) -> io::Result<()> {
    let reason = match status {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        500 => "Internal Server Error",
        503 => "Service Unavailable",
        _ => "Status",
    };
    let length: usize = body.iter().map(|part| part.len()).sum();
    let mut head = format!("HTTP/1.1 {status} {reason}\r\nServer: WebnovelNarration/2\r\nContent-Length: {length}\r\n");
    for (name, value) in headers {
        head.push_str(&format!("{name}: {value}\r\n"));
    }
    head.push_str(if keep { "Connection: keep-alive\r\n\r\n" } else { "Connection: close\r\n\r\n" });
    let mut parts: Vec<io::IoSlice> = Vec::with_capacity(body.len() + 1);
    parts.push(io::IoSlice::new(head.as_bytes()));
    parts.extend(body.iter().map(|part| io::IoSlice::new(part)));
    write_all_vectored(out, &mut parts)?;
    out.flush()
}

fn write_all_vectored(out: &mut TcpStream, mut parts: &mut [io::IoSlice]) -> io::Result<()> {
    while !parts.is_empty() {
        match out.write_vectored(parts) {
            Ok(0) => return Err(io::ErrorKind::WriteZero.into()),
            Ok(written) => io::IoSlice::advance_slices(&mut parts, written),
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

/// On a Mac, keeps it from idle-sleeping mid-batch while requests arrive (closing the lid still sleeps it).
pub struct KeepAwake {
    until: std::sync::Mutex<Option<Instant>>,
}

impl KeepAwake {
    pub fn new() -> KeepAwake {
        KeepAwake { until: std::sync::Mutex::new(None) }
    }

    pub fn touch(&self) {
        if !cfg!(target_os = "macos") {
            return;
        }
        let mut until = self.until.lock().unwrap_or_else(|p| p.into_inner());
        let now = Instant::now();
        if until.is_some_and(|deadline| now + Duration::from_secs(60) < deadline) {
            return;
        }
        *until = Some(now + Duration::from_secs(120));
        let _ = std::process::Command::new("caffeinate")
            .args(["-i", "-t", "120"])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .map(|mut child| std::thread::spawn(move || child.wait()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_compare_case_insensitively() {
        assert!(authorized("Bearer abcd-efgh-jkmn", "abcd-efgh-jkmn"));
        assert!(authorized("bearer ABCD-efgh-JKMN", "abcd-efgh-jkmn"));
        assert!(!authorized("Bearer abcd-efgh-jkm", "abcd-efgh-jkmn"));
        assert!(!authorized("Bearer abcd-efgh-jkmnp", "abcd-efgh-jkmn"));
        assert!(!authorized("", "abcd-efgh-jkmn"));
    }

    #[test]
    fn wav_header_describes_mono_16_bit() {
        let h = wav_header(24000, 480);
        assert_eq!(&h[0..4], b"RIFF");
        assert_eq!(u32::from_le_bytes(h[4..8].try_into().unwrap()), 36 + 480);
        assert_eq!(u32::from_le_bytes(h[24..28].try_into().unwrap()), 24000);
        assert_eq!(u32::from_le_bytes(h[40..44].try_into().unwrap()), 480);
    }
}
