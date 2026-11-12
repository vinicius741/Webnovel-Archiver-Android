package com.vinicius741.webnovelarchiver.narration

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Whether each of the user's computers is online, checked while Audio generation is open
 * (`docs/tts/remote-narration-servers.md`). A check asks `/v1/status` on each address in turn, the
 * local network first and then Tailscale, so it costs the computer nothing: the model does not load
 * and the computer is not kept awake.
 */
internal class NarrationServerMonitor(
    private val now: () -> Long = System::currentTimeMillis,
    private val check: suspend (NarrationServer) -> NarrationServerStatus = { server -> checkServer(server, now()) },
) {
    private val statuses = MutableStateFlow<Map<String, NarrationServerStatus>>(emptyMap())
    private val checking = Mutex()

    /** The last check of each computer, by server id. */
    val state: StateFlow<Map<String, NarrationServerStatus>> get() = statuses

    /**
     * Checks every one of [servers] whose status is older than [maxAgeMs], all at once, and forgets
     * computers no longer listed. A check already running covers this call.
     */
    suspend fun refresh(
        servers: List<NarrationServer>,
        maxAgeMs: Long,
    ) {
        if (!checking.tryLock()) return
        try {
            val ids = servers.map { it.id }.toSet()
            statuses.update { current -> current.filterKeys { it in ids } }
            val due =
                servers.filter { server ->
                    NarrationServerPlanning.needsCheck(statuses.value[server.id], server.locationKey, now(), maxAgeMs)
                }
            coroutineScope {
                due.map { server -> async { record(server, check(server)) } }.awaitAll()
            }
        } finally {
            checking.unlock()
        }
    }

    /** Records what a check (or a connection test) found. */
    fun record(
        server: NarrationServer,
        status: NarrationServerStatus,
    ) {
        statuses.update { it + (server.id to status) }
    }

    companion object {
        /** A silent address (another device on a different network) must not hold the check for minutes. */
        private const val CHECK_READ_TIMEOUT_MS = 10_000

        @Suppress("SwallowedException") // A rejected token is an answer: the computer is online, its token is wrong.
        suspend fun checkServer(
            server: NarrationServer,
            checkedAtMs: Long,
        ): NarrationServerStatus {
            val key = server.locationKey
            return try {
                val reached = reachServer(server, readTimeoutMs = CHECK_READ_TIMEOUT_MS) { it.memoryLevel() }
                reached.client.close()
                NarrationServerStatus(
                    key,
                    checkedAtMs,
                    online = true,
                    route = reached.route.route,
                    problem = NarrationServerPlanning.memoryProblem(reached.value),
                )
            } catch (rejected: NarrationServerRejected) {
                NarrationServerStatus(key, checkedAtMs, online = true, problem = "wrong token")
            } catch (problem: NarrationServerProblem) {
                NarrationServerStatus(key, checkedAtMs, online = false, problem = problem.message)
            }
        }
    }
}
