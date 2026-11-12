package com.vinicius741.webnovelarchiver.feature.narration

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.narration.NarrationServer
import com.vinicius741.webnovelarchiver.narration.NarrationServerPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.FormLayout
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.alert
import com.vinicius741.webnovelarchiver.ui.applyFormStyle
import com.vinicius741.webnovelarchiver.ui.chip
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.settingRow
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.styledDialogField
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where Kokoro chapters generate: this phone or one of the user's computers running
 * `scripts/narration-server` (see `docs/tts/remote-narration-servers.md`).
 */
internal fun LinearLayout.generateOnSection(
    host: ScreenHost,
    document: NarrationQueueDocument,
) {
    section("Generate on")
    val kokoro = KokoroVoices.isEngine(host.narration.currentVoice().engine)
    text(
        if (kokoro) {
            "Kokoro chapters can generate on one of your computers over Wi-Fi or Tailscale, several times faster than " +
                "on the phone and with the same voice. The audio is sent back and plays from this phone."
        } else {
            "Only Kokoro voices can generate on a computer. Your current voice always generates on this phone."
        },
        Type.BODY_SMALL,
        ThemeManager.colors.onSurfaceVariant,
    )
    spacer(Space.SM)
    val selected = document.generateOnServer?.id
    if (document.servers.isNotEmpty()) {
        flow {
            chip("This phone", selected = selected == null) {
                host.narrationAction("Could not save the setting") { setGenerateOn(null) }
            }
            document.servers.forEach { server ->
                chip(server.name, selected = selected == server.id) {
                    host.narrationAction("Could not save the setting") { setGenerateOn(server.id) }
                }
            }
        }
        spacer(Space.SM)
    }
    document.servers.forEach { server ->
        val summary = server.address + if (server.id == selected) " · generating here" else ""
        settingRow(R.drawable.wna_computer, server.name, summary) { host.showServerMenu(server, isSelected = server.id == selected) }
    }
    fullButton("Add a computer", Btn.TEXT, R.drawable.wna_add, bottomMarginDp = 0) { host.showServerDialog(null) }
}

private fun ScreenHost.showServerMenu(
    server: NarrationServer,
    isSelected: Boolean,
) {
    val items = mutableListOf<OptionsDialogItem>()
    if (!isSelected) {
        items += OptionsDialogItem.Option("Generate here") { narrationAction("Could not save the setting") { setGenerateOn(server.id) } }
    }
    items += OptionsDialogItem.Option("Test connection") { testServer(server) }
    items += OptionsDialogItem.Option("Edit") { showServerDialog(server) }
    items += OptionsDialogItem.Divider
    items +=
        OptionsDialogItem.Option("Remove", destructive = true) {
            confirm(
                "Remove ${server.name}? Chapters waiting to generate continue on this phone.",
                confirmLabel = "Remove",
            ) { narrationAction("Could not remove the computer") { removeServer(server.id) } }
        }
    showStyledOptionsDialog(server.name, items)
}

private fun ScreenHost.testServer(server: NarrationServer) {
    toast("Testing ${server.name}…")
    scope.launch {
        val result = withContext(Dispatchers.IO) { narration.testServer(server) }
        result.fold(
            onSuccess = { alert(server.name, it) },
            onFailure = { alert(server.name, it.message ?: "The test failed") },
        )
    }
}

/** Adds a computer ([existing] null) or edits one. Save keeps the dialog open on a form error. */
private fun ScreenHost.showServerDialog(existing: NarrationServer?) {
    val name = styledDialogField(existing?.name.orEmpty(), "MacBook")
    val address =
        styledDialogField(
            existing?.address.orEmpty(),
            "192.168.1.20 or 100.101.102.103",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
    val token =
        styledDialogField(
            existing?.token.orEmpty(),
            "abcd-efgh-jkmn",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )
    // An inline line rather than EditText.setError, whose popup covers the dialog's buttons.
    val errorLine = makeText(app, "", Type.BODY_SMALL, ThemeManager.colors.error).apply { visibility = View.GONE }
    val form =
        FormLayout(app, dialog = true).apply {
            addItem(
                makeText(
                    app,
                    "Run scripts/narration-server/setup.sh on the computer. It prints the address and token to enter here. " +
                        "The port is 8790 unless you changed it.",
                    Type.BODY_SMALL,
                    ThemeManager.colors.onSurfaceVariant,
                ),
            )
            addItem(name, "Name")
            addItem(address, "Address (LAN or Tailscale IP, optional :port)")
            addItem(token, "Token")
            addItem(errorLine)
        }
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle(if (existing == null) "Add a computer" else "Edit ${existing.name}")
            .setView(ScrollView(app).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
    dialog.show()
    dialog.applyFormStyle()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val values =
            Triple(
                name.text.toString().trim(),
                address.text.toString().trim(),
                NarrationServerPlanning.normalizeToken(token.text.toString()),
            )
        val error = NarrationServerPlanning.formError(values.first, values.second, values.third)
        if (error != null) {
            errorLine.text = error
            errorLine.visibility = View.VISIBLE
            return@setOnClickListener
        }
        dialog.dismiss()
        scope.launch {
            val saved =
                withContext(Dispatchers.IO) {
                    runCatching { narration.saveServer(existing?.id, values.first, values.second, values.third) }
                }
            saved.fold(
                onSuccess = { testServer(it) },
                onFailure = { toast("Could not save the computer") },
            )
        }
    }
}
