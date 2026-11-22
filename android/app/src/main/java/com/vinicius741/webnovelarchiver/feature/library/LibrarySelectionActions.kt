package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.BOTTOM_BAR_VIEW_TAG
import com.vinicius741.webnovelarchiver.ui.RAIL_MIN_WIDTH_DP
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * Bottom action bar of the Library's selection mode (Move / Download / Delete). It stands in for
 * the navigation bar, which the screen hides while selecting; the count lives in the top bar.
 */
internal fun ScreenHost.makeLibrarySelectionBar(
    onMove: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
): View {
    val colors = ThemeManager.colors
    val wide = app.resources.configuration.screenWidthDp >= RAIL_MIN_WIDTH_DP
    val bar =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            val radius = if (wide) dp(WIDE_BAR_RADIUS_DP).toFloat() else 0f
            background =
                strokeBg(
                    SELECTION_BAR_FILL,
                    radius,
                    ColorUtils.setAlphaComponent(colors.primary, SELECTION_BAR_BORDER_ALPHA),
                    dp(1).coerceAtLeast(1),
                )
            addView(selectionBarButton(R.drawable.wna_folder, "Move", colors.onPrimaryContainer, onMove))
            addView(selectionBarButton(R.drawable.wna_download, "Download", colors.onPrimaryContainer, onDownload))
            addView(selectionBarButton(R.drawable.wna_delete, "Delete", colors.error, onDelete))
        }
    return FrameLayout(app).apply {
        tag = BOTTOM_BAR_VIEW_TAG
        if (wide) setPadding(dp(Spacing.LG), dp(Spacing.SM), dp(Spacing.LG), dp(Spacing.LG))
        addView(
            bar,
            FrameLayout.LayoutParams(
                if (wide) dp(WIDE_BAR_MAX_WIDTH_DP) else ViewGroup.LayoutParams.MATCH_PARENT,
                dp(SELECTION_BAR_HEIGHT_DP),
                Gravity.CENTER_HORIZONTAL,
            ),
        )
    }
}

/** "Move N novels": Unassigned and each tab; [onPick] gets the chosen tab id (null is Unassigned). */
internal fun ScreenHost.showMoveStoriesDialog(
    count: Int,
    tabs: List<Tab>,
    onPick: (String?) -> Unit,
) {
    if (count == 0) return
    val tabOptions = listOf(null to "Unassigned") + tabs.map { it.id to it.name }
    val options = tabOptions.map { (tabId, label) -> label to { onPick(tabId) } }
    val novelLabel = if (count == 1) "novel" else "novels"
    showStyledOptionsDialog("Move $count $novelLabel", options)
}

private fun ScreenHost.selectionBarButton(
    iconRes: Int,
    label: String,
    color: Int,
    onClick: () -> Unit,
): View =
    LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = selectableRipple(color)
        contentDescription = label
        addView(
            ImageView(app).apply { setImageDrawable(app.tintedIcon(iconRes, color)) },
            LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)),
        )
        addView(
            makeText(app, label, Type.LABEL_MEDIUM, color).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(Spacing.XS), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
    }

private const val SELECTION_BAR_HEIGHT_DP = 72
private const val SELECTION_BAR_FILL = 0xFF2A2417.toInt()
private const val SELECTION_BAR_BORDER_ALPHA = 0x40
private const val WIDE_BAR_RADIUS_DP = 20
private const val WIDE_BAR_MAX_WIDTH_DP = 560
private const val ICON_DP = 24
