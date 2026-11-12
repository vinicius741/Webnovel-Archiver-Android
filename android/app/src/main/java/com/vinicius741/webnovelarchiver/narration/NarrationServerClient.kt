package com.vinicius741.webnovelarchiver.narration

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import com.vinicius741.webnovelarchiver.tts.TtsPcmPlanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
internal class NarrationServerProblem(
    message: String,
    val transient: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A minimal HTTP/1.1 client for the narration server's two calls. It talks over a plain socket
 * because the server is on the user's own network (or Tailscale, which encrypts it), and Android's
 * cleartext policy would otherwise have to allow plain HTTP for the whole app.
 */
internal class NarrationServerClient(
    private val endpoint: NarrationServerEndpoint,
    private val token: String,
    private val name: String,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {
    private class Response(
        val status: Int,
        val body: ByteArray,
    ) {
        val errorMessage: String?
            get() =
                runCatching {
                    JsonParser
                        .parseString(body.toString(Charsets.UTF_8))
                        .asJsonObject
                        .get("error")
                        ?.asString
                }.getOrNull()
    }

    suspend fun info(): NarrationServerInfo {
        val response = request("GET", "/v1/info", null)
        if (response.status != HTTP_OK) throw problem(response)
        return runCatching { Gson().fromJson(response.body.toString(Charsets.UTF_8), NarrationServerInfo::class.java) }
            .getOrNull() ?: throw NarrationServerProblem("$name did not answer like a narration server")
    }

    /**
     * Speaks [text] as [speakerId] at 1x. Null when the server could not generate this one sentence
     * (the caller retries, then fails the chapter); throws [NarrationServerProblem] when the server
     * itself is unusable.
     */
    suspend fun synthesize(
        text: String,
        speakerId: Int,
    ): TtsPcmClip? {
        val body = Gson().toJson(mapOf("text" to text, "speakerId" to speakerId)).toByteArray(Charsets.UTF_8)
        val response = request("POST", "/v1/synthesize", body)
        return when {
            response.status == HTTP_OK -> TtsPcmPlanning.parseWav(response.body)
            response.status == HTTP_BAD_REQUEST || response.status >= HTTP_SERVER_ERROR -> null
            else -> throw problem(response)
        }
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

    /** One request on its own connection; cancelling closes the socket, which ends a blocked read. */
    private suspend fun request(
        method: String,
        path: String,
        body: ByteArray?,
    ): Response =
        coroutineScope {
            val socket = Socket()
            // The child returns its failure instead of throwing it, so the error a closed socket
            // raises after a cancellation never replaces the cancellation itself.
            val call = async(Dispatchers.IO) { runCatching { exchange(socket, method, path, body) } }
            try {
                call.await().getOrThrow()
            } catch (cancelled: CancellationException) {
                runCatching { socket.close() }
                throw cancelled
            }
        }

    private fun exchange(
        socket: Socket,
        method: String,
        path: String,
        body: ByteArray?,
    ): Response =
        try {
            socket.use {
                it.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
                it.soTimeout = readTimeoutMs
                it.tcpNoDelay = true
                val head =
                    buildString {
                        append("$method $path HTTP/1.1\r\n")
                        append("Host: ${endpoint.display}\r\n")
                        append("Authorization: Bearer $token\r\n")
                        append("Connection: close\r\n")
                        if (body != null) {
                            append("Content-Type: application/json\r\n")
                            append("Content-Length: ${body.size}\r\n")
                        }
                        append("\r\n")
                    }
                val output = it.getOutputStream()
                output.write(head.toByteArray(Charsets.UTF_8) + (body ?: ByteArray(0)))
                output.flush()
                readResponse(BufferedInputStream(it.getInputStream()))
            }
        } catch (timeout: SocketTimeoutException) {
            throw NarrationServerProblem("$name did not answer in time", transient = true, cause = timeout)
        } catch (unknown: UnknownHostException) {
            throw NarrationServerProblem("Can't find $name at ${endpoint.host}", transient = true, cause = unknown)
        } catch (refused: ConnectException) {
            throw NarrationServerProblem(
                "Can't reach $name. Check that it is on and the server is running",
                transient = true,
                cause = refused,
            )
        } catch (noRoute: NoRouteToHostException) {
            throw NarrationServerProblem("Can't reach $name from this network", transient = true, cause = noRoute)
        } catch (io: IOException) {
            throw NarrationServerProblem("Lost the connection to $name", transient = true, cause = io)
        }

    private fun readResponse(input: InputStream): Response {
        val status =
            readLine(input)?.split(' ')?.getOrNull(1)?.toIntOrNull()
                ?: throw IOException("The connection closed without an HTTP response")
        var length = -1
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim()
            if (name.equals("Content-Length", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: -1
        }
        if (length > MAX_RESPONSE_BYTES) throw IOException("The response is too large")
        val bytes =
            if (length >= 0) {
                ByteArray(length).also { DataInputStream(input).readFully(it) }
            } else {
                readAll(input)
            }
        return Response(status, bytes)
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
        private const val MAX_RESPONSE_BYTES = 64 * 1024 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val CHUNK_BYTES = 16 * 1024
        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_SERVER_ERROR = 500
    }
}
