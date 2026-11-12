package com.vinicius741.webnovelarchiver.feature.downloads

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.showBottomSheet

/** One row of a Downloads menu sheet; a null entry draws a hairline between groups. */
internal data class QueueMenuEntry(
    val label: String,
    val icon: Int,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

/** A v3 sheet with icon rows; tapping a row closes the sheet, then runs it. */
internal fun ScreenHost.showQueueMenu(
    title: String,
    subtitle: String?,
    entries: List<QueueMenuEntry?>,
) {
    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    var sheet: BottomSheetHandle? = null
    entries.forEach { entry ->
        if (entry == null) {
            body.addView(
                View(app).apply { setBackgroundColor(ThemeManager.colors.line) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.dp(1)).apply {
                    setMargins(app.dp(Spacing.SM), app.dp(Spacing.SM), app.dp(Spacing.SM), app.dp(Spacing.SM))
                },
            )
        } else {
            body.addView(
                makeSheetRow(app, entry.label, iconRes = entry.icon, destructive = entry.destructive) {
                    sheet?.dismiss()
                    entry.action()
                },
            )
        }
    }
    sheet = showBottomSheet(app, title = title, subtitle = subtitle, body = body)
}
