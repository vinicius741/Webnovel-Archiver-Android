package com.vinicius741.webnovelarchiver.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ScreenHost
import com.vinicius741.webnovelarchiver.core.Chapter
import com.vinicius741.webnovelarchiver.core.DisplayPreferences
import com.vinicius741.webnovelarchiver.core.PreferenceNormalization
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.showSettings
import com.vinicius741.webnovelarchiver.showTtsSettings
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import kotlin.math.roundToInt

/**
 * Styled panel surface matching [showStyledOptionsDialog]: rounded surface, transparent window
 * background, title, and a Cancel button. Shared by the reader's TTS and settings panels so they
 * read as the same component as the rest of the app's option sheets.
 */
private fun ScreenHost.styledPanelSurface(title: String, block: LinearLayout.() -> Unit) {
    val colors = ThemeManager.colors
    val radiusPx = app.dp(ThemeManager.shapes.dialogRadius).toFloat()

    val dialogView = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(app.dp(24), app.dp(20), app.dp(24), app.dp(12))
        background = roundedBg(colors.surface, radiusPx)
        roundCorners(ThemeManager.shapes.dialogRadius.toFloat())
    }

    dialogView.addView(makeText(app, title, Type.TITLE_LARGE, colors.onSurface))
    dialogView.addView(View(app).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.dp(16))
    })
    dialogView.block()

    val cancelButton = makeButton(app, "Cancel", Btn.TEXT) { /* replaced below */ }
    dialogView.addView(LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, app.dp(8), 0, 0)
        addView(cancelButton)
    })

    val dialog = AlertDialog.Builder(app).setView(dialogView).create()
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    cancelButton.setOnClickListener { dialog.dismiss() }
    dialog.show()
}

/**
 * A ripple-tappable row inside a panel. Mirrors the option rows in [showStyledOptionsDialog] but
 * lets callers add a trailing element (e.g. a state label) for richer controls.
 */
private fun LinearLayout.panelRow(label: String, host: ScreenHost, trailing: View? = null, onClick: () -> Unit) {
    val colors = ThemeManager.colors
    val row = LinearLayout(host.app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, host.dp(12), 0, host.dp(12))
        isClickable = true
        isFocusable = true
        background = selectableRipple(colors.onSurface)
    }
    row.addView(makeText(host.app, label, Type.BODY_LARGE, colors.onSurface),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    trailing?.let { row.addView(it) }
    row.setOnClickListener { onClick() }
    addView(row)
}

/**
 * TTS transport panel for the reader. When no session exists it offers a single "Play this
 * chapter" action; once a session is active it exposes Resume/Pause · Stop · Prev/Next chunk, plus
 * a link into the global TTS voice settings. All commands route through [TtsForegroundService] —
 * identical to the inline transport buttons that used to live on the reader body.
 */
internal fun ScreenHost.showReaderTtsPanel(story: Story, chapter: Chapter) {
    val session = storage.getTtsSession()
    styledPanelSurface(if (session != null) "Text to Speech" else "Read Aloud") {
        if (session == null) {
            panelRow("Play this chapter", this@showReaderTtsPanel) {
                TtsForegroundService.start(app, story.id, chapter.id)
            }
        } else {
            val resumeOrPause = if (session.isPaused) "Resume" else "Pause"
            val resumeOrPauseAction =
                if (session.isPaused) TtsForegroundService.ACTION_RESUME_SESSION else TtsForegroundService.ACTION_PAUSE
            panelRow(resumeOrPause, this@showReaderTtsPanel) {
                TtsForegroundService.command(app, resumeOrPauseAction)
            }
            panelRow("Stop", this@showReaderTtsPanel) {
                TtsForegroundService.command(app, TtsForegroundService.ACTION_STOP)
            }
            addView(View(app).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.dp(8))
            })
            panelRow("Previous chunk", this@showReaderTtsPanel) {
                TtsForegroundService.command(app, TtsForegroundService.ACTION_PREVIOUS)
            }
            panelRow("Next chunk", this@showReaderTtsPanel) {
                TtsForegroundService.command(app, TtsForegroundService.ACTION_NEXT)
            }
        }
        panelRow("Voice settings", this@showReaderTtsPanel) { showTtsSettings() }
    }
}

/**
 * Reader display settings panel: font-size stepper, dark-reader toggle, and Copy chapter. Each
 * control mutates [display], persists it, and re-renders the WebView via [onRerender] so changes
 * preview live behind the dialog.
 */
internal fun ScreenHost.showReaderSettingsPanel(
    display: DisplayPreferences,
    onRerender: () -> Unit,
    onCopy: () -> Unit,
) {
    val colors = ThemeManager.colors
    styledPanelSurface("Reader Settings") {
        // Font size: A− / label / A+ in a single row. The label updates in place so the stepper
        // reflects the new size without dismissing the panel.
        val fontRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val sizeLabel = makeText(app, "${(display.readerFontScale * 100).roundToInt()}%", Type.BODY_LARGE, colors.onSurface)
        fun applyFont(delta: Float) {
            display.readerFontScale = (display.readerFontScale + delta).coerceIn(
                PreferenceNormalization.READER_FONT_SCALE_MIN,
                PreferenceNormalization.READER_FONT_SCALE_MAX,
            )
            storage.saveDisplayPreferences(display)
            onRerender()
            sizeLabel.text = "${(display.readerFontScale * 100).roundToInt()}%"
        }
        fontRow.addView(makeButton(app, "A−", Btn.TEXT) { applyFont(-0.1f) })
        fontRow.addView(sizeLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        })
        fontRow.addView(makeButton(app, "A+", Btn.TEXT) { applyFont(0.1f) })
        addView(fontRow)

        // Dark reader toggle row with a trailing state label.
        val darkState = makeText(app, if (display.readerDark) "On" else "Off", Type.LABEL_LARGE, colors.onSurfaceVariant)
        panelRow("Dark reader", this@showReaderSettingsPanel, darkState) {
            display.readerDark = !display.readerDark
            storage.saveDisplayPreferences(display)
            onRerender()
            darkState.text = if (display.readerDark) "On" else "Off"
        }

        addView(View(app).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.dp(8))
        })
        panelRow("Copy chapter text", this@showReaderSettingsPanel) { onCopy() }
    }
}
