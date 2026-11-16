package com.vinicius741.webnovelarchiver.feature.settings

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.TtsProfile
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.narration.NarrationServer
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsProfilePlanning
import com.vinicius741.webnovelarchiver.tts.TtsSampleFailure
import com.vinicius741.webnovelarchiver.tts.TtsSamplePlanning
import com.vinicius741.webnovelarchiver.tts.TtsSamplePreview
import com.vinicius741.webnovelarchiver.tts.TtsSampleState
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelFiles
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.chip
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeField
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.prompt
import com.vinicius741.webnovelarchiver.ui.scrollTextInsideParent
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch
import java.util.UUID

// Voice & Speech's profile card (switch / save as / delete) and its "Try it" sample card.

/** Named voice setups: switch to one, save the screen's values as one, or delete one. */
internal fun ScreenHost.ttsProfileCard(
    saved: TtsSettings,
    draft: () -> TtsSettings,
): View {
    val colors = ThemeManager.colors
    val active = TtsProfilePlanning.active(saved)
    val status =
        when {
            active == null && saved.profiles.isEmpty() -> "Save these settings as a profile to come back to them later."
            active == null -> "These settings are not saved as a profile."
            TtsProfilePlanning.matches(active, saved) -> "Using \"${active.name}\"."
            else -> "Based on \"${active.name}\", with changes not saved to it."
        }
    return makeCard(app).apply {
        iconColumnRow(R.drawable.wna_tune, colors.primary, Gravity.CENTER_VERTICAL).addView(
            makeText(context, status, Type.BODY_MEDIUM, colors.onSurfaceVariant),
        )
        addView(
            WrapLayout(context).apply {
                horizontalSpacingDp = Spacing.SM
                verticalSpacingDp = Spacing.SM
                if (saved.profiles.isNotEmpty()) addView(makeButton(context, "Switch", Btn.SURFACE) { showTtsProfileSwitcher(saved) })
                addView(makeButton(context, "Save as profile", Btn.SURFACE) { saveTtsProfile(active, draft) })
                if (saved.profiles.isNotEmpty()) {
                    addView(makeButton(context, "Delete", Btn.TEXT) { showTtsProfileDeleter(saved) }.apply { setTextColor(colors.error) })
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    dp(Spacing.MD)
            },
        )
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

private fun ScreenHost.showTtsProfileSwitcher(saved: TtsSettings) {
    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    var sheet: BottomSheetHandle? = null
    saved.profiles.forEach { profile ->
        body.addView(
            makeSheetRow(
                app,
                profile.name,
                supporting = SettingsHubPlanning.rateLabel(profile.rate),
                iconRes = R.drawable.wna_record_voice,
                checked = profile.id == saved.activeProfileId,
            ) {
                sheet?.dismiss()
                scope.launch {
                    persistTtsSettings(TtsProfilePlanning.apply(repository.ttsSettings.get(), profile))
                    toast("Using profile \"${profile.name}\"")
                    showTtsSettings()
                }
            },
        )
    }
    sheet = showBottomSheet(app, title = "Switch profile", body = body)
}

/** Saves what the screen shows under a name; reusing a name updates that profile. */
private fun ScreenHost.saveTtsProfile(
    active: TtsProfile?,
    draft: () -> TtsSettings,
) {
    prompt("Profile name", active?.name.orEmpty()) { raw ->
        val name = TtsProfilePlanning.cleanName(raw)
        if (name == null) {
            toast("Enter a name for the profile")
            return@prompt
        }
        val current = draft()
        val save = {
            scope.launch {
                persistTtsSettings(TtsProfilePlanning.save(current, name) { UUID.randomUUID().toString() })
                toast("Saved profile \"$name\"")
                showTtsSettings()
            }
        }
        val existing = TtsProfilePlanning.named(current.profiles, name)
        if (existing != null && existing.id != active?.id) {
            confirm("Replace the profile \"${existing.name}\" with these settings?", "Replace") { save() }
        } else {
            save()
        }
    }
}

private fun ScreenHost.showTtsProfileDeleter(saved: TtsSettings) {
    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    var sheet: BottomSheetHandle? = null
    saved.profiles.forEach { profile ->
        body.addView(
            makeSheetRow(app, profile.name, iconRes = R.drawable.wna_delete, destructive = true) {
                sheet?.dismiss()
                confirm("Delete the profile \"${profile.name}\"? Your current settings stay as they are.", "Delete") {
                    scope.launch {
                        repository.ttsSettings.save(TtsProfilePlanning.delete(repository.ttsSettings.get(), profile.id))
                        toast("Deleted profile \"${profile.name}\"")
                        showTtsSettings(keepDraft = true)
                    }
                }
            },
        )
    }
    sheet = showBottomSheet(app, title = "Delete profile", body = body)
}

private var sampleTextDraft: String? = null

private val DEFAULT_SAMPLE_TEXT =
    """
    Mara pushed the door open, held her breath, and listened. Nothing moved in the hall. She took one step, then another.

    "The lamp is still warm," Tobin whispered. "Someone was here."

    * * *

    By morning, the rain had stopped. They left the city before anyone thought to look for them.
    """.trimIndent()

/**
 * Editable text played with the unsaved values above, so they can be judged by ear before saving. A
 * built-in model that is not on this phone is generated on the chosen computers first, then played.
 */
internal fun ScreenHost.ttsSampleCard(draft: () -> TtsSettings): View {
    var preview: TtsSamplePreview? = null
    var state: TtsSampleState = TtsSampleState.Idle
    lateinit var playButton: Button
    val colors = ThemeManager.colors
    val narrationState = narration.queueState.value
    val servers = narrationState.servers
    val chosen = {
        TtsSamplePlanning.chosenComputers(servers, sampleComputerIds, narrationState.generateOnIds)
    }
    val status =
        makeText(app, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
            setPadding(dp(Spacing.XS), dp(Spacing.SM), 0, 0)
            visibility = View.GONE
        }
    return makeCard(app).apply {
        val sample =
            makeField(
                context,
                sampleTextDraft ?: DEFAULT_SAMPLE_TEXT,
                "Sample text",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            ).apply {
                isSingleLine = false
                gravity = Gravity.TOP or Gravity.START
                maxLines = SAMPLE_MAX_LINES
                contentDescription = "Sample text"
                scrollTextInsideParent()
            }
        addView(sample, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(
            makeText(
                context,
                "A blank line starts a paragraph; a line of * * * is a scene break.",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply {
                setPadding(dp(Spacing.XS), dp(Spacing.SM), 0, 0)
            },
        )
        addSampleComputers(this, draft(), servers, chosen)
        playButton =
            makeButton(context, "Play sample", Btn.FILLED, R.drawable.wna_play) {
                if (state != TtsSampleState.Idle) {
                    preview?.stop()
                    return@makeButton
                }
                sampleTextDraft = sample.text.toString()
                val player =
                    preview ?: TtsSamplePreview(
                        context = app,
                        builtIns = app.appContainer.builtIns,
                        onStateChanged = { next ->
                            state = next
                            playButton.showSampleState(next)
                            status.showSampleStatus(next)
                        },
                        onFailed = { failure -> toast(sampleFailureText(failure)) },
                    ).also { preview = it }
                player.play(sample.text.toString(), draft(), chosen())
            }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(playButton)
                addView(
                    makeButton(context, "Reset text", Btn.TEXT) {
                        sampleTextDraft = null
                        sample.setText(DEFAULT_SAMPLE_TEXT)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart =
                            dp(Spacing.SM)
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    dp(Spacing.MD)
            },
        )
        addView(status)
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    preview?.release()
                    preview = null
                }
            },
        )
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/** The computers a sample is generated on, kept while the app runs; null follows Listen's Generate on. */
private var sampleComputerIds: Set<String>? = null

/**
 * For a built-in model that is not on this phone: which computers generate the sample (one or
 * several, which share its sentences), or how to make one available.
 */
private fun ScreenHost.addSampleComputers(
    card: LinearLayout,
    settings: TtsSettings,
    servers: List<NarrationServer>,
    chosen: () -> List<NarrationServer>,
) {
    val model = KokoroVoices.model(settings.enginePackage) ?: return
    val builtIns = app.appContainer.builtIns
    if (builtIns.isInstalled(model) && KokoroModelFiles.isDeviceSupported(model)) return
    val colors = ThemeManager.colors
    val context = card.context
    val hint =
        if (servers.isEmpty()) {
            val download = if (KokoroModelFiles.isDeviceSupported(model)) ", or download the model above" else ""
            "${model.family} is not on this phone. Add one of your computers in Listen › Setup to hear it$download."
        } else {
            "${model.family} is not on this phone, so the sample is generated on your computers first and plays when it is ready. " +
                "Choose one or more."
        }
    card.addView(
        makeText(context, hint, Type.BODY_SMALL, colors.onSurfaceVariant),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Spacing.MD)
        },
    )
    if (servers.isEmpty()) return
    val chips =
        WrapLayout(context).apply {
            horizontalSpacingDp = Spacing.SM
            verticalSpacingDp = Spacing.SM
        }

    fun bind() {
        chips.removeAllViews()
        val ids = chosen().map { it.id }.toSet()
        servers.forEach { server ->
            chips.chip(server.name, selected = server.id in ids) {
                sampleComputerIds = TtsSamplePlanning.toggle(chosen(), server.id)
                bind()
            }
        }
    }
    bind()
    card.addView(
        chips,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Spacing.SM)
        },
    )
}

private const val SAMPLE_MAX_LINES = 8

/** The sample button doubles as its own stop control while the sample generates or plays. */
private fun Button.showSampleState(state: TtsSampleState) {
    val busy = state != TtsSampleState.Idle
    text =
        when (state) {
            TtsSampleState.Idle -> "Play sample"
            is TtsSampleState.Generating -> "Cancel"
            TtsSampleState.Playing -> "Stop playing"
        }
    val icon = context.tintedIcon(if (busy) R.drawable.wna_stop else R.drawable.wna_play, currentTextColor)
    setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
}

private fun TextView.showSampleStatus(state: TtsSampleState) {
    if (state is TtsSampleState.Generating) {
        text = TtsSamplePlanning.progressLine(state)
        visibility = View.VISIBLE
    } else {
        visibility = View.GONE
    }
}

private fun sampleFailureText(failure: TtsSampleFailure): String =
    when (failure) {
        TtsSampleFailure.NothingToRead -> "There is nothing to read in the sample"
        TtsSampleFailure.AudioBusy -> "Another app is using the audio right now"
        TtsSampleFailure.Engine -> "The voice could not play the sample"
        is TtsSampleFailure.NoComputer -> "${failure.model} is not on this phone. Add a computer in Listen › Setup to hear it."
        is TtsSampleFailure.Computers -> failure.message.ifBlank { "The computers could not generate the sample" }
    }
