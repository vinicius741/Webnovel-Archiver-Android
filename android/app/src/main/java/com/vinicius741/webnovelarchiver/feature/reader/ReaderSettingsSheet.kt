package com.vinicius741.webnovelarchiver.feature.reader

import android.content.Context
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.StepperSpec
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.stepperRow
import kotlin.math.roundToInt

/**
 * The v3 reader settings sheet: text size on a stepper (applied live), "Pure black" (the stored
 * dark-reader preference), voice settings, and Copy chapter text. Every change goes to [onDisplay]
 * at once with the sheet's whole display; rows that leave the reader close the sheet first so the
 * next screen is not buried under it.
 */
internal fun showReaderSettingsSheet(
    context: Context,
    initial: ReaderDisplay,
    voiceSummary: String?,
    onDisplay: (ReaderDisplay) -> Unit,
    onCopy: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    val colors = ThemeManager.colors
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    lateinit var sheet: BottomSheetHandle
    var display = initial

    val sizeHolder = LinearLayout(context).apply { setPadding(context.dp(Spacing.SM), 0, 0, 0) }
    sizeHolder.stepperRow(
        "Text size",
        null,
        (display.fontScale * PERCENT).roundToInt().toDouble(),
        StepperSpec(
            step = FONT_STEP_PERCENT,
            min = (PreferenceNormalization.READER_FONT_SCALE_MIN * PERCENT).toDouble(),
            max = (PreferenceNormalization.READER_FONT_SCALE_MAX * PERCENT).toDouble(),
            unit = "%",
            label = "Text size",
        ),
    ) { value ->
        display = display.copy(fontScale = (value / PERCENT).toFloat())
        onDisplay(display)
        null
    }
    body.addView(sizeHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val blackSwitch =
        SwitchCompat(context).apply {
            isChecked = display.pureBlack
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
        makeSheetRow(context, "Pure black", supporting = "Saves battery on OLED screens", iconRes = R.drawable.wna_dark_mode) {
            display = display.copy(pureBlack = !display.pureBlack)
            blackSwitch.isChecked = display.pureBlack
            onDisplay(display)
        }
    (blackRow as LinearLayout).addView(blackSwitch)
    blackRow.contentDescription = "Pure black"
    body.addView(blackRow)
    body.addView(
        makeSheetRow(context, "Listening settings", supporting = voiceSummary, iconRes = R.drawable.wna_record_voice) {
            sheet.dismiss()
            onOpenVoiceSettings()
        },
    )
    body.addView(
        makeSheetRow(context, "Copy chapter text", iconRes = R.drawable.wna_copy) {
            sheet.dismiss()
            onCopy()
        },
    )
    sheet = showBottomSheet(context, title = "Reader settings", body = body)
}

private const val PERCENT = 100f
private const val FONT_STEP_PERCENT = 10.0
