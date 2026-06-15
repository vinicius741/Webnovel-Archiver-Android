package com.vinicius741.webnovelarchiver.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.ScreenHost

/**
 * Custom options sheet matching the Library sort-by dialog styling:
 * rounded surface, title, selectable rows with ripple, and a text Cancel button.
 */
internal fun ScreenHost.showStyledOptionsDialog(
    title: String,
    options: List<Pair<String, () -> Unit>>,
) {
    val colors = ThemeManager.colors
    val shapes = ThemeManager.shapes
    val radiusPx = app.dp(shapes.dialogRadius).toFloat()

    val dialogView = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(app.dp(24), app.dp(20), app.dp(24), app.dp(12))
        background = roundedBg(colors.surface, radiusPx)
        roundCorners(shapes.dialogRadius.toFloat())
    }

    dialogView.addView(makeText(app, title, Type.TITLE_LARGE, colors.onSurface))
    dialogView.addView(View(app).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.dp(16))
    })

    var dialogRef: AlertDialog? = null

    options.forEach { (label, action) ->
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, app.dp(12), 0, app.dp(12))
            isClickable = true
            isFocusable = true
            background = selectableRipple(colors.onSurface)
        }
        val text = makeText(app, label, Type.BODY_LARGE, colors.onSurface)
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            dialogRef?.dismiss()
            action()
        }
        dialogView.addView(row)
    }

    val cancelButton = makeButton(app, "Cancel", Btn.TEXT) { dialogRef?.dismiss() }
    dialogView.addView(LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, app.dp(8), 0, 0)
        addView(cancelButton)
    })

    dialogRef = AlertDialog.Builder(app)
        .setView(dialogView)
        .create()
    dialogRef.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialogRef.show()
}
