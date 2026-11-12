package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import java.util.Locale

/** How the phone reaches a computer: its local-network address first, then its Tailscale one. */
internal enum class NarrationRoute(
    val label: String,
) {
    LOCAL("local network"),
    TAILSCALE("Tailscale"),
}

/** One address to try for a computer. */
internal data class NarrationServerRoute(
    val route: NarrationRoute,
    val endpoint: NarrationServerEndpoint,
)

/**
 * The last online check of a computer, made while Audio generation is open. [locationKey] ties it
 * to the addresses and token it checked, so an edit checks again.
 */
internal data class NarrationServerStatus(
    val locationKey: String,
    val checkedAtMs: Long,
    val online: Boolean,
    /** How it answered, when it did. */
    val route: NarrationRoute? = null,
    /** Why it answers but cannot generate right now (a wrong token, short of memory). */
    val problem: String? = null,
)

/** How a computer's status reads at a glance. */
internal enum class NarrationServerTone { CHECKING, ONLINE, WARNING, OFFLINE }

/** Rules for the user's narration servers: compatibility, how many sentences to send at once, form checks. */
@Suppress("TooManyFunctions") // Small, related rules about the same computers.
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

    /** Connect timeout while a later address is still left to try; the last address gets the client's usual one. */
    const val FALLBACK_CONNECT_TIMEOUT_MS = 2_500

    /** The addresses to try, in order: the local network, then Tailscale. Blank or invalid ones are skipped. */
    fun routes(server: NarrationServer): List<NarrationServerRoute> =
        buildList {
            NarrationServerEndpoint.parse(server.address)?.let { add(NarrationServerRoute(NarrationRoute.LOCAL, it)) }
            NarrationServerEndpoint.parse(server.tailscaleAddress)?.let { endpoint ->
                if (none { it.endpoint == endpoint }) add(NarrationServerRoute(NarrationRoute.TAILSCALE, endpoint))
            }
        }

    /**
     * How long to wait for a connection on route [index] of [count]. Away from home the local address
     * usually does not answer at all, so it gets a short wait before Tailscale is tried; null keeps the
     * client's usual timeout.
     */
    fun connectTimeoutMs(
        index: Int,
        count: Int,
    ): Int? = if (index < count - 1) FALLBACK_CONNECT_TIMEOUT_MS else null

    /**
     * The one problem to report after every route failed. A server that answered (a wrong token, another
     * model) says more than one that could not be reached; when none answered, one message covers both.
     */
    fun failure(
        name: String,
        failures: List<NarrationServerProblem>,
    ): NarrationServerProblem =
        when {
            failures.isEmpty() -> NarrationServerProblem("$name has no valid address. Edit it in Audio generation.")
            failures.size == 1 -> failures.single()
            else ->
                failures.firstOrNull { !it.transient }
                    ?: NarrationServerProblem(
                        "Can't reach $name on the local network or over Tailscale. Check that it is on and the server is running",
                        transient = true,
                        cause = failures.last(),
                    )
        }

    /** What is wrong with the add/edit form, or null when it can be saved. Either address may be left blank, not both. */
    fun formError(
        name: String,
        address: String,
        tailscaleAddress: String,
        token: String,
    ): String? =
        when {
            name.isBlank() -> "Give the computer a name"
            address.isBlank() && tailscaleAddress.isBlank() -> "Enter its local network address, its Tailscale address, or both"
            address.isNotBlank() && NarrationServerEndpoint.parse(address) == null ->
                "Enter a local address like 192.168.1.20 or my-mac:8790"
            tailscaleAddress.isNotBlank() && NarrationServerEndpoint.parse(tailscaleAddress) == null ->
                "Enter a Tailscale address like 100.101.102.103 or my-mac.tail1234.ts.net"
            token.length < MIN_TOKEN_CHARS -> "Enter the token setup.sh printed"
            token.any { it.isWhitespace() || it.isISOControl() } -> "The token cannot contain spaces"
            else -> null
        }

    /** Whether a status is missing, older than [maxAgeMs], or was made for other addresses or another token. */
    fun needsCheck(
        status: NarrationServerStatus?,
        locationKey: String,
        nowMs: Long,
        maxAgeMs: Long,
    ): Boolean = status == null || status.locationKey != locationKey || nowMs - status.checkedAtMs >= maxAgeMs

    /** Why a server whose memory is at [level] (its `/v1/status`) cannot generate now, or null when it can. */
    fun memoryProblem(level: String): String? = if (level == "critical") "stopped, short of memory" else null

    fun tone(status: NarrationServerStatus?): NarrationServerTone =
        when {
            status == null -> NarrationServerTone.CHECKING
            !status.online -> NarrationServerTone.OFFLINE
            status.problem != null -> NarrationServerTone.WARNING
            else -> NarrationServerTone.ONLINE
        }

    /** "Online · local network · generating here", "Offline", or "Checking…" before the first answer. */
    fun statusLine(
        status: NarrationServerStatus?,
        generatingHere: Boolean,
    ): String =
        buildList {
            when {
                status == null -> add("Checking…")
                status.online -> {
                    add("Online")
                    status.route?.let { add(it.label) }
                    status.problem?.let { add(it) }
                }
                else -> add("Offline")
            }
            if (generatingHere) add("generating here")
        }.joinToString(" · ")

    /** "192.168.1.20 · Tailscale 100.101.102.103", for the computer's row. */
    fun addressesLine(server: NarrationServer): String =
        listOfNotNull(
            server.address.trim().ifBlank { null },
            server.tailscaleAddress
                .trim()
                .ifBlank { null }
                ?.let { "Tailscale $it" },
        ).joinToString(" · ")

    /** Tokens are typed by hand from the computer's screen; case does not matter in the generated ones. */
    fun normalizeToken(token: String): String = token.trim().lowercase(Locale.ROOT)

    /** A one-line summary of a successful connection test. */
    fun testSummary(
        info: NarrationServerInfo,
        route: NarrationServerRoute,
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
        val via = if (route.route == NarrationRoute.TAILSCALE) " over Tailscale" else " on the local network"
        return "Connected to ${info.name.ifBlank { route.endpoint.display }}$via · ${info.model} · $copies$speed"
    }

    private const val MIN_TOKEN_CHARS = 8
}
