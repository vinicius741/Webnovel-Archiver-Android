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
 * Holds the built filter [view] plus a [rebuildChips] hook the screen calls whenever the active
 * tab, search query, or tag selection changes. The available tag/source chips follow that filter
 * context (All = union, a specific tab = only that tab's labels), mirroring the legacy RN
 * `useLibrary` `useMemo` keyed on `activeTabId`. Each rebuild also preserves the chip row's scroll
 * context — the tapped chip is pinned at its on-screen position and the surviving chips keep their
 * order — so narrowing the row never makes it jump away from where the user is looking.
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
            topMargin = dp(Space.MD)
        },
    )

    // Recompute the chip set for the active tab. Labels, counts, and presence derive from the
    // stories still visible under the active filter context: the tab plus the current search query
    // and selected tags, so only tag combinations that can actually match something are offered
    // ([LibraryQuery.availableFilterGroups]). When the active filters match nothing, that function
    // falls back to the tab's full label set so the active chips never vanish and stay deselectable.
    // The collapsible header's active-filter indicators (badge dot + "•") are synced here too so a
    // tag toggle keeps them honest; assigned by the wrapper branch below, no-op without tabs.
    var syncActiveFilters: (Set<String>) -> Unit = { _ -> }
    // Identity of the chip the user just tapped, consumed by the next rebuild so it can pin that
    // chip at the viewport position where it was tapped. Without a pin, the narrowed row re-sorts
    // itself by the new counts and the viewport reflows, so the chip being selected can jump out
    // of view (or the row can visibly snap back to the start) whenever the tap lands mid-row.
    var lastToggledLabel: String? = null
    // Tab the current chip set was built for; a change means the chips are a brand-new set and the
    // row should reset to the start instead of preserving the previous scroll context.
    var lastTabId: String? = null
    // Render order from the previous chip build, per group. Narrowing keeps the surviving chips in
    // the order the user already saw them instead of re-sorting by the new counts, so the row does
    // not reshuffle under the tap. The first build (and tab switches) still use LibraryQuery's
    // frequency-then-name order.
    var lastSourceOrder: List<String> = emptyList()
    var lastTagOrder: List<String> = emptyList()
    // The pending layout listener for [restoreChipScroll]. Only one is ever registered at a time:
    // each rebuild removes the previous one before installing its own, so a burst of rebuilds (e.g.
    // fast search typing) can't stack listeners that each fire `scrollTo` with a stale scroll.
    var pendingScrollListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    fun stableOrder(
        labels: List<Pair<String, Int>>,
        previous: List<String>,
    ): List<Pair<String, Int>> {
        val byLabel = labels.associate { it.first to it }
        return previous.mapNotNull { byLabel[it] } + labels.filter { it.first !in previous }
    }

    // HorizontalScrollView re-clamps (and effectively resets) the viewport when its content is
    // rebuilt, so the scroll position must be re-applied after the new row is measured. Pinning
    // the tapped chip keeps it under the user's finger; otherwise the previous scroll position is
    // preserved. Tab switches reset to the start on purpose.
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
        // Replace any still-pending listener from an earlier rebuild instead of stacking a new one.
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

        // Capture the tapped chip's viewport position before the row is rebuilt. `lastToggledLabel`
        // is null for search/tab rebuilds, which instead preserve the previous scroll (tab changes
        // deliberately reset to the start).
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
        // Render every available label as a chip — no artificial cap. `availableFilterGroups` already
        // returns one entry per unique source/tag (sorted by frequency then name), and the row sits
        // inside a HorizontalScrollView, so a large label set just scrolls instead of being truncated.
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
        // Hide the row entirely when the active tab offers no chips, so the empty scroll view does
        // not leave a stray gap below the search/sort row.
        tagScroll.visibility = if (rowHasChips) View.VISIBLE else View.GONE
        syncActiveFilters(currentTags)
        restoreChipScroll(tabChanged, anchorLabel, anchorViewportX, previousScrollX, rowHasChips)
    }
    populateChips(selectedTabId, selectedTags)

    if (!hasCustomTabs) {
        filtersContainer.layoutParams = filterTopMargin
        return LibraryFiltersView(filtersContainer, populateChips, syncActiveFilters)
    }

    // Collapsible wrapper when tabs exist
    val wrapper = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // The chevron is decorative only — its tap is handled by `toggleWrap`'s listener (below). It must
    // NOT be clickable/focusable, otherwise Android dispatches the touch to this ImageView (which has
    // its own no-op listener from iconButtonSmall), consumes it, and the parent never expands — leaving
    // the search/sort/tag filters trapped behind View.GONE and the whole filter row unresponsive.
    val toggleIcon =
        context
            .iconButton(R.drawable.wna_chevron_down, "Toggle filters", style = com.vinicius741.webnovelarchiver.ui.IconButtonStyle.Small)
            .apply {
                isClickable = false
                isFocusable = false
            }
    // Active-filter indicators (badge dot + "•" next to the "Filters" label), always allocated and
    // shown/hidden by [syncActiveFilters] so they track live tag/search state instead of the initial
    // snapshot. Both are decorative only; the tap target is `toggleWrap`.
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
    // L3: pair the chevron with a "Filters" label so the toggle is discoverable instead of a lone arrow.
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
