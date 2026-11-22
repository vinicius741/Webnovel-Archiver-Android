package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import com.vinicius741.webnovelarchiver.source.PatreonAccount
import com.vinicius741.webnovelarchiver.source.PatreonSession
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Patreon sign-in as the account page sees it: the shared cookie, Patreon's API, Firefox and the clipboard. */
internal interface PatreonAccountData {
    /** The saved `session_id`, or null. */
    fun session(): String?

    fun storeSession(sessionId: String)

    /** Puts back a session that was saved before a refused one. */
    fun restoreSession(sessionId: String)

    fun clearSession()

    fun parsePaste(text: String?): PatreonSession.Paste

    /** Asks Patreon who the saved session belongs to; throws when it cannot. */
    suspend fun currentUser(): PatreonAccount

    fun firefoxInstalled(): Boolean

    /** The title of [storyId] when it is a novel with early access, so the page can offer to check it. */
    fun earlyAccessTitle(storyId: String): String?

    fun clearClipboard()
}

internal sealed interface AccountStatus {
    data object SignedOut : AccountStatus

    data object Checking : AccountStatus

    data class SignedIn(
        val account: PatreonAccount,
    ) : AccountStatus

    data object Rejected : AccountStatus

    data class Unreachable(
        val reason: String,
    ) : AccountStatus
}

internal data class PatreonAccountUiState(
    val sessionPresent: Boolean,
    val status: AccountStatus,
    /** Shown under the paste button after a paste that could not be used; never contains the value. */
    val pasteFeedback: String?,
    val stepsExpanded: Boolean,
    val firefoxInstalled: Boolean,
    /** The novel the page was opened from, when it has early access. */
    val earlyAccessTitle: String?,
) {
    val signedIn: Boolean get() = status is AccountStatus.SignedIn
}

/**
 * The Patreon account page (`AppRoute.PatreonAccount`): whether a sign-in is saved and what Patreon
 * says about it, the setup steps, and paste feedback. Returning from Firefox [refresh]es which steps
 * are done without asking Patreon again. A refused session never replaces one already saved, and an
 * accepted clipboard paste is wiped from the clipboard.
 */
internal class PatreonAccountScreenModel(
    private val data: PatreonAccountData,
    private val storyId: String?,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    /** What Patreon said about the saved session; null until asked (or after it changed). */
    private var status: AccountStatus? = null
    private var pasteFeedback: String? = null
    private var stepsExpanded = false
    private var check: Job? = null

    /** Counts checks and sign-outs, so a pasted sign-in's check that a newer one replaced decides nothing. */
    private var checkTicket = 0
    private val mutableState = MutableStateFlow(compute())
    val state: StateFlow<PatreonAccountUiState> = mutableState.asStateFlow()

    /** Re-reads the session and Firefox, and asks Patreon about a saved session not checked yet. */
    fun refresh(): PatreonAccountUiState {
        if (data.session() != null && status == null) checkSaved()
        return publish()
    }

    fun checkAgain() {
        status = null
        refresh()
    }

    fun signOut() {
        checkTicket += 1
        data.clearSession()
        status = null
        refresh()
    }

    fun expandSteps() {
        stepsExpanded = true
        publish()
    }

    /** Saves a sign-in pasted from the clipboard, or says why [clipboard] holds none. */
    fun paste(clipboard: String?) {
        pasteFeedback =
            when (val paste = data.parsePaste(clipboard)) {
                is PatreonSession.Paste.Found -> {
                    save(paste.sessionId, fromClipboard = true)
                    return
                }
                PatreonSession.Paste.Empty -> "The clipboard is empty. Copy your sign-in in Firefox first (step 4)."
                PatreonSession.Paste.CookiesWithoutSession ->
                    "Those cookies have no session_id. Sign in on patreon.com in Firefox, then export again."
                PatreonSession.Paste.Unrecognized -> "That doesn't look like a Patreon sign-in. Copy it again from Cookie-Editor."
            }
        publish()
    }

    /** Saves a typed `session_id`; false when [text] holds none. */
    fun enterManually(text: String): Boolean {
        val paste = data.parsePaste(text) as? PatreonSession.Paste.Found ?: return false
        save(paste.sessionId, fromClipboard = false)
        return true
    }

    private fun save(
        sessionId: String,
        fromClipboard: Boolean,
    ) {
        val previous = data.session()
        data.storeSession(sessionId)
        pasteFeedback = null
        status = AccountStatus.Checking
        publish()
        check?.cancel()
        val ticket = ++checkTicket
        check =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // To the end even when the page closes meanwhile: a sign-in Patreon refuses must not stay
                // saved in place of the previous one, and an accepted one must not stay on the clipboard.
                withContext(NonCancellable) {
                    val result = checkAccount()
                    if (ticket != checkTicket) return@withContext
                    if (result is AccountStatus.Rejected) {
                        if (previous != null && previous != sessionId) data.restoreSession(previous) else data.clearSession()
                        status = null
                        pasteFeedback = "Patreon didn't accept that sign-in. Copy session_id again while signed in on patreon.com."
                    } else {
                        status = result
                        if (result is AccountStatus.SignedIn) stepsExpanded = false
                        if (fromClipboard) data.clearClipboard()
                    }
                    publish()
                }
            }
    }

    private fun checkSaved() {
        status = AccountStatus.Checking
        check?.cancel()
        checkTicket += 1
        check =
            scope.launch {
                status = checkAccount()
                publish()
            }
    }

    private suspend fun checkAccount(): AccountStatus =
        try {
            AccountStatus.SignedIn(data.currentUser())
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpNetworkException) {
            if (error.statusCode in setOf(401, 403)) AccountStatus.Rejected else AccountStatus.Unreachable("HTTP ${error.statusCode}")
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            AccountStatus.Unreachable(error.message ?: error.javaClass.simpleName)
        }

    private fun publish(): PatreonAccountUiState = compute().also { mutableState.update { _ -> it } }

    private fun compute(): PatreonAccountUiState {
        val present = data.session() != null
        return PatreonAccountUiState(
            sessionPresent = present,
            status = if (present) status ?: AccountStatus.Checking else AccountStatus.SignedOut,
            pasteFeedback = pasteFeedback,
            stepsExpanded = stepsExpanded,
            firefoxInstalled = data.firefoxInstalled(),
            earlyAccessTitle = storyId?.let(data::earlyAccessTitle),
        )
    }
}
