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
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.FormLayout
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyFormStyle
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
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

internal fun patreonAccountSummary(): String =
    if (PatreonSession.isPresent()) "Signed in" else "Not signed in · set up early-access chapters"

private const val COOKIE_EDITOR_URL = "https://addons.mozilla.org/android/addon/cookie-editor/"
private const val PATREON_LOGIN_URL = "https://www.patreon.com/login"

/** The Patreon sign-in: the shared cookie store, Patreon's API, Firefox and the clipboard. */
private class HostPatreonAccountData(
    private val host: ScreenHost,
) : PatreonAccountData {
    override fun session(): String? = PatreonSession.currentValue()

    override fun storeSession(sessionId: String) = PatreonSession.store(sessionId)

    override fun restoreSession(sessionId: String) = PatreonSession.restore(sessionId)

    override fun clearSession() = PatreonSession.clear()

    override fun parsePaste(text: String?): PatreonSession.Paste = PatreonSession.parsePaste(text)

    override suspend fun currentUser(): PatreonAccount = PatreonApi(host.app.appContainer.network).currentUser()

    override fun firefoxInstalled(): Boolean = FirefoxLauncher.installedPackage(host.app) != null

    override fun earlyAccessTitle(storyId: String): String? =
        host.repository
            .story(storyId)
            ?.takeIf { it.patreonEarlyAccess != null }
            ?.title

    override fun clearClipboard() = host.clearClipboard()
}

/** The account page's model, kept while it is in the history. */
private fun ScreenHost.patreonAccountModel(storyId: String?): PatreonAccountScreenModel {
    val route = AppRoute.PatreonAccount(storyId)
    return screenModels.get(route.stableKey) { PatreonAccountScreenModel(HostPatreonAccountData(this), storyId) }
}

/** Re-reads which setup steps are done when the account page is on screen (returning from Firefox). */
internal fun ScreenHost.refreshPatreonAccount() {
    val route = navigator.current as? AppRoute.PatreonAccount ?: return
    screenModels.find<PatreonAccountScreenModel>(route.stableKey)?.refresh()
}

/**
 * Patreon sign-in guide. Google sign-in cannot run inside the app, so the user signs in with
 * Firefox, copies the `session_id` cookie with the Cookie-Editor add-on, and pastes it here in one
 * tap. The value is never displayed, logged, or stored outside the shared cookie store, and the
 * clipboard is cleared once Patreon accepts it. Built from [PatreonAccountScreenModel]'s state; any
 * change rebuilds the page.
 */
internal fun ScreenHost.showPatreonAccount(storyId: String? = null) {
    screen(route = AppRoute.PatreonAccount(storyId), title = "Patreon account", scrollable = true) {
        // screen() navigates to this route before it prunes the store, so the model is kept.
        val model = patreonAccountModel(storyId)
        val state = model.refresh()
        bindWhileAttached(scope, model.state) { next -> if (next != state) showPatreonAccount(storyId) }
        capSettingsWidth(this)
        buildStatusCard(this, model, state, storyId)
        if (state.signedIn && !state.stepsExpanded) {
            fullButton("Sign in with a different account", Btn.TEXT, bottomMarginDp = Space.SM) { model.expandSteps() }
            return@screen
        }
        val firefox = state.firefoxInstalled
        section(if (state.sessionPresent) "Sign in again" else "Set up in 5 steps")
        addView(
            setupStep(
                this,
                1,
                "Install Firefox",
                if (firefox) {
                    "Firefox is installed."
                } else {
                    "Patreon's Google sign-in doesn't work inside other apps, so you'll sign in with Firefox."
                },
                done = firefox,
            ) {
                if (!firefox) button("Get Firefox", Btn.TONAL, R.drawable.wna_download) { FirefoxLauncher.openStoreListing(app) }
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
                fullButton("Paste from clipboard", Btn.FILLED, R.drawable.wna_paste, bottomMarginDp = 0) { model.paste(clipboardText()) }
                state.pasteFeedback?.let { feedback ->
                    spacer(Space.SM)
                    text(feedback, Type.BODY_SMALL, ThemeManager.colors.error)
                }
                fullButton("Enter it manually", Btn.TEXT, topMarginDp = Space.XS, bottomMarginDp = 0) { showManualEntry(model) }
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
    model: PatreonAccountScreenModel,
    state: PatreonAccountUiState,
    storyId: String?,
): LinearLayout {
    val colors = ThemeManager.colors
    val status = state.status
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
    val tint = if (status is AccountStatus.Rejected) colors.error else colors.secondary
    return parent.tintedCard(tint) {
        iconColumnRow(R.drawable.wna_favorite, tint).apply {
            addView(makeEyebrow(context, "Patreon", tint))
            addView(makeText(context, title, Type.TITLE_SMALL, colors.onSurface).apply { textSize = STATUS_TITLE_SP })
            body?.let { addView(makeText(context, it, Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(0, dp(2), 0, 0) }) }
        }
        val title = state.earlyAccessTitle
        if (status is AccountStatus.SignedIn && storyId != null && title != null) {
            fullButton("Check “$title” now", Btn.FILLED, bottomMarginDp = 0) {
                val story = repository.story(storyId) ?: return@fullButton
                showDetails(story.id)
                syncStory(story)
            }
        }
        if (status !is AccountStatus.SignedOut && status !is AccountStatus.Checking) {
            row {
                if (status !is AccountStatus.SignedIn) {
                    button("Check again", Btn.TEXT) { model.checkAgain() }
                }
                button("Sign out", Btn.TEXT) {
                    confirm("Sign out of Patreon? Stored early-access chapters stay; new ones stop arriving.", confirmLabel = "Sign out") {
                        model.signOut()
                    }
                }
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
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

private fun ScreenHost.showManualEntry(model: PatreonAccountScreenModel) {
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
            if (model.enterManually(field.text.toString())) dialog.dismiss() else field.error = "Enter the session_id value"
        }
    }
    dialog.show()
    dialog.applyFormStyle()
}

private fun ScreenHost.openInFirefox(url: String) {
    if (!FirefoxLauncher.open(app, url)) toast("Install Firefox first (step 1)")
}

private const val STATUS_TITLE_SP = 15f
