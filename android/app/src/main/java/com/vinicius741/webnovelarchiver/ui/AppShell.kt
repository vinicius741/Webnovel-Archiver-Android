package com.vinicius741.webnovelarchiver.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ShellDestination
import com.vinicius741.webnovelarchiver.navigation.ShellNavigationPlanning

/** Screens at least this wide (the Fold's inner display) use the right-hand rail instead of the bar. */
internal const val RAIL_MIN_WIDTH_DP = 600

/** Width (dp) left for screens beside the rail: the window width minus the rail when it shows. */
internal fun Context.shellContentWidthDp(): Int {
    val width = resources.configuration.screenWidthDp
    return if (width >= RAIL_MIN_WIDTH_DP) width - RAIL_WIDTH_DP else width
}

private const val BAR_HEIGHT_DP = 72
private const val RAIL_WIDTH_DP = 84
private const val PILL_WIDTH_DP = 60
private const val PILL_HEIGHT_DP = 32
private const val FADE_HEIGHT_DP = 20
private const val BADGE_MAX = 99

/**
 * The app's frame around every screen (v3): the screen itself, then a solid bottom shelf holding
 * the docked TTS mini player and the bottom navigation bar; on the inner display the destinations
 * move to a rail on the right edge. Screens end where the shelf begins, so the player and bar
 * never cover content; a short fade at the screen's bottom edge shows rows sliding under it.
 */
class AppShell internal constructor(
    private val context: Context,
    /** The screen frame every `showXxx()` renders into. */
    val frame: FrameLayout,
    private val systemBarBottom: () -> Int,
    private val systemBarTop: () -> Int,
    private val onDestination: (ShellDestination) -> Unit,
    private val onAddStory: () -> Unit,
) {
    private val colors = ThemeManager.colors

    /** Root view to set as the activity content. */
    val root: LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    /** Overlays that float over the screen but above the shelf (AI job chip, simulation badge). */
    val overlayHost: FrameLayout = FrameLayout(context)

    /** Where the TTS mini player docks; its visibility follows the bar inside it. */
    val miniSlot: FrameLayout =
        FrameLayout(context).apply {
            setPadding(context.dp(Spacing.SM), context.dp(Spacing.XS + 2), context.dp(Spacing.SM), context.dp(Spacing.XS))
        }

    private val fade =
        View(context).apply {
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(colors.background and 0x00FFFFFF, colors.background),
                )
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    private val bottomBar =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
    private val shelf = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rail =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
    private val barItems = mutableMapOf<ShellDestination, NavItem>()
    private val railItems = mutableMapOf<ShellDestination, NavItem>()
    private var navVisible = false
    private var railMode = false
    private var miniWanted = true

    init {
        overlayHost.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlayHost.addView(
            fade,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(FADE_HEIGHT_DP), Gravity.BOTTOM),
        )
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(overlayHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        shelf.setBackgroundColor(colors.background)
        shelf.addView(miniSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomBar.setBackgroundColor(colors.navSurface)
        ShellDestination.entries.forEach { dest ->
            val item = NavItem(context, dest, rail = false) { onDestination(dest) }
            barItems[dest] = item
            bottomBar.addView(item.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        shelf.addView(
            View(context).apply { setBackgroundColor(colors.line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1).coerceAtLeast(1)),
        )
        shelf.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(shelf, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        buildRail()
        root.addView(rail, LinearLayout.LayoutParams(context.dp(RAIL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        root.setBackgroundColor(colors.background)
        applyChrome()
        miniSlot.setOnHierarchyChangeListener(
            object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(
                    parent: View?,
                    child: View?,
                ) = applyChrome()

                override fun onChildViewRemoved(
                    parent: View?,
                    child: View?,
                ) = applyChrome()
            },
        )
    }

    private fun buildRail() {
        rail.gravity = Gravity.CENTER_HORIZONTAL
        // A hairline on the rail's inner (left) edge.
        rail.background =
            android.graphics.drawable.LayerDrawable(
                arrayOf(
                    android.graphics.drawable.ColorDrawable(colors.line),
                    android.graphics.drawable.InsetDrawable(
                        android.graphics.drawable.ColorDrawable(colors.navSurface),
                        context.dp(1),
                        0,
                        0,
                        0,
                    ),
                ),
            )
        val add =
            ImageView(context).apply {
                contentDescription = "Add story"
                setImageDrawable(context.tintedIcon(R.drawable.wna_add, colors.onPrimaryContainer))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background =
                    ripple(
                        roundedBg(colors.primaryContainer, context.dp(16).toFloat()),
                        context.dp(16).toFloat(),
                        colors.onPrimaryContainer,
                    )
                setOnClickListener { onAddStory() }
            }
        rail.addView(
            add,
            LinearLayout.LayoutParams(context.dp(52), context.dp(52)).apply {
                topMargin = context.dp(Spacing.LG)
            },
        )
        // Destinations sit low on the inner screen, within reach of the thumb holding it.
        rail.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        ShellDestination.entries.forEach { dest ->
            val item = NavItem(context, dest, rail = true) { onDestination(dest) }
            railItems[dest] = item
            rail.addView(item.view, LinearLayout.LayoutParams(context.dp(RAIL_WIDTH_DP), context.dp(BAR_HEIGHT_DP)))
        }
    }

    /** Called by `screen()` for every rendered route, before the body is built. */
    fun onRoute(history: List<AppRoute>) {
        val route = history.lastOrNull() ?: return
        navVisible = ShellNavigationPlanning.showsNavigation(route)
        miniWanted = ShellNavigationPlanning.showsMiniPlayer(route)
        railMode = context.resources.configuration.screenWidthDp >= RAIL_MIN_WIDTH_DP
        navSuppressed = false
        val selected = ShellNavigationPlanning.destinationOf(history)
        barItems.forEach { (dest, item) -> item.setSelected(dest == selected) }
        railItems.forEach { (dest, item) -> item.setSelected(dest == selected) }
        applyChrome()
    }

    /** Count badge (or a dot when [count] is 0 and [dot] is true) on a destination; null clears it. */
    fun setBadge(
        destination: ShellDestination,
        count: Int?,
        dot: Boolean = false,
    ) {
        barItems[destination]?.setBadge(count, dot)
        railItems[destination]?.setBadge(count, dot)
    }

    private var navSuppressed = false

    /**
     * Hides the navigation bar / rail (and the mini player) while a screen shows its own bottom
     * action bar, e.g. a selection mode. Cleared automatically on the next route render.
     */
    fun setNavigationSuppressed(suppressed: Boolean) {
        if (navSuppressed == suppressed) return
        navSuppressed = suppressed
        applyChrome()
    }

    /** The `rerender` the last `screen()` call saw, so the scaffold can spot screens that set none. */
    internal var rerenderAtLastScreen: (() -> Unit)? = null

    /** Whether the route currently rendered wants the mini player (it decides its own visibility). */
    val miniPlayerAllowed: Boolean get() = miniWanted

    private fun applyChrome() {
        val miniShown =
            miniWanted && !navSuppressed && (0 until miniSlot.childCount).any { miniSlot.getChildAt(it).visibility == View.VISIBLE }
        miniSlot.visibility = if (miniShown) View.VISIBLE else View.GONE
        val barShown = navVisible && !railMode && !navSuppressed
        bottomBar.visibility = if (barShown) View.VISIBLE else View.GONE
        (shelf.getChildAt(1)).visibility = if (barShown) View.VISIBLE else View.GONE
        rail.visibility = if (navVisible && railMode && !navSuppressed) View.VISIBLE else View.GONE
        rail.setPadding(0, systemBarTop(), 0, systemBarBottom() + context.dp(Spacing.SM))
        // The shelf always owns the gesture-bar inset, so screens never pad for it themselves.
        val inset = systemBarBottom()
        if (barShown) {
            bottomBar.layoutParams.height = context.dp(BAR_HEIGHT_DP) + inset
            bottomBar.setPadding(0, 0, 0, inset)
            shelf.setPadding(0, 0, 0, 0)
        } else {
            shelf.setPadding(0, 0, 0, inset)
        }
        bottomBar.requestLayout()
        fade.visibility = if (miniShown || barShown) View.VISIBLE else View.GONE
    }

    /** Re-evaluates rail vs bar after a fold / rotation without a route change. */
    fun onConfigurationChanged() {
        railMode = context.resources.configuration.screenWidthDp >= RAIL_MIN_WIDTH_DP
        applyChrome()
    }

    /** Re-applies chrome after the mini player shows or hides. */
    fun onMiniPlayerVisibilityChanged() = applyChrome()

    private class NavItem(
        context: Context,
        destination: ShellDestination,
        rail: Boolean,
        onClick: () -> Unit,
    ) {
        private val colors = ThemeManager.colors
        private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
        private val pill = FrameLayout(context)
        private val label =
            TextView(context).apply {
                text = destination.label()
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
            }
        private val badge =
            TextView(context).apply {
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(colors.onPrimary)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(context.dp(4), 0, context.dp(4), 0)
                minWidth = context.dp(16)
                background = roundedBg(colors.primary, context.dp(8).toFloat())
                visibility = View.GONE
            }
        private val iconRes = destination.icon()
        val view: LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = destination.label()
                background = selectableRipple(colors.onSurface)
                pill.addView(icon, FrameLayout.LayoutParams(context.dp(24), context.dp(24), Gravity.CENTER))
                pill.addView(
                    badge,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(16), Gravity.TOP or Gravity.START).apply {
                        leftMargin = context.dp(if (rail) 34 else 36)
                        topMargin = context.dp(0)
                    },
                )
                // The badge may overhang the pill ("99+"), so neither clips it.
                clipChildren = false
                clipToPadding = false
                pill.clipChildren = false
                addView(pill, LinearLayout.LayoutParams(context.dp(if (rail) 56 else PILL_WIDTH_DP), context.dp(PILL_HEIGHT_DP)))
                addView(
                    label,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(4)
                    },
                )
                setOnClickListener { onClick() }
            }

        fun setSelected(selected: Boolean) {
            val fg = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
            icon.setImageDrawable(view.context.tintedIcon(iconRes, fg))
            pill.background = if (selected) roundedBg(colors.primaryContainer, view.context.dp(16).toFloat()) else null
            label.setTextColor(if (selected) colors.onSurface else colors.onSurfaceVariant)
            label.typeface = Typeface.create(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
            view.isSelected = selected
        }

        fun setBadge(
            count: Int?,
            dot: Boolean,
        ) {
            when {
                count != null && count > 0 -> {
                    badge.text = if (count > BADGE_MAX) "$BADGE_MAX+" else count.toString()
                    badge.layoutParams = badge.layoutParams.apply { height = view.context.dp(16) }
                    badge.minWidth = view.context.dp(16)
                    badge.visibility = View.VISIBLE
                }
                dot -> {
                    badge.text = ""
                    badge.minWidth = view.context.dp(8)
                    badge.layoutParams = badge.layoutParams.apply { height = view.context.dp(8) }
                    badge.visibility = View.VISIBLE
                }
                else -> badge.visibility = View.GONE
            }
        }
    }
}

private fun ShellDestination.label(): String =
    when (this) {
        ShellDestination.LIBRARY -> "Library"
        ShellDestination.UPDATES -> "Updates"
        ShellDestination.LISTEN -> "Listen"
        ShellDestination.DOWNLOADS -> "Downloads"
        ShellDestination.SETTINGS -> "Settings"
    }

private fun ShellDestination.icon(): Int =
    when (this) {
        ShellDestination.LIBRARY -> R.drawable.wna_menu_book
        ShellDestination.UPDATES -> R.drawable.wna_updates
        ShellDestination.LISTEN -> R.drawable.wna_headphones
        ShellDestination.DOWNLOADS -> R.drawable.wna_download
        ShellDestination.SETTINGS -> R.drawable.wna_settings
    }
