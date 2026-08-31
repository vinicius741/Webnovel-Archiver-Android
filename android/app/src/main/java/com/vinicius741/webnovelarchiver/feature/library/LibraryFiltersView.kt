package com.vinicius741.webnovelarchiver.feature.library

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeSourceChip
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * Filter bar [view] plus [rebuildChips] (called on tab/search/tag changes). Chips follow that
 * filter context; rebuilds preserve chip-row scroll — the tapped chip stays pinned and surviving
 * chips keep their order.
 */
internal class LibraryFiltersView(
    val view: View,
    val rebuildChips: (String?, Set<String>) -> Unit,
    val syncActiveFilters: (Set<String>) -> Unit,
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

    val searchRow =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    // The parameters are only the initial snapshot; the chip reads these locals to track dialog picks.
    var currentSortOption = LibraryFiltersPlanning.normalizeSortOption(sortOption)
    var currentSortAscending = sortAscending

    fun sortChipLabel(): String = LibraryFiltersPlanning.sortOptionLabel(currentSortOption) + if (currentSortAscending) " ↑" else " ↓"

    fun sortChipIconRes(): Int = if (currentSortAscending) R.drawable.wna_sort_ascending else R.drawable.wna_sort_descending

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
            // Match the search field's 48dp min height so the two controls align.
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

    // Chips are allocated up front so a refresh can show them even if the entry tab had none.
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
            topMargin = dp(Space.MD)
        },
    )

    // Labels derive from stories visible under the tab + search + selected tags
    // ([LibraryQuery.availableFilterGroups]; falls back to the tab's full label set when nothing
    // matches). syncActiveFilters — assigned below when tabs exist — keeps the header honest.
    var syncActiveFilters: (Set<String>) -> Unit = { _ -> }
    // The chip the user just tapped, pinned by the next rebuild at its viewport position — without
    // a pin, the narrowed row re-sorts by the new counts and the selected chip can jump out of view.
    var lastToggledLabel: String? = null
    // Tab the chip set was built for; a change resets the row to the start.
    var lastTabId: String? = null
    // Previous render order per group: narrowing keeps surviving chips in the order the user saw;
    // first build and tab switches use LibraryQuery's frequency-then-name order.
    var lastSourceOrder: List<String> = emptyList()
    var lastTagOrder: List<String> = emptyList()
    // Only one layout listener at a time: each rebuild removes the previous one, so bursts of
    // rebuilds can't stack stale `scrollTo` calls.
    var pendingScrollListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    fun stableOrder(
        labels: List<Pair<String, Int>>,
        previous: List<String>,
    ): List<Pair<String, Int>> {
        val byLabel = labels.associate { it.first to it }
        return previous.mapNotNull { byLabel[it] } + labels.filter { it.first !in previous }
    }

    // HorizontalScrollView resets its viewport when content is rebuilt, so scroll is re-applied
    // after the new row is measured; the tapped chip stays under the finger, tab changes reset.
    fun restoreChipScroll(
        tabChanged: Boolean,
        anchorLabel: String?,
        anchorViewportX: Int?,
        previousScrollX: Int,
        rowHasChips: Boolean,
    ) {
        val pinnedChip =
            if (!tabChanged && anchorLabel != null && anchorViewportX != null) {
                (0 until tagRow.childCount)
                    .firstOrNull { tagRow.getChildAt(it).tag == anchorLabel }
                    ?.let { tagRow.getChildAt(it) }
            } else {
                null
            }
        if (!rowHasChips || (pinnedChip == null && (tabChanged || previousScrollX <= 0))) return
        pendingScrollListener?.let { tagScroll.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        val listener =
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (tagRow.width <= 0) return
                    tagScroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    pendingScrollListener = null
                    val scroll = if (pinnedChip != null) pinnedChip.left - anchorViewportX!! else previousScrollX
                    val maxScroll = (tagRow.width - tagScroll.width).coerceAtLeast(0)
                    tagScroll.scrollTo(scroll.coerceIn(0, maxScroll), 0)
                }
            }
        pendingScrollListener = listener
        tagScroll.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    val populateChips: (String?, Set<String>) -> Unit = { currentTabId, currentTags ->
        val tabChanged = currentTabId != lastTabId
        lastTabId = currentTabId

        // Capture the tapped chip's viewport position before the rebuild; null for search/tab
        // rebuilds, which preserve the previous scroll (tabs reset to the start).
        var anchorViewportX: Int? = null
        if (!tabChanged && lastToggledLabel != null) {
            for (i in 0 until tagRow.childCount) {
                val chipView = tagRow.getChildAt(i)
                if (chipView.tag == lastToggledLabel) {
                    anchorViewportX = chipView.left - tagScroll.scrollX
                    break
                }
            }
        }
        val anchorLabel = lastToggledLabel
        lastToggledLabel = null
        val previousScrollX = tagScroll.scrollX

        val (sourceLabels, tagLabels) =
            LibraryQuery.availableFilterGroups(
                stories,
                currentTabId,
                searchQuery = search.text.toString(),
                selectedTags = currentTags,
            )
        val orderedSources = stableOrder(sourceLabels, lastSourceOrder)
        val orderedTags = stableOrder(tagLabels, lastTagOrder)
        lastSourceOrder = orderedSources.map { it.first }
        lastTagOrder = orderedTags.map { it.first }

        tagRow.removeAllViews()
        // No artificial cap on chip count; the row just scrolls.
        orderedSources.forEach { (label, count) ->
            val selected = currentTags.contains(label)
            val chip =
                makeSourceChip(context, label, count, selected) {
                    lastToggledLabel = label
                    onTagToggled(label)
                }
            chip.tag = label
            tagRow.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(Space.SM + 2)
                },
            )
        }
        orderedTags.forEach { (label, count) ->
            val chipLabel = "$label ($count)"
            val selected = currentTags.contains(label)
            val chip =
                makeChip(context, chipLabel, selected) {
                    lastToggledLabel = label
                    onTagToggled(label)
                }
            chip.tag = label
            tagRow.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(Space.SM + 2)
                },
            )
        }
        val rowHasChips = sourceLabels.isNotEmpty() || tagLabels.isNotEmpty()
        // Hide the row when the tab offers no chips so the empty scroll view leaves no stray gap.
        tagScroll.visibility = if (rowHasChips) View.VISIBLE else View.GONE
        syncActiveFilters(currentTags)
        restoreChipScroll(tabChanged, anchorLabel, anchorViewportX, previousScrollX, rowHasChips)
    }
    populateChips(selectedTabId, selectedTags)

    if (!hasCustomTabs) {
        filtersContainer.layoutParams = filterTopMargin
        return LibraryFiltersView(filtersContainer, populateChips, syncActiveFilters)
    }

    val wrapper = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // Decorative only: if clickable, Android dispatches the touch to this ImageView (which has its
    // own no-op listener), consumes it, and the parent never expands.
    val toggleIcon =
        context
            .iconButton(R.drawable.wna_chevron_down, "Toggle filters", style = com.vinicius741.webnovelarchiver.ui.IconButtonStyle.Small)
            .apply {
                isClickable = false
                isFocusable = false
            }
    // Active-filter indicators, shown/hidden by [syncActiveFilters]; decorative only — the tap
    // target is `toggleWrap`.
    val activeDot =
        View(context).apply {
            layoutParams =
                FrameLayout.LayoutParams(dp(Space.SM), dp(Space.SM), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(Space.XS + 2)
                    rightMargin = dp(Space.XS + 2)
                }
            background = roundedBg(ThemeManager.colors.primary, dp(Space.XS).toFloat())
        }
    val toggleWrap =
        FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { gravity = Gravity.END }
            addView(toggleIcon)
            addView(activeDot)
        }
    val headerRow =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    headerRow.addView(
        makeText(context, "Filters", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(0, 0, dp(Space.XS + 2), 0)
        },
    )
    val activeLabel =
        makeText(context, "•", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(0, 0, dp(Space.XS + 2), 0)
        }
    headerRow.addView(activeLabel)
    headerRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
    headerRow.addView(toggleWrap)
    wrapper.addView(headerRow)
    wrapper.addView(filtersContainer)

    syncActiveFilters = { tags ->
        val active = tags.isNotEmpty() || search.text.isNotBlank()
        activeDot.visibility = if (active) View.VISIBLE else View.GONE
        activeLabel.visibility = if (active) View.VISIBLE else View.GONE
    }
    syncActiveFilters(selectedTags)

    var expanded = false
    filtersContainer.visibility = View.GONE
    val toggleAction = {
        expanded = !expanded
        filtersContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        toggleIcon
            .animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(200)
            .start()
    }
    headerRow.isClickable = true
    headerRow.isFocusable = true
    headerRow.background = selectableRipple(ThemeManager.colors.onSurface)
    headerRow.setOnClickListener { toggleAction() }
    toggleWrap.setOnClickListener { toggleAction() }
    wrapper.layoutParams = filterTopMargin
    return LibraryFiltersView(wrapper, populateChips, syncActiveFilters)
}
