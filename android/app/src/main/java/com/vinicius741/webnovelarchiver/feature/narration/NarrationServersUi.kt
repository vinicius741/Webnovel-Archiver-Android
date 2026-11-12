package com.vinicius741.webnovelarchiver.feature.narration

import android.app.AlertDialog
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.narration.NarrationServer
import com.vinicius741.webnovelarchiver.narration.NarrationServerPlanning
import com.vinicius741.webnovelarchiver.narration.NarrationServerStatus
import com.vinicius741.webnovelarchiver.narration.NarrationServerTone
import com.vinicius741.webnovelarchiver.narration.locationKey
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.FormLayout
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.StepperHandle
import com.vinicius741.webnovelarchiver.ui.StepperSpec
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.alert
import com.vinicius741.webnovelarchiver.ui.applyFormStyle
import com.vinicius741.webnovelarchiver.ui.chip
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.stepperRow
import com.vinicius741.webnovelarchiver.ui.styledDialogField
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The Listen hub's Setup section. Where Kokoro chapters generate: this phone or one of the user's
 * computers running `scripts/narration-server` (see `docs/tts/remote-narration-servers.md`), then
 * how many chapters stay generated ahead of the listener. Returns the computers' rows, which the
 * screen updates in place as online checks come back.
 */
internal fun LinearLayout.setupSection(
    host: ScreenHost,
    document: NarrationQueueDocument,
): ServerRows {
    val rows = generateOnSection(host, document)
    keepAheadSection(host, document.keepAhead)
    return rows
}

private fun LinearLayout.generateOnSection(
    host: ScreenHost,
    document: NarrationQueueDocument,
): ServerRows {
    listenSectionLabel("Generate on")
    val colors = ThemeManager.colors
    val kokoro = KokoroVoices.isEngine(host.narration.currentVoice().engine)
    val selected = document.generateOnServer?.id
    val card =
        makeCard(context).apply {
            if (document.servers.isNotEmpty()) {
                flow(spacing = Space.SM) {
                    chip("This phone", selected = selected == null) {
                        host.narrationAction("Could not save the setting") { setGenerateOn(null) }
                    }
                    document.servers.forEach { server ->
                        chip(server.name, selected = selected == server.id) {
                            host.narrationAction("Could not save the setting") { setGenerateOn(server.id) }
                        }
                    }
                }
                spacer(Space.SM + 2)
            }
            text(generateOnHint(kokoro, document.servers.isEmpty(), selected != null), Type.BODY_SMALL, colors.onSurfaceVariant)
        }
    addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    val rows = ServerRows(host, document.servers, selected)
    (rows.view.layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(Space.SM + 2)
    addView(rows.view)
    rows.bind(host.narration.serverStatus.value)
    return rows
}

/**
 * The computers' rows: name, whether it is online and how it is reached, its addresses, and an
 * "Add a computer" row. Rebinds in place as online checks come back.
 */
internal class ServerRows(
    private val host: ScreenHost,
    private val servers: List<NarrationServer>,
    private val selected: String?,
) {
    val view: LinearLayout = makeListGroup(host.app)
    private var shown: Map<String, NarrationServerStatus?>? = null

    /** Redraws the rows when a computer's status changed; a status made for older settings counts as none yet. */
    fun bind(statuses: Map<String, NarrationServerStatus>) {
        val current = servers.associate { server -> server.id to statuses[server.id]?.takeIf { it.locationKey == server.locationKey } }
        if (current == shown) return
        shown = current
        view.removeAllViews()
        val context = host.app
        servers.forEach { server ->
            val generatingHere = server.id == selected
            val more =
                context.iconButton(R.drawable.wna_more_vert, "${server.name} options", ThemeManager.colors.onSurfaceVariant) {
                    host.showServerMenu(server, isSelected = generatingHere)
                }
            view.addView(
                makeListRow(
                    context,
                    server.name,
                    summary(server, current[server.id], generatingHere),
                    leadIcon = R.drawable.wna_computer,
                    trailing = more,
                ) {
                    host.showServerMenu(server, isSelected = generatingHere)
                },
            )
        }
        view.addView(makeListRow(context, "Add a computer", leadIcon = R.drawable.wna_add) { host.showServerDialog(null) })
    }

    private fun summary(
        server: NarrationServer,
        status: NarrationServerStatus?,
        generatingHere: Boolean,
    ): CharSequence {
        val colors = ThemeManager.colors
        val color =
            when (NarrationServerPlanning.tone(status)) {
                NarrationServerTone.CHECKING -> colors.onSurfaceVariant
                NarrationServerTone.ONLINE -> colors.tertiary
                NarrationServerTone.WARNING -> colors.secondary
                NarrationServerTone.OFFLINE -> colors.error
            }
        return SpannableStringBuilder()
            .append("● ", ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append(NarrationServerPlanning.statusLine(status, generatingHere))
            .append("\n")
            .append(NarrationServerPlanning.addressesLine(server))
    }
}

private fun generateOnHint(
    kokoro: Boolean,
    noServers: Boolean,
    onServer: Boolean,
): String =
    when {
        !kokoro -> "Only Kokoro voices can generate on a computer. Your current voice generates on this phone."
        noServers -> "Generating on this phone. Add one of your computers to generate faster over Wi-Fi or Tailscale."
        onServer -> "A computer generates faster and saves battery. The audio plays from this phone."
        else -> "Uses the Kokoro voice on this phone."
    }

private fun LinearLayout.keepAheadSection(
    host: ScreenHost,
    keepAhead: Int,
) {
    listenSectionLabel("Keep ahead while listening")
    val summary = makeText(context, keepAheadSummary(keepAhead), Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
    val list = makeListGroup(context)
    val holder =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(Space.LG), 0, context.dp(Space.SM), 0)
        }
    lateinit var stepper: StepperHandle
    // A held stepper ticks many times a second: writes run one after another and only the latest
    // value is stored, and a failed write puts the stored value back on screen.
    var latest = 0
    var writes: Job? = null
    stepper =
        holder.stepperRow(
            "Chapters ahead",
            "Generated after the chapter you're hearing",
            keepAhead.toDouble(),
            StepperSpec(
                step = 1.0,
                min = 0.0,
                max = NarrationPlanning.KEEP_AHEAD_MAX.toDouble(),
                zeroLabel = "Off",
                label = "Chapters ahead",
            ),
        ) { value ->
            val count = value.toInt()
            summary.text = keepAheadSummary(count)
            val ticket = ++latest
            val previous = writes
            writes =
                host.scope.launch {
                    previous?.join()
                    if (ticket != latest) return@launch
                    val failure = withContext(Dispatchers.IO) { runCatching { host.narration.setKeepAhead(count) } }.exceptionOrNull()
                    if (failure != null) {
                        Timber.e(failure, "Could not save the keep-ahead setting")
                        host.toast("Could not save the setting")
                        if (ticket == latest) {
                            val stored = host.narration.queueState.value.keepAhead
                            stepper.show(stored.toDouble())
                            summary.text = keepAheadSummary(stored)
                        }
                    }
                }
            null
        }
    list.addView(holder)
    addView(list)
    addView(summary.apply { setPadding(context.dp(Space.XS), context.dp(Space.SM + 2), context.dp(Space.XS), 0) })
}

private fun keepAheadSummary(count: Int): String =
    when (count) {
        0 -> "Chapters generate only when you queue them."
        1 -> "Keeps the next chapter generated while a story plays."
        else -> "Keeps the next $count chapters generated while a story plays."
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
    items += OptionsDialogItem.Option("Rename or edit") { showServerDialog(server) }
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
internal fun ScreenHost.showServerDialog(existing: NarrationServer?) {
    val name = styledDialogField(existing?.name.orEmpty(), "MacBook")
    val address =
        styledDialogField(
            existing?.address.orEmpty(),
            "192.168.1.20",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
    val tailscale =
        styledDialogField(
            existing?.tailscaleAddress.orEmpty(),
            "100.101.102.103",
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
                    "Run scripts/narration-server/setup.sh on the computer. It prints the addresses and token to enter here. " +
                        "The local network is tried first, then Tailscale. The port is 8790 unless you changed it.",
                    Type.BODY_SMALL,
                    ThemeManager.colors.onSurfaceVariant,
                ),
            )
            addItem(name, "Name")
            addItem(address, "Local network address")
            addItem(tailscale, "Tailscale address (optional)")
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
        val nameText = name.text.toString().trim()
        val addressText = address.text.toString().trim()
        val tailscaleText = tailscale.text.toString().trim()
        val tokenText = NarrationServerPlanning.normalizeToken(token.text.toString())
        val error = NarrationServerPlanning.formError(nameText, addressText, tailscaleText, tokenText)
        if (error != null) {
            errorLine.text = error
            errorLine.visibility = View.VISIBLE
            return@setOnClickListener
        }
        dialog.dismiss()
        scope.launch {
            val saved =
                withContext(Dispatchers.IO) {
                    runCatching { narration.saveServer(existing?.id, nameText, addressText, tailscaleText, tokenText) }
                }
            saved.fold(
                onSuccess = { testServer(it) },
                onFailure = { toast("Could not save the computer") },
            )
        }
    }
}
