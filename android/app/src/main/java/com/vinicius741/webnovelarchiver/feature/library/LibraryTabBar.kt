package com.vinicius741.webnovelarchiver.feature.library

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.feature.library.LibraryTabSelection
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.SCREEN_SIDE_PADDING_DP
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.text

/**
 * Tab bar for the Library. Holds the [view] to add to the screen plus hooks the pager uses to stay
 * in two-way sync with it:
 *  - [selectVisual] restyles the active tab indicator in place (no rebuild) when a *swipe* changes
 *    the active tab.
 *  - [onSelectFromBar] is invoked (when set) when the user *taps* a tab, so the caller can animate a
 *    bound [androidx.viewpager2.widget.ViewPager2] to the matching page. Left null in the
 *    single-grid (no-pager) path.
 */
internal class LibraryTabBar(
    val view: View,
    private val applySelection: (String?) -> Unit,
) {
    var onSelectFromBar: ((String?) -> Unit)? = null

    fun selectVisual(id: String?) = applySelection(id)
}

internal fun ScreenHost.makeLibraryTabBar(
    context: Context,
    tabs: List<Tab>,
    stories: List<Story>,
    selectedTabId: String?,
    onSelect: (String?) -> Unit,
): LibraryTabBar {
    val unassignedCount = stories.count { it.tabId == null }
    val scroll =
        HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
    val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

    // Keep each tab's id paired with its text + underline views so we can restyle the active
    // tab in place on selection, instead of rebuilding the whole bar (which would leave the
    // indicator stuck on the initially-selected tab).
    data class TabView(
        val id: String?,
        val container: View,
        val text: TextView,
        val underline: View,
    )
    val tabViews = mutableListOf<TabView>()
    var currentSelection = selectedTabId

    // Keeps the selected tab in view by centering it, as far as the row can scroll.
    fun revealSelection(animated: Boolean) {
        val tab = tabViews.firstOrNull { it.id == currentSelection }?.container ?: return
        val target = tab.left - (scroll.width - tab.width) / 2
        if (animated) scroll.smoothScrollTo(target, 0) else scroll.scrollTo(target, 0)
    }

    fun applySelection(id: String?) {
        currentSelection = id
        val colors = ThemeManager.colors
        tabViews.forEach { tab ->
            val selected = tab.id == id
            tab.text.setTextColor(if (selected) colors.primary else colors.onSurfaceVariant)
            tab.text.typeface = Typeface.create(tab.text.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            tab.underline.setBackgroundColor(if (selected) colors.primary else Color.TRANSPARENT)
        }
        if (scroll.isLaidOut) revealSelection(animated = true)
    }

    // Invoke both the bar's selection callback (persist + filter) and, when set, the pager hook that
    // animates to the tapped tab's page. Order matters only visually; both are idempotent. The holder
    // is assigned after it is constructed below; fireSelect reads it at click time, never at setup time.
    var holderRef: LibraryTabBar? = null

    fun fireSelect(id: String?) {
        applySelection(id)
        onSelect(id)
        holderRef?.onSelectFromBar?.invoke(id)
    }

    fun addTab(
        label: String,
        id: String?,
    ) {
        val text =
            makeText(context, label, Type.LABEL_LARGE, ThemeManager.colors.onSurfaceVariant).apply {
                // Symmetric, so labels stay centered; the first one lines up with the content below.
                setPadding(dp(SCREEN_SIDE_PADDING_DP), dp(Space.SM), dp(SCREEN_SIDE_PADDING_DP), dp(Space.SM))
                minWidth = dp(60)
                gravity = Gravity.CENTER
            }
        val underline =
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(TAB_INDICATOR_HEIGHT_DP))
            }
        val tabContainer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(text)
                addView(underline)
                isClickable = true
                isFocusable = true
                background = selectableRipple(ThemeManager.colors.onSurface)
                setOnClickListener { fireSelect(id) }
            }
        row.addView(tabContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tabViews += TabView(id, tabContainer, text, underline)
    }

    if (unassignedCount > 0) {
        addTab("Unassigned ($unassignedCount)", null)
    }
    tabs.forEach { tab ->
        addTab(tab.name, tab.id)
    }
    addTab("All", LibraryTabSelection.ALL_TAB_ID)
    applySelection(currentSelection)
    scroll.addView(row)
    scroll.doOnLayout { revealSelection(animated = false) }
    return LibraryTabBar(scroll, ::applySelection).also { holder ->
        holderRef = holder
    }
}

private const val TAB_INDICATOR_HEIGHT_DP = 3
