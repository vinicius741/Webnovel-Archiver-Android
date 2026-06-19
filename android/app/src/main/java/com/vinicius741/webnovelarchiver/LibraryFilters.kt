package com.vinicius741.webnovelarchiver

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.LibraryQuery
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeDivider
import com.vinicius741.webnovelarchiver.ui.makeSourceChip
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

internal fun ScreenHost.makeLibraryFilters(
    context: Context,
    search: EditText,
    hasCustomTabs: Boolean,
    stories: List<Story>,
    selectedTabId: String?,
    selectedTags: Set<String>,
    sortOption: String,
    sortAscending: Boolean,
    onSortChanged: (Pair<String, Boolean>) -> Unit,
    onTagToggled: (String) -> Unit,
): View {
    val filtersContainer =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
    val filterTopMargin =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Space.SM)
        }

    // Search + sort row
    val searchRow =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    val sortIcon = if (sortAscending) R.drawable.wna_sort_ascending else R.drawable.wna_sort_descending
    // L2: a labeled chip communicates the active sort + direction instead of a bare, stateless icon.
    val sortLabel = sortOptionLabel(sortOption) + if (sortAscending) " ↑" else " ↓"
    val sortButton =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Space.SM), dp(Space.SM), dp(Space.SM), dp(Space.SM))
            background =
                ripple(
                    strokeBg(
                        Color.TRANSPARENT,
                        context.dp(ThemeManager.shapes.chipRadius).toFloat(),
                        ThemeManager.colors.outline,
                        context.dp(1),
                    ),
                    context.dp(ThemeManager.shapes.chipRadius).toFloat(),
                    ThemeManager.colors.onSurface,
                )
            isClickable = true
            isFocusable = true
            setOnClickListener { showSortDialog(context, sortOption, sortAscending, onSortChanged) }
            addView(
                ImageView(context).apply {
                    setImageDrawable(context.tintedIcon(sortIcon, ThemeManager.colors.onSurfaceVariant))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams =
                        LinearLayout.LayoutParams(dp(Space.SM + Space.XS + 2), dp(Space.SM + Space.XS + 2)).apply {
                            rightMargin =
                                dp(Space.XS + 2)
                        }
                },
            )
            addView(makeText(context, sortLabel, Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant))
        }
    searchRow.addView(
        sortButton,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin =
                dp(Space.MD)
        },
    )
    filtersContainer.addView(searchRow)

    // Tag chips — L4: render source filters (globe icon, filled) separately from genre tags so the
    // two filter kinds are visually distinguishable instead of one flat row of identical chips.
    val (sourceLabels, tagLabels) = LibraryQuery.availableFilterGroups(stories, selectedTabId)
    if (sourceLabels.isNotEmpty() || tagLabels.isNotEmpty()) {
        val tagScroll =
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
            }
        val tagRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        sourceLabels.take(4).forEach { (label, count) ->
            val selected = selectedTags.contains(label)
            val chip = makeSourceChip(context, label, count, selected) { onTagToggled(label) }
            tagRow.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(Space.SM + 2)
                },
            )
        }
        tagLabels.take(8).forEach { (label, count) ->
            val chipLabel = "$label ($count)"
            val selected = selectedTags.contains(label)
            val chip = makeChip(context, chipLabel, selected) { onTagToggled(label) }
            tagRow.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(Space.SM + 2)
                },
            )
        }
        tagScroll.addView(tagRow)
        filtersContainer.addView(
            tagScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Space.SM)
            },
        )
    }

    if (!hasCustomTabs) return filtersContainer.also { it.layoutParams = filterTopMargin }

    // Collapsible wrapper when tabs exist
    val wrapper = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val hasActiveFilters = selectedTags.isNotEmpty() || search.text.isNotBlank()
    // The chevron is decorative only — its tap is handled by `toggleWrap`'s listener (below). It must
    // NOT be clickable/focusable, otherwise Android dispatches the touch to this ImageView (which has
    // its own no-op listener from iconButtonSmall), consumes it, and the parent never expands — leaving
    // the search/sort/tag filters trapped behind View.GONE and the whole filter row unresponsive.
    val toggleIcon =
        context.iconButtonSmall(R.drawable.wna_chevron_down, "Toggle filters") { }.apply {
            isClickable = false
            isFocusable = false
        }
    val toggleWrap =
        FrameLayout(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    gravity = Gravity.END
                }
            addView(toggleIcon)
            if (hasActiveFilters) {
                addView(
                    View(context).apply {
                        layoutParams =
                            FrameLayout.LayoutParams(dp(Space.SM), dp(Space.SM), Gravity.TOP or Gravity.END).apply {
                                topMargin = dp(Space.XS + 2)
                                rightMargin =
                                    dp(Space.XS + 2)
                            }
                        background = roundedBg(ThemeManager.colors.primary, dp(Space.XS).toFloat())
                    },
                )
            }
        }
    val headerRow =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    // L3: pair the chevron with a "Filters" label so the toggle is discoverable instead of a lone arrow.
    headerRow.addView(
        makeText(context, "Filters", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(0, 0, dp(Space.XS + 2), 0)
        },
    )
    if (hasActiveFilters) {
        headerRow.addView(
            makeText(context, "•", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                setPadding(
                    0,
                    0,
                    dp(
                        Space.XS + 2,
                    ),
                    0,
                )
            },
        )
    }
    headerRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
    headerRow.addView(toggleWrap)
    wrapper.addView(headerRow)
    wrapper.addView(filtersContainer)

    var expanded = false
    filtersContainer.visibility = View.GONE
    toggleWrap.setOnClickListener {
        expanded = !expanded
        filtersContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        toggleIcon
            .animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(200)
            .start()
    }
    wrapper.layoutParams = filterTopMargin
    return wrapper
}

private fun showSortDialog(
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
        )

    val colors = ThemeManager.colors
    val shapes = ThemeManager.shapes
    val radiusPx = context.dp(shapes.dialogRadius).toFloat()

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
        val isSelected = key == currentOption
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(12), 0, context.dp(12))
                isClickable = true
                isFocusable = true
                background = selectableRipple(colors.onSurface)
            }
        val check =
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { rightMargin = context.dp(16) }
                if (isSelected) setImageDrawable(checkIcon)
            }
        val text = makeText(context, label, Type.BODY_LARGE, colors.onSurface)
        row.addView(check)
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            val newAscending = if (key == currentOption) !ascending else defaultDirectionFor(key)
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
            setPadding(0, context.dp(12), 0, context.dp(12))
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
    val directionText = makeText(context, if (ascending) "Ascending" else "Descending", Type.BODY_LARGE, colors.onSurface)
    directionRow.addView(directionImage)
    directionRow.addView(directionText)
    directionRow.setOnClickListener {
        onChanged(currentOption to !ascending)
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

private fun defaultDirectionFor(option: String): Boolean =
    when (option) {
        "title" -> true
        else -> false
    }

/** Short human label for a sort option key, shown on the Library sort chip. */
private fun sortOptionLabel(option: String): String =
    when (option) {
        "title" -> "Title"
        "lastUpdated" -> "Updated"
        "dateAdded" -> "Added"
        "totalChapters" -> "Chapters"
        "score" -> "Score"
        else -> "Default"
    }

private fun Context.iconButtonSmall(
    iconRes: Int,
    desc: String,
    onClick: () -> Unit,
): ImageView {
    val size = dp(40)
    return ImageView(this).apply {
        contentDescription = desc
        setImageDrawable(tintedIcon(iconRes, ThemeManager.colors.onSurfaceVariant))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(Space.SM), dp(Space.SM), dp(Space.SM), dp(Space.SM))
        background = selectableRipple(ThemeManager.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(size, size)
    }
}
