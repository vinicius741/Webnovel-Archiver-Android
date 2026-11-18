package com.vinicius741.webnovelarchiver.feature.narration

import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.narration.GenerateOnChoice
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.narration.NarrationServer
import com.vinicius741.webnovelarchiver.narration.NarrationServerPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.chip
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text

// Generate on: the chips that choose where Kokoro chapters generate (this phone or the user's
// computers), shown in the Listening settings with the computers' rows and in the Listen queue.

/** The Listening settings' Generate on card, followed by the computers' rows. */
internal fun LinearLayout.generateOnSection(
    host: ScreenHost,
    document: NarrationQueueDocument,
): ServerRows {
    listenSectionLabel("Generate on")
    val colors = ThemeManager.colors
    val model = KokoroVoices.model(host.narration.currentVoice().engine)
    val kokoro = model != null
    // A built-in model that is not on this phone can only generate on a computer.
    val missingOnPhone =
        model
            ?.takeUnless {
                host.app.appContainer.builtIns
                    .isInstalled(it)
            }?.family
    val choice = document.generateOnChoice
    val card =
        makeCard(context).apply {
            if (document.servers.isNotEmpty()) {
                generateOnChips(host, document)
                spacer(Space.SM + 2)
            }
            text(
                generateOnHint(kokoro, document.servers.isEmpty(), document.generateOnServers, choice, missingOnPhone),
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            )
        }
    addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    val rows = ServerRows(host, document.servers, choice)
    (rows.view.layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(Space.SM + 2)
    addView(rows.view)
    rows.bind(host.narration.serverStatus.value)
    return rows
}

/** The Generate on chips: "This phone" and each computer; a tap saves the choice and the screen rebuilds. */
private fun LinearLayout.generateOnChips(
    host: ScreenHost,
    document: NarrationQueueDocument,
) {
    val choice = document.generateOnChoice
    flow(spacing = Space.SM) {
        chip("This phone", selected = choice.phoneChosen) { host.chooseGenerateOn(choice, null) }
        document.servers.forEach { server ->
            chip(server.name, selected = server.id in choice.serverIds) { host.chooseGenerateOn(choice, server.id) }
        }
    }
}

/**
 * The Listen queue's Generate on row, so where chapters generate can change without leaving the
 * queue. Shown only when there is a computer to choose and the current voice can generate on one.
 */
internal fun LinearLayout.queueGenerateOnSection(
    host: ScreenHost,
    document: NarrationQueueDocument,
) {
    if (document.servers.isEmpty()) return
    if (KokoroVoices.model(host.narration.currentVoice().engine) == null) return
    listenSectionLabel("Generate on")
    generateOnChips(host, document)
}

/** Saves what a tap on a Generate on chip chooses ([serverId] null is "This phone"). */
internal fun ScreenHost.chooseGenerateOn(
    current: GenerateOnChoice,
    serverId: String?,
) {
    val next = NarrationServerPlanning.tapGenerateOn(current, serverId)
    if (next == current) return
    narrationAction("Could not save the setting") { setGenerateOn(next.serverIds, next.phoneFallback) }
}

private fun generateOnHint(
    kokoro: Boolean,
    noServers: Boolean,
    chosen: List<NarrationServer>,
    choice: GenerateOnChoice,
    /** The current built-in model's name when it is not downloaded to this phone. */
    missingOnPhone: String?,
): String =
    when {
        !kokoro -> "Only the built-in voices (Kokoro, Phonon) can generate on a computer. Your current voice generates on this phone."
        missingOnPhone != null && noServers ->
            "$missingOnPhone is not on this phone. Add one of your computers to generate with it, or download it above."
        missingOnPhone != null && chosen.isEmpty() ->
            "$missingOnPhone is not on this phone. Choose a computer to generate with it, or download it above."
        noServers -> "Generating on this phone. Add one of your computers to generate faster over Wi-Fi or Tailscale."
        chosen.isEmpty() -> "Uses the built-in voice on this phone. Choose one or more computers to generate faster."
        else -> {
            val who =
                if (chosen.size == 1) {
                    "A computer generates faster and saves battery."
                } else {
                    "${chosen.joinToString(" and ") { it.name }} share each chapter, so it generates faster."
                }
            val fallback =
                if (choice.phoneFallback && missingOnPhone != null) {
                    " This phone can take over only after $missingOnPhone is downloaded above."
                } else if (choice.phoneFallback) {
                    " This phone takes over while ${if (chosen.size == 1) "it can't generate" else "none of them can generate"}."
                } else {
                    ""
                }
            "$who The audio plays from this phone.$fallback"
        }
    }
