package com.vinicius741.webnovelarchiver.feature.ai

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg

/** Custom content owns the entire dialog, including its title and actions, like the app's options sheets. */
internal fun ScreenHost.makeAiCoverDialog(title: String): Pair<AlertDialog, LinearLayout> {
    val colors = ThemeManager.colors
    val root =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
            background = roundedBg(colors.surface, dp(ThemeManager.shapes.dialogRadius).toFloat())
            roundCorners(ThemeManager.shapes.dialogRadius.toFloat())
            addView(
                makeText(app, title, Type.TITLE_LARGE, colors.onSurface).apply {
                    setPadding(0, 0, 0, dp(Space.LG))
                },
            )
        }
    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    val scroll =
        object : ScrollView(app) {
            override fun onMeasure(
                widthMeasureSpec: Int,
                heightMeasureSpec: Int,
            ) {
                val maxHeight = (resources.displayMetrics.heightPixels * 0.65f).toInt()
                val available = View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: maxHeight
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(minOf(maxHeight, available), View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(body) }
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val dialog = AlertDialog.Builder(app).setView(root).create()
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    return dialog to body
}
