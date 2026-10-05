package com.vinicius741.webnovelarchiver.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout

/** A vertical form whose items own the spacing between groups and between a label and its control. */
internal class FormLayout(
    context: Context,
    dialog: Boolean = false,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        if (dialog) {
            setPadding(context.dp(Spacing.XL), context.dp(Spacing.LG), context.dp(Spacing.XL), context.dp(Spacing.LG))
        }
    }

    /** Adds a full-width control or preview with an optional accessible label. Do not add extra spacers. */
    fun addItem(
        control: View,
        label: String? = null,
    ) {
        val group =
            LinearLayout(context).apply {
                orientation = VERTICAL
                if (label != null) {
                    if (control.id == View.NO_ID) control.id = View.generateViewId()
                    addView(
                        makeText(context, label, Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                            labelFor = control.id
                        },
                        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = context.dp(Spacing.SM)
                        },
                    )
                }
                addView(control, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        addView(
            group,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (childCount > 0) topMargin = context.dp(Spacing.XL)
            },
        )
    }
}

/** Keeps dialog actions above the keyboard while the form body scrolls in the available space. */
internal fun AlertDialog.applyFormStyle() {
    applyAppTheme()
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
}
