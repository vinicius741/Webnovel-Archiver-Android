package com.vinicius741.webnovelarchiver.feature.library

import android.widget.ScrollView

// Scroll capture/restore for the Library's own scrollers (the single grid's ScrollView and each
// ViewPager2 page's ScrollView). The scaffold's routeScrollPositions mechanism can't serve this
// screen: its restore runs at screen() build time, before a pager page exists or is measured.

/** Continuously records the vertical offset into [positions] under [key]. */
internal fun ScrollView.trackScrollInto(
    positions: MutableMap<String, Int>,
    key: String,
) {
    setOnScrollChangeListener { _, _, scrollY, _, _ -> positions[key] = scrollY }
}

/**
 * Applies a saved offset after the scroller's first layout; 0 is applied too so a recycled page
 * resets to top instead of inheriting the tab that last used the view (listeners run in
 * registration order, so the newest bind's restore wins). A plain `post {}` can run before the
 * pager page is measured, and ScrollView.scrollTo clamps to the not-yet-known (zero) range.
 */
internal fun ScrollView.restoreScrollOnce(offset: Int) {
    viewTreeObserver.addOnGlobalLayoutListener(
        object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // Remove via the live observer: the view may have merged observers since registration.
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                scrollTo(0, offset)
            }
        },
    )
}
