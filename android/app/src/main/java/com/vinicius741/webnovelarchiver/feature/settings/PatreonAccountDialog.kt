package com.vinicius741.webnovelarchiver.feature.settings

import android.app.AlertDialog
import android.text.InputType
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.PatreonApi
import com.vinicius741.webnovelarchiver.source.PatreonSession
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.ui.FormLayout
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyFormStyle
import com.vinicius741.webnovelarchiver.ui.makeField
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.scroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun patreonAccountSummary(): String = if (PatreonSession.isPresent()) "Session saved" else "Not signed in"

/**
 * Patreon sign-in for early-access chapters. Google sign-in cannot run inside the app, so the user
 * signs in with a browser and pastes that browser's `session_id` cookie here. The value is masked,
 * never displayed back, and stored only in the shared cookie store.
 */
internal fun ScreenHost.showPatreonAccountDialog(onChanged: () -> Unit) {
    val view = FormLayout(app, dialog = true)
    val status = makeText(app, "", Type.BODY_MEDIUM, ThemeManager.colors.onSurface)
    view.addItem(status, "Status")
    val sessionField =
        makeField(app, "", "Paste the session_id value", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    view.addItem(sessionField, "Session cookie")
    view.addItem(
        makeText(
            app,
            "Sign in on patreon.com in a browser (Google works), then copy its session_id cookie, " +
                "for example with Firefox and the Cookie-Editor add-on.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        ),
    )

    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("Patreon Account")
            .setView(scroll(view))
            .setPositiveButton("Save", null)
            .setNeutralButton("Sign out", null)
            .setNegativeButton("Close", null)
            .create()

    fun verify() {
        if (!PatreonSession.isPresent()) {
            status.text = "Not signed in"
            return
        }
        status.text = "Checking with Patreon..."
        scope.launch { status.text = describeAccount() }
    }

    dialog.setOnShowListener {
        verify()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!PatreonSession.store(sessionField.text.toString())) {
                status.text = "Paste the session_id value first"
                return@setOnClickListener
            }
            sessionField.setText("")
            onChanged()
            verify()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            PatreonSession.clear()
            onChanged()
            verify()
        }
    }
    dialog.show()
    dialog.applyFormStyle()
}

private suspend fun ScreenHost.describeAccount(): String =
    try {
        val account = PatreonApi(app.appContainer.network).currentUser()
        val memberships = account.memberships.joinToString { it.name }.ifBlank { "no paid memberships" }
        "Signed in as ${account.name ?: "Patreon user"} · $memberships"
    } catch (error: CancellationException) {
        throw error
    } catch (error: HttpNetworkException) {
        if (error.statusCode in
            setOf(401, 403)
        ) {
            "Patreon rejected this session. Paste a new one."
        } else {
            "Couldn't reach Patreon (HTTP ${error.statusCode})"
        }
    } catch (error: Exception) {
        "Couldn't reach Patreon: ${error.message ?: error.javaClass.simpleName}"
    }
