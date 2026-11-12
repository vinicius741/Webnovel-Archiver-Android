package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import java.util.Locale

/** Rules for the user's narration servers: compatibility, how many sentences to send at once, form checks. */
internal object NarrationServerPlanning {
    const val PROTOCOL = 1

    /** The model the server must run for its clips to match the phone's ([KokoroVoices.ENGINE_ID]). */
    val MODEL: String = KokoroVoices.ENGINE_ID.substringAfter("builtin:")

    /** Upper bound on sentences in flight, whatever the server reports. */
    const val MAX_IN_FLIGHT = 9

    /** Why audio from this server would not match the phone's, or null when it is usable. */
    fun incompatibility(
        info: NarrationServerInfo,
        name: String,
    ): String? =
        when {
            info.protocol != PROTOCOL -> "$name runs another version of the narration server; update it with setup.sh"
            info.model != MODEL -> "$name runs ${info.model.ifBlank { "an unknown model" }}, not $MODEL"
            else -> null
        }

    /**
     * One sentence per model copy, plus one waiting on the server so a copy never idles while the
     * next request travels over the network.
     */
    fun inFlight(info: NarrationServerInfo): Int = (info.workers.coerceAtLeast(1) + 1).coerceAtMost(MAX_IN_FLIGHT)

    /** What is wrong with the add/edit form, or null when it can be saved. */
    fun formError(
        name: String,
        address: String,
        token: String,
    ): String? =
        when {
            name.isBlank() -> "Give the computer a name"
            NarrationServerEndpoint.parse(address) == null -> "Enter an address like 192.168.1.20 or my-mac:8790"
            token.length < MIN_TOKEN_CHARS -> "Enter the token setup.sh printed"
            token.any { it.isWhitespace() || it.isISOControl() } -> "The token cannot contain spaces"
            else -> null
        }

    /** Tokens are typed by hand from the computer's screen; case does not matter in the generated ones. */
    fun normalizeToken(token: String): String = token.trim().lowercase(Locale.ROOT)

    /** A one-line summary of a successful connection test. */
    fun testSummary(
        info: NarrationServerInfo,
        endpoint: NarrationServerEndpoint,
        audioMs: Long,
        realMs: Long,
    ): String {
        val copies = if (info.workers == 1) "1 model copy" else "${info.workers} model copies"
        val speed =
            if (realMs > 0) {
                " · generates ${String.format(Locale.US, "%.1fx", audioMs.toDouble() / realMs)} real time"
            } else {
                ""
            }
        return "Connected to ${info.name.ifBlank { endpoint.display }} · ${info.model} · $copies$speed"
    }

    private const val MIN_TOKEN_CHARS = 8
}
