package com.vinicius741.webnovelarchiver.feature.updates

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization.FOLLOW_THRESHOLD_MAX
import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization.FOLLOW_THRESHOLD_MIN
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.styledDialogField
import com.vinicius741.webnovelarchiver.ui.toast

internal fun ScreenHost.showUpdateThresholdDialog(
    current: Int,
    onSave: (Int) -> Unit,
) {
    val theme = ThemeManager.current
    val colors = theme.colors
    val radiusPx = app.dp(theme.shapes.dialogRadius).toFloat()
    val input = styledDialogField(current.toString(), "Chapters behind the end", InputType.TYPE_CLASS_NUMBER)
    var dialogRef: AlertDialog? = null

    fun save() {
        val parsed = input.text.toString().toIntOrNull()
        if (parsed == null) {
            toast("Enter a number between $FOLLOW_THRESHOLD_MIN and $FOLLOW_THRESHOLD_MAX")
            return
        }
        onSave(parsed.coerceIn(FOLLOW_THRESHOLD_MIN, FOLLOW_THRESHOLD_MAX))
        dialogRef?.dismiss()
    }

    val content =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.dp(24), app.dp(20), app.dp(24), app.dp(12))
            background = roundedBg(colors.surface, radiusPx)
            roundCorners(theme.shapes.dialogRadius.toFloat())
        }
    content.addView(makeText(app, "Follow threshold", Type.TITLE_LARGE, colors.onSurface))
    content.addView(
        makeText(
            app,
            "A novel is followed while its bookmark is within this many chapters of the latest synced chapter.",
            Type.BODY_MEDIUM,
            colors.onSurfaceVariant,
        ).apply { setPadding(0, app.dp(Space.SM), 0, app.dp(Space.SM)) },
    )
    content.addView(input)
    content.addView(
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, app.dp(8), 0, 0)
            addView(makeButton(app, "Cancel", Btn.TEXT) { dialogRef?.dismiss() })
            addView(makeButton(app, "Save", Btn.TEXT) { save() })
        },
    )

    dialogRef =
        AlertDialog
            .Builder(app)
            .setView(content)
            .create()
    dialogRef.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialogRef.show()
}
