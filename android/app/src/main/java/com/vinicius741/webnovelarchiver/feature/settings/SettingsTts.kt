package com.vinicius741.webnovelarchiver.feature.settings

import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.repository.clearTtsSession
import com.vinicius741.webnovelarchiver.data.repository.getTtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsSampleFailure
import com.vinicius741.webnovelarchiver.tts.TtsSamplePreview
import com.vinicius741.webnovelarchiver.tts.VoiceInfo
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.flow
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.labeledField
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.scrollTextInsideParent
import com.vinicius741.webnovelarchiver.ui.showTtsVoiceDialog
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

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
        val pitch = labeledField("Pitch", ttsSettings.pitch.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val rate = labeledField("Rate", ttsSettings.rate.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val voiceLabel = ttsSettings.voiceIdentifier ?: "System default"
        lateinit var draftSettings: () -> TtsSettings
        row {
            addView(
                makeText(context, "Voice", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val voiceBtn = makeButton(context, voiceLabel, Btn.TEXT, R.drawable.wna_speaker) { showTtsVoicePicker { draftSettings() } }
            addView(
                voiceBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(Space.MD)
                },
            )
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
        fullButton("Save TTS", Btn.FILLED, R.drawable.wna_check, bottomMarginDp = MINI_PLAYER_CLEARANCE_DP) {
            val saved = draftSettings()
            scope.launch {
                repository.ttsSettings.save(saved)
                ttsEngine.applyPacing(saved)
                toast("TTS settings saved")
            }
        }
    }
}

/** Keeps Save reachable at the end of the scroll while the mini-player floats over the screen. */
private const val MINI_PLAYER_CLEARANCE_DP = 88
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
    lateinit var stopButton: View
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
            button("Play sample", Btn.TONAL, R.drawable.wna_play) {
                sampleTextDraft = sample.text.toString()
                val player =
                    preview ?: TtsSamplePreview(
                        context = app,
                        onPlayingChanged = { playing -> stopButton.visibility = if (playing) View.VISIBLE else View.GONE },
                        onFailed = { failure -> toast(sampleFailureText(failure)) },
                    ).also { preview = it }
                player.play(sample.text.toString(), draft())
            }
            stopButton =
                button("Stop", Btn.TEXT, R.drawable.wna_close) { preview?.stop() }
                    .apply { visibility = View.GONE }
            button("Reset text", Btn.TEXT) {
                sampleTextDraft = null
                sample.setText(DEFAULT_SAMPLE_TEXT)
            }
        }.also { buttons ->
            (buttons.layoutParams as LinearLayout.LayoutParams).topMargin = dp(Space.MD)
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
    val currentVoice = repository.ttsSettings.get().voiceIdentifier

    fun showLoadedVoices(voices: List<VoiceInfo>) {
        if (voices.isEmpty()) {
            toast("No local TTS voices available yet")
            return
        }
        showTtsVoiceDialog(voices, currentVoice) { voice -> saveTtsVoice(voice, draft()) }
    }

    val voices = ttsEngine.availableVoices()
    if (voices.isEmpty()) {
        lateinit var listener: (List<VoiceInfo>) -> Unit
        listener = { loadedVoices ->
            ttsEngine.removeVoiceAvailabilityListener(listener)
            app.runOnUiThread { showLoadedVoices(loadedVoices) }
        }
        ttsEngine.addVoiceAvailabilityListener(listener)
        toast("Loading local TTS voices")
        return
    }
    showLoadedVoices(voices)
}

private fun ScreenHost.saveTtsVoice(
    voice: VoiceInfo?,
    current: TtsSettings,
) {
    scope.launch {
        repository.ttsSettings.save(current.copy(voiceIdentifier = voice?.identifier))
        showTtsSettings()
    }
}
