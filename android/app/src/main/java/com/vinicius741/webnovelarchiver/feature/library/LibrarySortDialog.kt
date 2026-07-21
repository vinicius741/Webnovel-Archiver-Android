package com.vinicius741.webnovelarchiver.feature.library

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeDivider
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * The Library "Sort by" dialog, split out of [makeLibraryFilters] so the filter bar builder stays
 * focused on composing the search/sort/tag row and the dialog's ~150 lines of view construction
 * live separately. Sort normalization/direction decisions go through [LibraryFiltersPlanning].
 */
internal fun showSortDialog(
    context: Context,
    currentOption: String,
    ascending: Boolean,
    onChanged: (Pair<String, Boolean>) -> Unit,
) {
    val options =
        listOf(
            "default" to "Default (Smart)",
            "title" to "Title",
            "lastUpdated" to "Last Updated",
            "dateAdded" to "Date Added",
            "totalChapters" to "Chapter Count",
            "score" to "Score",
            "patreonMonthly" to "Patreon Earnings",
            "patreonMembers" to "Patreon Paid Members",
        )

    // Canonicalize legacy aliases (e.g. "updated" → "lastUpdated") so the active row always
    // matches an option key and the check/highlight can actually appear.
    val normalizedCurrent = LibraryFiltersPlanning.normalizeSortOption(currentOption)
    val colors = ThemeManager.colors
    val shapes = ThemeManager.shapes
    val radiusPx = context.dp(shapes.dialogRadius).toFloat()
    val chipRadius = context.dp(shapes.chipRadius).toFloat()

    val dialogView =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(20), context.dp(24), context.dp(12))
            background = roundedBg(colors.surface, radiusPx)
            roundCorners(shapes.dialogRadius.toFloat())
        }

    dialogView.addView(makeText(context, "Sort by", Type.TITLE_LARGE, colors.onSurface))
    dialogView.addView(
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(16))
        },
    )

    val checkIcon = context.tintedIcon(R.drawable.wna_check, colors.primary)
    var dialogRef: AlertDialog? = null

    options.forEach { (key, label) ->
        val isSelected = key == normalizedCurrent
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Horizontal padding so the selected-row highlight does not kiss the dialog edge.
                setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
                isClickable = true
                isFocusable = true
                // Selected: soft primaryContainer fill + primary check/text. Unselected: plain ripple.
                background =
                    if (isSelected) {
                        ripple(roundedBg(colors.primaryContainer, chipRadius), chipRadius, colors.onSurface)
                    } else {
                        selectableRipple(colors.onSurface)
                    }
            }
        val check =
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { rightMargin = context.dp(16) }
                contentDescription = if (isSelected) "Selected" else null
                if (isSelected) setImageDrawable(checkIcon)
            }
        val text =
            makeText(
                context,
                label,
                Type.BODY_LARGE,
                // onPrimaryContainer pairs with the primaryContainer fill for readable contrast
                // across all themes; unselected rows stay plain onSurface.
                if (isSelected) colors.onPrimaryContainer else colors.onSurface,
            ).apply {
                if (isSelected) typeface = Typeface.create(typeface, Typeface.BOLD)
            }
        row.addView(check)
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            val newAscending = if (key == normalizedCurrent) !ascending else LibraryFiltersPlanning.defaultDirectionFor(key)
            onChanged(key to newAscending)
            dialogRef?.dismiss()
        }
        dialogView.addView(row)
    }

    // Direction toggle row
    dialogView.addView(makeDivider(context))
    val directionRow =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            isClickable = true
            isFocusable = true
            background = selectableRipple(colors.onSurface)
        }
    val directionIcon =
        context.tintedIcon(
            if (ascending) R.drawable.wna_sort_ascending else R.drawable.wna_sort_descending,
            colors.primary,
        )
    val directionImage =
        ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { rightMargin = context.dp(16) }
            setImageDrawable(directionIcon)
        }
    val directionText =
        makeText(
            context,
            if (ascending) "Ascending" else "Descending",
            Type.BODY_LARGE,
            colors.primary,
        ).apply {
            typeface = Typeface.create(typeface, Typeface.BOLD)
        }
    directionRow.addView(directionImage)
    directionRow.addView(directionText)
    directionRow.setOnClickListener {
        onChanged(normalizedCurrent to !ascending)
        dialogRef?.dismiss()
    }
    dialogView.addView(directionRow)

    val cancelButton = makeButton(context, "Cancel", Btn.TEXT) { dialogRef?.dismiss() }
    dialogView.addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, context.dp(8), 0, 0)
            addView(cancelButton)
        },
    )

    dialogRef =
        AlertDialog
            .Builder(context)
            .setView(dialogView)
            .create()
    dialogRef.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialogRef.show()
}
