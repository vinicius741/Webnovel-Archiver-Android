package com.vinicius741.webnovelarchiver.feature.settings

import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.repository.clearTtsSession
import com.vinicius741.webnovelarchiver.data.repository.getTtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsProfile
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsEngineCatalog
import com.vinicius741.webnovelarchiver.tts.TtsEngineInfo
import com.vinicius741.webnovelarchiver.tts.TtsEnginePlanning
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsProfilePlanning
import com.vinicius741.webnovelarchiver.tts.TtsSampleFailure
import com.vinicius741.webnovelarchiver.tts.TtsSamplePreview
import com.vinicius741.webnovelarchiver.tts.VoiceInfo
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.labeledField
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.prompt
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.scrollTextInsideParent
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.showTtsVoiceDialog
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch
import java.util.UUID

/** Voice & Speech settings, reached from Settings and from the reader's settings menu. */
internal fun ScreenHost.showTtsSettings() {
    val ttsSettings = repository.ttsSettings.get()
    screen(route = AppRoute.TtsSettings, title = "Voice & Speech", scrollable = true) {
        repository.getTtsSession()?.let { session ->
            addView(
                card {
                    text("Saved TTS session", Type.TITLE_SMALL)
                    text(
                        "${session.chapterTitle} (sentence ${session.currentChunkIndex + 1})",
                        Type.BODY_SMALL,
                        ThemeManager.colors.onSurfaceVariant,
                    )
                    flow {
                        button("Resume TTS", Btn.TONAL, R.drawable.wna_play) {
                            TtsForegroundService.command(app, TtsForegroundService.ACTION_RESUME_SESSION)
                        }
                        button("Clear Session", Btn.TEXT, R.drawable.wna_delete) {
                            scope.launch {
                                repository.clearTtsSession()
                                showTtsSettings()
                            }
                        }
                    }
                },
            )
        }
        lateinit var draftSettings: () -> TtsSettings
        addView(ttsProfileCard(ttsSettings) { draftSettings() })
        val pitch = labeledField("Pitch", ttsSettings.pitch.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val rate = labeledField("Rate", ttsSettings.rate.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val engines = TtsEngineCatalog.installed(app)
        val phoneEngine = TtsEngineCatalog.phoneDefault(app)
        settingButtonRow("Engine", TtsEnginePlanning.label(ttsSettings.enginePackage, engines, phoneEngine), R.drawable.wna_settings) {
            showTtsEnginePicker(engines, phoneEngine) { draftSettings() }
        }
        settingButtonRow("Voice", ttsSettings.voiceIdentifier ?: "Engine default", R.drawable.wna_speaker) {
            showTtsVoicePicker { draftSettings() }
        }
        val contextReplay =
            ttsSwitch("Replay a little after long breaks", ttsSettings.contextReplay)
        val announceChapters =
            ttsSwitch("Say chapter titles", ttsSettings.announceChapters)
        val chapterSkipButtons =
            ttsSwitch("Headphone and lock-screen skips move whole chapters", ttsSettings.chapterSkipButtons)
        addView(
            makeText(context, "Pauses", Type.TITLE_SMALL, ThemeManager.colors.onSurface),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Space.LG)
            },
        )
        addView(
            makeText(
                context,
                "Real time: a pause lasts this long at any playback speed. The voice runs words together, " +
                    "so only Rate changes the gaps between them.",
                Type.BODY_SMALL,
                ThemeManager.colors.onSurfaceVariant,
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Space.XS)
                bottomMargin = dp(Space.MD)
            },
        )
        val phrasePause =
            pauseSlider("Extra at commas and phrases", ttsSettings.phrasePauseMs, TtsSettings.MAX_PHRASE_PAUSE_MS, "Off")
        val sentencePause =
            pauseSlider("Between sentences", ttsSettings.sentencePauseMs, TtsSettings.MAX_SENTENCE_PAUSE_MS)
        val paragraphPause =
            pauseSlider("Between paragraphs", ttsSettings.paragraphPauseMs, TtsSettings.MAX_PARAGRAPH_PAUSE_MS)
        val scenePause =
            pauseSlider("At scene breaks", ttsSettings.scenePauseMs, TtsSettings.MAX_SCENE_PAUSE_MS)
        draftSettings = {
            repository.ttsSettings.get().copy(
                pitch = SettingsValidation.ttsScalar(pitch.text.toString(), ttsSettings.pitch),
                rate = SettingsValidation.ttsScalar(rate.text.toString(), ttsSettings.rate),
                contextReplay = contextReplay.isChecked,
                announceChapters = announceChapters.isChecked,
                chapterSkipButtons = chapterSkipButtons.isChecked,
                phrasePauseMs = phrasePause(),
                sentencePauseMs = sentencePause(),
                paragraphPauseMs = paragraphPause(),
                scenePauseMs = scenePause(),
            )
        }
        addView(ttsSampleCard { draftSettings() })
        fullButton("Save TTS", Btn.FILLED, R.drawable.wna_check) {
            val saved = draftSettings()
            scope.launch {
                persistTtsSettings(saved)
                toast("TTS settings saved")
                // The profile line above reflects what is saved.
                showTtsSettings()
            }
        }
    }
}

/** Saves [settings] and hands them to the loaded chapter, which picks up a new voice in place. */
private suspend fun ScreenHost.persistTtsSettings(settings: TtsSettings) {
    repository.ttsSettings.save(settings)
    ttsEngine.applySettings(repository.ttsSettings.get())
}

/** A label on the left and a text button showing the current value on the right. */
private fun LinearLayout.settingButtonRow(
    label: String,
    value: String,
    icon: Int,
    onClick: () -> Unit,
) {
    row {
        addView(
            makeText(context, label, Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            makeButton(context, value, Btn.TEXT, icon) { onClick() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(Space.MD)
            },
        )
    }
}

/** Named voice setups: switch to one, save the screen's values as one, or delete one. */
private fun ScreenHost.ttsProfileCard(
    saved: TtsSettings,
    draft: () -> TtsSettings,
): View {
    val active = TtsProfilePlanning.active(saved)
    val status =
        when {
            active == null && saved.profiles.isEmpty() -> "Save these settings as a profile to come back to them later."
            active == null -> "These settings are not saved as a profile."
            TtsProfilePlanning.matches(active, saved) -> "Using \"${active.name}\"."
            else -> "Based on \"${active.name}\", with changes not saved to it."
        }
    return makeCard(app).apply {
        text("Profile", Type.TITLE_SMALL)
        text(status, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
            .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(Space.XS) }
        flow {
            if (saved.profiles.isNotEmpty()) {
                button("Switch", Btn.TONAL, R.drawable.wna_bookmark) { showTtsProfileSwitcher(saved) }
            }
            button("Save as profile", Btn.TEXT, R.drawable.wna_check) { saveTtsProfile(active, draft) }
            if (saved.profiles.isNotEmpty()) {
                button("Delete", Btn.TEXT, R.drawable.wna_delete) { showTtsProfileDeleter(saved) }
            }
        }
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.MD)
            }
    }
}

private fun ScreenHost.showTtsProfileSwitcher(saved: TtsSettings) {
    showStyledOptionsDialog(
        "Switch profile",
        saved.profiles.map { profile ->
            val label = if (profile.id == saved.activeProfileId) "\u2713  ${profile.name}" else profile.name
            OptionsDialogItem.Option(label) {
                scope.launch {
                    persistTtsSettings(TtsProfilePlanning.apply(repository.ttsSettings.get(), profile))
                    toast("Using profile \"${profile.name}\"")
                    showTtsSettings()
                }
            }
        },
    )
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
    showStyledOptionsDialog(
        "Delete profile",
        saved.profiles.map { profile ->
            OptionsDialogItem.Option(profile.name, destructive = true) {
                confirm("Delete the profile \"${profile.name}\"? Your current settings stay as they are.", "Delete") {
                    scope.launch {
                        repository.ttsSettings.save(TtsProfilePlanning.delete(repository.ttsSettings.get(), profile.id))
                        toast("Deleted profile \"${profile.name}\"")
                        showTtsSettings()
                    }
                }
            }
        },
    )
}

/** Picks which installed speech engine the app speaks with; its voices differ, so the voice resets. */
private fun ScreenHost.showTtsEnginePicker(
    engines: List<TtsEngineInfo>,
    phoneEngine: String?,
    draft: () -> TtsSettings,
) {
    val selected = repository.ttsSettings.get().enginePackage

    fun option(
        packageName: String?,
        label: String,
    ) = OptionsDialogItem.Option(if (packageName == selected) "\u2713  $label" else label) {
        if (packageName == selected) return@Option
        scope.launch {
            persistTtsSettings(draft().copy(enginePackage = packageName, voiceIdentifier = null))
            toast("Engine changed. Pick a voice from it, or keep its default.")
            showTtsSettings()
        }
    }
    showStyledOptionsDialog(
        "Speech engine",
        listOf(option(null, TtsEnginePlanning.label(null, engines, phoneEngine))) +
            engines.map { option(it.packageName, it.label) },
    )
}

private const val PAUSE_STEP_MS = 50L
private const val MS_PER_SECOND = 1_000.0
private var sampleTextDraft: String? = null

private val DEFAULT_SAMPLE_TEXT =
    """
    Mara pushed the door open, held her breath, and listened. Nothing moved in the hall. She took one step, then another.

    "The lamp is still warm," Tobin whispered. "Someone was here."

    * * *

    By morning, the rain had stopped. They left the city before anyone thought to look for them.
    """.trimIndent()

/** A labeled pause slider; the returned function reads its current value. */
private fun LinearLayout.pauseSlider(
    label: String,
    valueMs: Long,
    maxMs: Long,
    zeroLabel: String = "None",
): () -> Long {
    val colors = ThemeManager.colors

    fun describe(ms: Long): String =
        when {
            ms == 0L -> zeroLabel
            ms < MS_PER_SECOND -> "$ms ms"
            else -> String.format(java.util.Locale.ROOT, "%.2f s", ms / MS_PER_SECOND)
        }
    val current = makeText(context, describe(valueMs), Type.LABEL_MEDIUM, colors.onSurface).apply { gravity = Gravity.END }
    row {
        addView(
            makeText(context, label, Type.LABEL_MEDIUM, colors.onSurfaceVariant),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(current)
    }
    val slider =
        SeekBar(context).apply {
            contentDescription = label
            max = (maxMs / PAUSE_STEP_MS).toInt()
            progress = (valueMs / PAUSE_STEP_MS).toInt()
            progressTintList = ColorStateList.valueOf(colors.primary)
            thumbTintList = ColorStateList.valueOf(colors.primary)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        current.text = describe(progress * PAUSE_STEP_MS)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                },
            )
        }
    addView(
        slider,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(Space.XS)
            bottomMargin = context.dp(Space.SM)
        },
    )
    return { slider.progress * PAUSE_STEP_MS }
}

/** Editable text played with the unsaved values above, so pauses can be judged by ear before saving. */
private fun ScreenHost.ttsSampleCard(draft: () -> TtsSettings): View {
    var preview: TtsSamplePreview? = null
    var playing = false
    lateinit var playButton: Button
    return makeCard(app).apply {
        text("Try it", Type.TITLE_SMALL)
        text(
            "Plays with the voice, rate, pitch and pauses set on this screen, saved or not. " +
                "A blank line starts a paragraph; a line of * * * is a scene break.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        ).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(Space.XS) }
        val sample =
            labeledField(
                "Sample text",
                sampleTextDraft ?: DEFAULT_SAMPLE_TEXT,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            ).apply {
                isSingleLine = false
                gravity = Gravity.TOP or Gravity.START
                maxLines = SAMPLE_MAX_LINES
                scrollTextInsideParent()
            }
        flow {
            playButton =
                button("Play sample", Btn.TONAL, R.drawable.wna_play) {
                    if (playing) {
                        preview?.stop()
                        return@button
                    }
                    sampleTextDraft = sample.text.toString()
                    val player =
                        preview ?: TtsSamplePreview(
                            context = app,
                            onPlayingChanged = { isPlaying ->
                                playing = isPlaying
                                playButton.showSampleState(isPlaying)
                            },
                            onFailed = { failure -> toast(sampleFailureText(failure)) },
                        ).also { preview = it }
                    player.play(sample.text.toString(), draft())
                }
            button("Reset text", Btn.TEXT) {
                sampleTextDraft = null
                sample.setText(DEFAULT_SAMPLE_TEXT)
            }
        }
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    preview?.release()
                    preview = null
                }
            },
        )
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Space.SM)
                bottomMargin = dp(Space.MD)
            }
    }
}

private const val SAMPLE_MAX_LINES = 8

/** The sample button doubles as its own stop control while the sample is playing. */
private fun Button.showSampleState(playing: Boolean) {
    text = if (playing) "Stop playing" else "Play sample"
    val icon = context.tintedIcon(if (playing) R.drawable.wna_stop else R.drawable.wna_play, currentTextColor)
    setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
}

private fun sampleFailureText(failure: TtsSampleFailure): String =
    when (failure) {
        TtsSampleFailure.NothingToRead -> "There is nothing to read in the sample"
        TtsSampleFailure.AudioBusy -> "Another app is using the audio right now"
        TtsSampleFailure.Engine -> "The voice could not play the sample"
    }

private fun LinearLayout.ttsSwitch(
    label: String,
    checked: Boolean,
): SwitchCompat =
    SwitchCompat(context)
        .apply {
            text = label
            isChecked = checked
            setTextColor(ThemeManager.colors.onSurface)
            val colors = ThemeManager.colors
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(colors.primary, colors.onSurfaceVariant))
            trackTintList = ColorStateList(states, intArrayOf(colors.primary, colors.onSurfaceVariant))
        }.also {
            addView(
                it,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(Space.SM)
                },
            )
        }

/** [draft] is what the settings screen currently shows, kept when picking a voice rebuilds it. */
internal fun ScreenHost.showTtsVoicePicker(draft: () -> TtsSettings = { repository.ttsSettings.get() }) {
    val current = draft()
    val engine = TtsEnginePlanning.resolve(current.enginePackage, TtsEngineCatalog.installedPackages(app))
    toast("Loading voices")
    scope.launch {
        val voices = TtsEngineCatalog.voices(app, engine)
        when {
            voices == null -> toast("The speech engine did not start")
            voices.isEmpty() -> toast("This speech engine has no offline voices")
            else -> showTtsVoiceDialog(voices, current.voiceIdentifier) { voice -> saveTtsVoice(voice, draft()) }
        }
    }
}

private fun ScreenHost.saveTtsVoice(
    voice: VoiceInfo?,
    current: TtsSettings,
) {
    scope.launch {
        persistTtsSettings(current.copy(voiceIdentifier = voice?.identifier))
        showTtsSettings()
    }
}
