package com.vinicius741.webnovelarchiver.ui

import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * The v3 reader settings sheet: text size on a stepper (applied live), "Pure black" (the stored
 * dark-reader preference), voice settings, and Copy chapter text. Changes persist immediately;
 * rows that leave the reader close the sheet first so the next screen is not buried under it.
 */
@Suppress("LongParameterList") // Named callbacks from the Reader; call sites use named arguments.
internal fun ScreenHost.showReaderSettingsPanel(
    display: DisplayPreferences,
    voiceSummary: String?,
    // Applies a new text size to the reader page shown now, without reloading it.
    onFontScale: (Float) -> Unit,
    // Re-renders the reader shown now after a change the page cannot apply live (its colors).
    onRerender: () -> Unit,
    onCopy: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    val colors = ThemeManager.colors
    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    lateinit var sheet: BottomSheetHandle

    // A held stepper ticks many times a second: writes run one after another, only the latest
    // value is stored, and each stores just this sheet's fields over the latest saved preferences.
    var latest = 0
    var writes: Job? = null

    fun save(): Job {
        val ticket = ++latest
        val previous = writes
        return scope
            .launch {
                previous?.join()
                if (ticket != latest) return@launch
                val fontScale = display.readerFontScale
                val dark = display.readerDark
                runCatching { repository.updateDisplayPreferences { it.copy(readerFontScale = fontScale, readerDark = dark) } }
                    .onFailure {
                        Timber.e(it, "Reader settings save failed")
                        toast("Could not save reader settings")
                    }
            }.also { writes = it }
    }

    suspend fun saved() {
        while (true) {
            val write = writes ?: return
            write.join()
            if (write === writes) return
        }
    }

    val sizeHolder = LinearLayout(app).apply { setPadding(dp(Spacing.SM), 0, 0, 0) }
    sizeHolder.stepperRow(
        "Text size",
        null,
        (display.readerFontScale * PERCENT).roundToInt().toDouble(),
        StepperSpec(
            step = FONT_STEP_PERCENT,
            min = (PreferenceNormalization.READER_FONT_SCALE_MIN * PERCENT).toDouble(),
            max = (PreferenceNormalization.READER_FONT_SCALE_MAX * PERCENT).toDouble(),
            unit = "%",
            label = "Text size",
        ),
    ) { value ->
        display.readerFontScale = (value / PERCENT).toFloat()
        save()
        onFontScale(display.readerFontScale)
        null
    }
    body.addView(sizeHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val blackSwitch =
        SwitchCompat(app).apply {
            isChecked = display.readerDark
            thumbTintList =
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(colors.onPrimary, colors.onSurfaceVariant),
                )
            trackTintList =
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(colors.primary, colors.elevation5),
                )
            isClickable = false
            isFocusable = false
        }
    val blackRow =
        makeSheetRow(app, "Pure black", supporting = "Saves battery on OLED screens", iconRes = R.drawable.wna_dark_mode) {
            display.readerDark = !display.readerDark
            blackSwitch.isChecked = display.readerDark
            // The re-rendered page reads the stored preference, so it waits for the write.
            save()
            scope.launch {
                saved()
                onRerender()
            }
        }
    (blackRow as LinearLayout).addView(blackSwitch)
    blackRow.contentDescription = "Pure black"
    body.addView(blackRow)
    body.addView(
        makeSheetRow(app, "Voice settings", supporting = voiceSummary, iconRes = R.drawable.wna_record_voice) {
            sheet.dismiss()
            onOpenVoiceSettings()
        },
    )
    body.addView(
        makeSheetRow(app, "Copy chapter text", iconRes = R.drawable.wna_copy) {
            sheet.dismiss()
            onCopy()
        },
    )
    sheet = showBottomSheet(app, title = "Reader settings", body = body)
}

private const val PERCENT = 100f
private const val FONT_STEP_PERCENT = 10.0
