package com.vinicius741.webnovelarchiver.feature.library

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.library.LibraryQuery
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.chip
import com.vinicius741.webnovelarchiver.ui.circularRipple
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeSourceChip
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * Holds the built filter [view] plus a [rebuildChips] hook the screen calls whenever the active
 * tab changes. The available tag/source chips follow the active tab (All = union, a specific tab =
 * only that tab's labels), mirroring the legacy RN `useLibrary` `useMemo` keyed on `activeTabId`.
 */
internal class LibraryFiltersView(
    val view: View,
    val rebuildChips: (selectedTabId: String?, selectedTags: Set<String>) -> Unit,
)

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
): LibraryFiltersView {
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

    // Live sort state for this filter bar. The function parameters above are only the *initial*
    // snapshot; without mutable locals the sort-chip click listener and its label would keep
    // replaying the values from first construction, so picking "Default (Smart)" looked like a
    // dead click (dialog reopened still on Last Updated, chip never updated).
    var currentSortOption = LibraryFiltersPlanning.normalizeSortOption(sortOption)
    var currentSortAscending = sortAscending

    fun sortChipLabel(): String = LibraryFiltersPlanning.sortOptionLabel(currentSortOption) + if (currentSortAscending) " ↑" else " ↓"

    fun sortChipIconRes(): Int = if (currentSortAscending) R.drawable.wna_sort_ascending else R.drawable.wna_sort_descending

    // L2: a labeled chip communicates the active sort + direction instead of a bare, stateless icon.
    val sortIconView =
        ImageView(context).apply {
            setImageDrawable(context.tintedIcon(sortChipIconRes(), ThemeManager.colors.onSurfaceVariant))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams =
                LinearLayout.LayoutParams(dp(Space.SM + Space.XS + 2), dp(Space.SM + Space.XS + 2)).apply {
                    rightMargin = dp(Space.XS + 2)
                }
        }
    val sortLabelView = makeText(context, sortChipLabel(), Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant)

    fun refreshSortChip() {
        sortIconView.setImageDrawable(
            context.tintedIcon(sortChipIconRes(), ThemeManager.colors.onSurfaceVariant),
        )
        sortLabelView.text = sortChipLabel()
    }

    val sortButton =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Match the search field's 48dp minimum height so the two filter controls form one
            // visually aligned row instead of the sort chip shrinking to its content height.
            minimumHeight = context.dp(48)
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
            setOnClickListener {
                showSortDialog(context, currentSortOption, currentSortAscending) { newSort ->
                    currentSortOption = LibraryFiltersPlanning.normalizeSortOption(newSort.first)
                    currentSortAscending = newSort.second
                    refreshSortChip()
                    onSortChanged(currentSortOption to currentSortAscending)
                }
            }
            addView(sortIconView)
            addView(sortLabelView)
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
    // The chips follow the active tab (All = every label, a specific tab = only that tab's labels),
    // so the scroll + row are allocated up front and [populateChips] rebuilds them whenever the tab
    // changes. Allocated unconditionally so a refresh can show chips even if the entry tab had none.
    val tagScroll =
        HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
    val tagRow =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
    tagScroll.addView(tagRow)
    filtersContainer.addView(
        tagScroll,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Space.SM)
        },
    )

    // Recompute the chip set for the active tab. Counts and labels derive from the tab's visible
    // stories via [LibraryQuery.availableFilterGroups]; [selectedTags] only sets each chip's pressed
    // state, it does not change which chips appear (the native filter set is tab-derived only).
    val populateChips: (String?, Set<String>) -> Unit = { currentTabId, currentTags ->
        val (sourceLabels, tagLabels) = LibraryQuery.availableFilterGroups(stories, currentTabId)
        tagRow.removeAllViews()
        // Render every available label as a chip — no artificial cap. `availableFilterGroups` already
        // returns one entry per unique source/tag (sorted by frequency then name), and the row sits
        // inside a HorizontalScrollView, so a large label set just scrolls instead of being truncated.
        sourceLabels.forEach { (label, count) ->
            val selected = currentTags.contains(label)
            val chip = makeSourceChip(context, label, count, selected) { onTagToggled(label) }
            tagRow.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(Space.SM + 2)
                },
            )
        }
        tagLabels.forEach { (label, count) ->
            val chipLabel = "$label ($count)"
            val selected = currentTags.contains(label)
            val chip = makeChip(context, chipLabel, selected) { onTagToggled(label) }
            tagRow.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(Space.SM + 2)
                },
            )
        }
        // Hide the row entirely when the active tab offers no chips, so the empty scroll view does
        // not leave a stray gap below the search/sort row.
        tagScroll.visibility = if (sourceLabels.isEmpty() && tagLabels.isEmpty()) View.GONE else View.VISIBLE
    }
    populateChips(selectedTabId, selectedTags)

    if (!hasCustomTabs) {
        filtersContainer.layoutParams = filterTopMargin
        return LibraryFiltersView(filtersContainer, populateChips)
    }

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
    return LibraryFiltersView(wrapper, populateChips)
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
        // OVAL mask so the press feedback reads as a round highlight instead of a square block,
        // matching Material's circular icon-button ripple for a square tap target.
        background = circularRipple(ThemeManager.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(size, size)
    }
}
