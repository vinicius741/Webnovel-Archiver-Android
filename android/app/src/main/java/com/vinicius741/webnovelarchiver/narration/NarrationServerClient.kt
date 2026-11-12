package com.vinicius741.webnovelarchiver.narration

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import com.vinicius741.webnovelarchiver.tts.TtsPcmPlanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Where a narration server listens, parsed from what the user typed: `host`, `host:port` or `http://host:port`. */
internal data class NarrationServerEndpoint(
    val host: String,
    val port: Int,
) {
    val display: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 8790
        private const val MAX_PORT = 65_535

        fun parse(address: String): NarrationServerEndpoint? {
            val text = address.trim().removePrefix("http://").trimEnd('/')
            if (text.isEmpty() || text.contains("://") || text.contains('/') || text.any(Char::isWhitespace)) return null
            val (host, portText) =
                if (text.startsWith("[")) {
                    val end = text.indexOf(']')
                    if (end < 0) return null
                    text.substring(1, end) to text.substring(end + 1).removePrefix(":").ifEmpty { null }
                } else {
                    val parts = text.split(':')
                    when (parts.size) {
                        1 -> parts[0] to null
                        2 -> parts[0] to parts[1]
                        else -> return null
                    }
                }
            val port = if (portText == null) DEFAULT_PORT else portText.toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..MAX_PORT) return null
            return NarrationServerEndpoint(host, port)
        }
    }
}

/** What `GET /v1/info` reports. */
internal data class NarrationServerInfo(
    val protocol: Int = 0,
    val model: String = "",
    val sampleRate: Int = 0,
    val workers: Int = 0,
    val threadsPerWorker: Int = 0,
    val name: String = "",
)

/**
 * The server cannot generate right now: unreachable (off, asleep, another network, a firewall),
 * the token is wrong, or it runs another model. Generation pauses with this message instead of
 * failing every queued chapter; [transient] problems are worth one retry first.
 */
internal open class NarrationServerProblem(
    message: String,
    val transient: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The server is up but holding back to protect its machine's memory: it generates fewer
 * sentences or none for now. Generation waits [retryAfterMs] and asks again, without failing
 * the chapter or pausing the queue; [stopped] when the server has stopped generating altogether.
 */
internal class NarrationServerBusy(
    message: String,
    val retryAfterMs: Long,
    val stopped: Boolean,
) : NarrationServerProblem(message, transient = true)

/**
 * A minimal HTTP/1.1 client for the narration server's calls. It talks over a plain socket
 * because the server is on the user's own network (or Tailscale, which encrypts it), and Android's
 * cleartext policy would otherwise have to allow plain HTTP for the whole app.
 *
 * Connections are kept alive and reused, one per sentence in flight, so a chapter of hundreds of
 * sentences costs a handful of TCP (and Tailscale) handshakes instead of one per sentence. Call
 * [close] when done to release them.
 */
@Suppress("TooManyFunctions") // One connection pool and the HTTP exchange it serves, kept together.
internal class NarrationServerClient(
    private val endpoint: NarrationServerEndpoint,
    private val token: String,
    private val name: String,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Response(
        val status: Int,
        val body: ByteArray,
        val keepAlive: Boolean,
        val retryAfterSeconds: Long?,
    ) {
        val json: JsonObject?
            get() = runCatching { JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject }.getOrNull()

        val errorMessage: String? get() = runCatching { json?.get("error")?.asString }.getOrNull()
    }

    private class Connection(
        val socket: Socket,
    ) {
        val input = BufferedInputStream(socket.getInputStream())
        var idleSince = 0L
    }

    private val idle = ArrayDeque<Connection>()
    private var closed = false

    /** Sentences the server lets generate at once right now (its `X-Slots`), once it has said. */
    @Volatile
    var serverSlots: Int? = null
        private set

    suspend fun info(): NarrationServerInfo {
        val response = request("GET", "/v1/info", null)
        if (response.status != HTTP_OK) throw problem(response)
        return runCatching { Gson().fromJson(response.body.toString(Charsets.UTF_8), NarrationServerInfo::class.java) }
            .getOrNull() ?: throw NarrationServerProblem("$name did not answer like a narration server")
    }

    /**
     * Speaks [text] as [speakerId] at 1x. Null when the server could not generate this one sentence
     * (the caller retries, then fails the chapter); throws [NarrationServerBusy] while the server
     * holds back for memory, and [NarrationServerProblem] when the server itself is unusable.
     */
    suspend fun synthesize(
        text: String,
        speakerId: Int,
    ): TtsPcmClip? {
        val body = Gson().toJson(mapOf("text" to text, "speakerId" to speakerId)).toByteArray(Charsets.UTF_8)
        val response = request("POST", "/v1/synthesize", body)
        return when {
            response.status == HTTP_OK -> TtsPcmPlanning.parseWav(response.body)
            response.status == HTTP_UNAVAILABLE -> throw busy(response)
            response.status == HTTP_BAD_REQUEST || response.status >= HTTP_SERVER_ERROR -> null
            else -> throw problem(response)
        }
    }

    /** Closes the kept-alive connections; a request after this opens a new one. */
    fun close() {
        val connections =
            synchronized(idle) {
                closed = true
                idle.toList().also { idle.clear() }
            }
        connections.forEach { runCatching { it.socket.close() } }
    }

    private fun busy(response: Response): NarrationServerBusy {
        val json = response.json
        val retryMs =
            runCatching { json?.get("retryAfterMs")?.asLong }.getOrNull()
                ?: response.retryAfterSeconds?.let { it * MS_PER_SECOND }
                ?: DEFAULT_RETRY_MS
        val stopped = runCatching { json?.get("stopped")?.asBoolean }.getOrNull() ?: false
        return NarrationServerBusy(
            response.errorMessage ?: "$name is busy",
            retryMs.coerceIn(MIN_RETRY_MS, MAX_RETRY_MS),
            stopped,
        )
    }

    private fun problem(response: Response): NarrationServerProblem =
        when (response.status) {
            HTTP_UNAUTHORIZED -> NarrationServerProblem("$name rejected the token. Check it in Audio generation.")
            HTTP_NOT_FOUND -> NarrationServerProblem("$name is not a narration server, or runs an older version")
            else ->
                NarrationServerProblem(
                    "$name answered with error ${response.status}: ${response.errorMessage.orEmpty()}".trimEnd(' ', ':'),
                )
        }

    /**
     * One request on a kept-alive connection when one is free, else a new one. Cancelling closes
     * the socket, which ends a blocked read. A reused connection the server already closed fails
     * at once; the request is then sent once more on a fresh connection.
     */
    @Suppress("SwallowedException") // A stale kept-alive connection is expected; the retry replaces it.
    private suspend fun request(
        method: String,
        path: String,
        body: ByteArray?,
    ): Response {
        val reused = takeIdle()
        if (reused != null) {
            try {
                return exchangeCancellable(reused, method, path, body)
            } catch (stale: StaleConnection) {
                // Expected after an idle spell: the server closed the kept-alive connection. Reconnect.
                Timber.d(stale.cause, "Narration server connection was closed; reconnecting")
            }
        }
        // A fresh connection never reports StaleConnection: its failures are already translated.
        return exchangeCancellable(null, method, path, body)
    }

    /** Thrown for an I/O failure on a connection; the caller decides whether a fresh one may retry. */
    private class StaleConnection(
        cause: IOException,
    ) : Exception(cause)

    private suspend fun exchangeCancellable(
        reuse: Connection?,
        method: String,
        path: String,
        body: ByteArray?,
    ): Response =
        coroutineScope {
            val socket = reuse?.socket ?: Socket()
            // The child returns its failure instead of throwing it, so the error a closed socket
            // raises after a cancellation never replaces the cancellation itself.
            val call =
                async(Dispatchers.IO) {
                    runCatching {
                        val connection = reuse ?: connect(socket)
                        exchange(connection, method, path, body, reused = reuse != null)
                    }
                }
            try {
                call.await().getOrThrow()
            } catch (cancelled: CancellationException) {
                runCatching { socket.close() }
                throw cancelled
            }
        }

    private fun connect(socket: Socket): Connection =
        try {
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            socket.keepAlive = true
            Connection(socket)
        } catch (io: IOException) {
            runCatching { socket.close() }
            throw translate(io)
        }

    @Suppress("ThrowsCount") // Stale reuse, timeouts and other I/O failures map to distinct outcomes.
    private fun exchange(
        connection: Connection,
        method: String,
        path: String,
        body: ByteArray?,
        reused: Boolean,
    ): Response {
        var answered = false
        try {
            val head =
                buildString {
                    append("$method $path HTTP/1.1\r\n")
                    append("Host: ${endpoint.display}\r\n")
                    append("Authorization: Bearer $token\r\n")
                    append("Connection: keep-alive\r\n")
                    if (body != null) {
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${body.size}\r\n")
                    }
                    append("\r\n")
                }
            val output = connection.socket.getOutputStream()
            output.write(head.toByteArray(Charsets.UTF_8) + (body ?: ByteArray(0)))
            output.flush()
            val response = readResponse(connection.input) { answered = true }
            if (response.keepAlive) release(connection) else runCatching { connection.socket.close() }
            return response
        } catch (timeout: SocketTimeoutException) {
            runCatching { connection.socket.close() }
            throw translate(timeout)
        } catch (io: IOException) {
            runCatching { connection.socket.close() }
            // Nothing came back on a reused connection: the server had closed it while idle.
            if (reused && !answered) throw StaleConnection(io)
            throw translate(io)
        }
    }

    private fun takeIdle(): Connection? {
        val stale = mutableListOf<Connection>()
        val fresh =
            synchronized(idle) {
                var found: Connection? = null
                while (found == null && idle.isNotEmpty()) {
                    val candidate = idle.removeLast()
                    if (now() - candidate.idleSince > MAX_IDLE_MS || candidate.socket.isClosed) stale += candidate else found = candidate
                }
                found
            }
        stale.forEach { runCatching { it.socket.close() } }
        return fresh
    }

    private fun release(connection: Connection) {
        connection.idleSince = now()
        val keep =
            synchronized(idle) {
                (!closed && idle.size < MAX_IDLE_CONNECTIONS).also { if (it) idle.addLast(connection) }
            }
        if (!keep) runCatching { connection.socket.close() }
    }

    private fun translate(io: IOException): NarrationServerProblem =
        when (io) {
            is SocketTimeoutException -> NarrationServerProblem("$name did not answer in time", transient = true, cause = io)
            is UnknownHostException -> NarrationServerProblem("Can't find $name at ${endpoint.host}", transient = true, cause = io)
            is ConnectException ->
                NarrationServerProblem("Can't reach $name. Check that it is on and the server is running", transient = true, cause = io)
            is NoRouteToHostException -> NarrationServerProblem("Can't reach $name from this network", transient = true, cause = io)
            else -> NarrationServerProblem("Lost the connection to $name", transient = true, cause = io)
        }

    @Suppress("ThrowsCount") // Each malformed-response case is its own I/O failure.
    private fun readResponse(
        input: InputStream,
        onFirstByte: () -> Unit,
    ): Response {
        val statusLine = readLine(input) ?: throw IOException("The connection closed without an HTTP response")
        onFirstByte()
        val parts = statusLine.split(' ')
        val status = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("Not an HTTP response")
        var keepAlive = parts.getOrNull(0) == "HTTP/1.1"
        var length = -1
        var retryAfter: Long? = null
        while (true) {
            // The headers end with an empty line; the connection closing first is a cut-off response.
            val line = readLine(input) ?: throw IOException("The connection closed in the middle of a response")
            if (line.isEmpty()) break
            val header = line.substringBefore(':').trim()
            val value = line.substringAfter(':').trim()
            when {
                header.equals("Content-Length", ignoreCase = true) -> length = value.toIntOrNull() ?: -1
                header.equals("Retry-After", ignoreCase = true) -> retryAfter = value.toLongOrNull()
                header.equals("X-Slots", ignoreCase = true) -> value.toIntOrNull()?.let { serverSlots = it.coerceAtLeast(1) }
                header.equals("Connection", ignoreCase = true) ->
                    keepAlive =
                        when {
                            value.contains("close", ignoreCase = true) -> false
                            value.contains("keep-alive", ignoreCase = true) -> true
                            else -> keepAlive
                        }
            }
        }
        if (length > MAX_RESPONSE_BYTES) throw IOException("The response is too large")
        val bytes =
            if (length >= 0) {
                ByteArray(length).also { DataInputStream(input).readFully(it) }
            } else {
                // No length: the body runs to the end of the connection, which cannot be reused.
                keepAlive = false
                readAll(input)
            }
        return Response(status, bytes, keepAlive, retryAfter)
    }

    private fun readAll(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
            if (buffer.size() > MAX_RESPONSE_BYTES) throw IOException("The response is too large")
        }
        return buffer.toByteArray()
    }

    /** One header line without its CRLF; null at the end of the stream. */
    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
            if (byte == '\n'.code) break
            if (byte != '\r'.code) line.write(byte)
            if (line.size() > MAX_HEADER_LINE_BYTES) throw IOException("A response header is too long")
        }
        return line.toString(Charsets.ISO_8859_1.name())
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000

        /** A long sentence on a busy server can take a minute; this only catches a hung server. */
        const val READ_TIMEOUT_MS = 180_000

        /** The server closes idle connections after 75 s; drop ours well before that. */
        private const val MAX_IDLE_MS = 45_000L
        private const val MAX_IDLE_CONNECTIONS = NarrationServerPlanning.MAX_IN_FLIGHT + 1
        private const val DEFAULT_RETRY_MS = 5_000L
        private const val MIN_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val MS_PER_SECOND = 1_000L
        private const val MAX_RESPONSE_BYTES = 64 * 1024 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val CHUNK_BYTES = 16 * 1024
        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_SERVER_ERROR = 500
        private const val HTTP_UNAVAILABLE = 503
    }
}
