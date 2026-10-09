package com.vinicius741.webnovelarchiver.feature.settings

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.feature.browser.FirefoxLauncher
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.PatreonAccount
import com.vinicius741.webnovelarchiver.source.PatreonApi
import com.vinicius741.webnovelarchiver.source.PatreonSession
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.FormLayout
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyFormStyle
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.clearClipboard
import com.vinicius741.webnovelarchiver.ui.clipboardText
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeField
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun patreonAccountSummary(): String =
    if (PatreonSession.isPresent()) "Signed in" else "Not signed in · set up early-access chapters"

private const val COOKIE_EDITOR_URL = "https://addons.mozilla.org/android/addon/cookie-editor/"
private const val PATREON_LOGIN_URL = "https://www.patreon.com/login"

private sealed interface AccountStatus {
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

/** Process-lifetime screen state, so returning from Firefox re-renders without re-asking Patreon. */
private object PatreonAccountScreenState {
    var status: AccountStatus? = null

    /** Shown under the paste button after a paste that could not be used; never contains the value. */
    var pasteFeedback: String? = null

    var stepsExpanded = false
}

/**
 * Patreon sign-in guide. Google sign-in cannot run inside the app, so the user signs in with
 * Firefox, copies the `session_id` cookie with the Cookie-Editor add-on, and pastes it here in one
 * tap. The value is never displayed, logged, or stored outside the shared cookie store, and the
 * clipboard is cleared once Patreon accepts it.
 */
internal fun ScreenHost.showPatreonAccount(storyId: String? = null) {
    rerender = { showPatreonAccount(storyId) }
    val state = PatreonAccountScreenState
    val sessionPresent = PatreonSession.isPresent()
    val status = if (sessionPresent) state.status ?: AccountStatus.Checking else AccountStatus.SignedOut
    if (sessionPresent && state.status == null) refreshAccountStatus(storyId)
    val signedIn = status is AccountStatus.SignedIn
    val firefox = FirefoxLauncher.installedPackage(app)
    val onBack = { if (storyId != null) showDetails(storyId) else showDataBackup() }

    screen(route = AppRoute.PatreonAccount(storyId), title = "Patreon Account", onBack = onBack, scrollable = true) {
        addView(buildStatusCard(this, status, storyId))
        if (signedIn && !state.stepsExpanded) {
            fullButton("Sign in with a different account", Btn.TEXT, bottomMarginDp = Space.SM) {
                state.stepsExpanded = true
                showPatreonAccount(storyId)
            }
            return@screen
        }
        section(if (sessionPresent) "Sign in again" else "Set up in 5 steps")
        addView(
            setupStep(
                this,
                1,
                "Install Firefox",
                if (firefox != null) {
                    "Firefox is installed."
                } else {
                    "Patreon's Google sign-in doesn't work inside other apps, so you'll sign in with Firefox."
                },
                done = firefox != null,
            ) {
                if (firefox == null) button("Get Firefox", Btn.TONAL, R.drawable.wna_download) { FirefoxLauncher.openStoreListing(app) }
            },
        )
        addView(
            setupStep(
                this,
                2,
                "Add Cookie-Editor to Firefox",
                "A free add-on that can copy your Patreon sign-in. On its page, tap Add to Firefox, then Add.",
            ) {
                button("Open add-on page", Btn.TONAL, R.drawable.wna_open_external) { openInFirefox(COOKIE_EDITOR_URL) }
            },
        )
        addView(
            setupStep(
                this,
                3,
                "Sign in to Patreon",
                "Any sign-in method works, Google included. Use the account that supports your creators.",
            ) {
                button("Open Patreon in Firefox", Btn.TONAL, R.drawable.wna_open_external) { openInFirefox(PATREON_LOGIN_URL) }
            },
        )
        addView(
            setupStep(
                this,
                4,
                "Copy your sign-in",
                "Still on patreon.com, open Firefox's ⋮ menu › Extensions › Cookie-Editor and tap Export. " +
                    "Or tap session_id and copy its Value.",
            ),
        )
        addView(
            setupStep(this, 5, "Paste it here", "Come back to this screen and tap Paste. The app picks out session_id.") {
                orientation = LinearLayout.VERTICAL
                fullButton("Paste from clipboard", Btn.FILLED, R.drawable.wna_paste, bottomMarginDp = 0) { pasteSession(storyId) }
                state.pasteFeedback?.let { feedback ->
                    spacer(Space.SM)
                    text(feedback, Type.BODY_SMALL, ThemeManager.colors.error)
                }
                fullButton("Enter it manually", Btn.TEXT, topMarginDp = Space.XS, bottomMarginDp = 0) { showManualEntry(storyId) }
            },
        )
        text(
            "On a computer instead? Sign in on patreon.com, open the browser's developer tools › Cookies, " +
                "copy session_id, and send it to this phone.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        spacer(Space.LG)
    }
}

private fun ScreenHost.buildStatusCard(
    parent: ViewGroup,
    status: AccountStatus,
    storyId: String?,
): LinearLayout {
    val colors = ThemeManager.colors
    val (title, body) =
        when (status) {
            AccountStatus.SignedOut ->
                "Not signed in" to "Sign in to read early-access chapters from creators you support on Patreon."
            AccountStatus.Checking -> "Checking with Patreon..." to null
            is AccountStatus.SignedIn ->
                "Signed in as ${status.account.name ?: "Patreon user"}" to
                    status.account.memberships
                        .joinToString { it.name }
                        .ifBlank { null }
                        ?.let { "Supporting $it" }
                        .orEmpty()
                        .ifBlank { "This account has no paid memberships, so locked chapters stay locked." }
            AccountStatus.Rejected -> "Patreon didn't accept the saved sign-in" to "It may have expired. Follow the steps below again."
            is AccountStatus.Unreachable -> "Sign-in saved" to "Couldn't reach Patreon to confirm it: ${status.reason}"
        }
    return parent.card {
        text(title, Type.TITLE_SMALL)
        body?.let {
            spacer(Space.XS)
            text(it, Type.BODY_SMALL, colors.onSurfaceVariant)
        }
        val story = storyId?.let { repository.story(it) }
        if (status is AccountStatus.SignedIn && story != null && story.patreonEarlyAccess != null) {
            fullButton("Check “${story.title}” now", Btn.FILLED, topMarginDp = Space.MD, bottomMarginDp = 0) {
                showDetails(story.id)
                syncStory(story)
            }
        }
        if (status !is AccountStatus.SignedOut && status !is AccountStatus.Checking) {
            row {
                if (status !is AccountStatus.SignedIn) {
                    button("Check again", Btn.TEXT) {
                        PatreonAccountScreenState.status = null
                        showPatreonAccount(storyId)
                    }
                }
                button("Sign out", Btn.TEXT) {
                    confirm("Sign out of Patreon? Stored early-access chapters stay; new ones stop arriving.", confirmLabel = "Sign out") {
                        PatreonSession.clear()
                        PatreonAccountScreenState.status = null
                        showPatreonAccount(storyId)
                    }
                }
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = dp(Space.SM)
                    bottomMargin = 0
                }
            }
        }
    }
}

/** A numbered step: badge (a check once [done]), title, short body, and optional actions. */
private fun ScreenHost.setupStep(
    parent: ViewGroup,
    number: Int,
    title: String,
    body: String,
    done: Boolean = false,
    actions: (LinearLayout.() -> Unit)? = null,
): LinearLayout {
    val colors = ThemeManager.colors
    return parent.card {
        row(gravity = Gravity.TOP) {
            addView(
                TextView(app).apply {
                    gravity = Gravity.CENTER
                    text = if (done) "✓" else number.toString()
                    setTextColor(if (done) colors.onPrimary else colors.onPrimaryContainer)
                    setTypeface(typeface, Typeface.BOLD)
                    background = roundedBg(if (done) colors.primary else colors.primaryContainer, dp(14).toFloat())
                    contentDescription = if (done) "Step $number, done" else "Step $number"
                },
                LinearLayout.LayoutParams(dp(28), dp(28)),
            )
            addView(
                LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    text(title, Type.TITLE_SMALL)
                    spacer(Space.XS)
                    text(body, Type.BODY_SMALL, colors.onSurfaceVariant)
                    actions?.let { addActions ->
                        val holder = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
                        holder.addActions()
                        if (holder.childCount > 0) {
                            spacer(Space.MD)
                            addView(
                                holder,
                                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                            )
                        }
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Space.MD) },
            )
        }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0 }
    }
}

private fun ScreenHost.pasteSession(storyId: String?) {
    val state = PatreonAccountScreenState
    state.pasteFeedback =
        when (val paste = PatreonSession.parsePaste(clipboardText())) {
            is PatreonSession.Paste.Found -> {
                saveSession(paste.sessionId, storyId, fromClipboard = true)
                return
            }
            PatreonSession.Paste.Empty -> "The clipboard is empty. Copy your sign-in in Firefox first (step 4)."
            PatreonSession.Paste.CookiesWithoutSession ->
                "Those cookies have no session_id. Sign in on patreon.com in Firefox, then export again."
            PatreonSession.Paste.Unrecognized -> "That doesn't look like a Patreon sign-in. Copy it again from Cookie-Editor."
        }
    showPatreonAccount(storyId)
}

private fun ScreenHost.showManualEntry(storyId: String?) {
    val view = FormLayout(app, dialog = true)
    val field = makeField(app, "", "session_id value", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    view.addItem(field, "Session cookie")
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("Enter session_id")
            .setView(scroll(view))
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val paste = PatreonSession.parsePaste(field.text.toString())
            if (paste !is PatreonSession.Paste.Found) {
                field.error = "Enter the session_id value"
                return@setOnClickListener
            }
            dialog.dismiss()
            saveSession(paste.sessionId, storyId, fromClipboard = false)
        }
    }
    dialog.show()
    dialog.applyFormStyle()
}

/**
 * Saves [sessionId] and confirms it with Patreon. A refused value never replaces a session that
 * was already saved; an accepted clipboard paste is wiped so the cookie doesn't linger there.
 */
private fun ScreenHost.saveSession(
    sessionId: String,
    storyId: String?,
    fromClipboard: Boolean,
) {
    val state = PatreonAccountScreenState
    val previous = PatreonSession.currentValue()
    PatreonSession.store(sessionId)
    state.pasteFeedback = null
    state.status = AccountStatus.Checking
    showPatreonAccount(storyId)
    scope.launch {
        val result = checkAccount()
        if (result is AccountStatus.Rejected) {
            if (previous != null && previous != sessionId) PatreonSession.restore(previous) else PatreonSession.clear()
            state.status = null
            state.pasteFeedback = "Patreon didn't accept that sign-in. Copy session_id again while signed in on patreon.com."
        } else {
            state.status = result
            if (result is AccountStatus.SignedIn) state.stepsExpanded = false
            if (fromClipboard) clearClipboard()
        }
        if (navigator.current == AppRoute.PatreonAccount(storyId)) showPatreonAccount(storyId)
    }
}

private fun ScreenHost.openInFirefox(url: String) {
    if (!FirefoxLauncher.open(app, url)) toast("Install Firefox first (step 1)")
}

private fun ScreenHost.refreshAccountStatus(storyId: String?) {
    PatreonAccountScreenState.status = AccountStatus.Checking
    scope.launch {
        PatreonAccountScreenState.status = checkAccount()
        if (navigator.current == AppRoute.PatreonAccount(storyId)) showPatreonAccount(storyId)
    }
}

private suspend fun ScreenHost.checkAccount(): AccountStatus =
    try {
        AccountStatus.SignedIn(PatreonApi(app.appContainer.network).currentUser())
    } catch (error: CancellationException) {
        throw error
    } catch (error: HttpNetworkException) {
        if (error.statusCode in setOf(401, 403)) AccountStatus.Rejected else AccountStatus.Unreachable("HTTP ${error.statusCode}")
    } catch (
        @Suppress("TooGenericExceptionCaught") error: Exception,
    ) {
        AccountStatus.Unreachable(error.message ?: error.javaClass.simpleName)
    }
