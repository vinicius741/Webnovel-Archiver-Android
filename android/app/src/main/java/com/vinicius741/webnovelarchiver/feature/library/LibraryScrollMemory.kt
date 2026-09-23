package com.vinicius741.webnovelarchiver.feature.library

import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView

/** Stores a tab's vertical offset even when ViewPager2 recycles the page view. */
internal fun RecyclerView.trackScrollInto(
    positions: MutableMap<String, Int>,
    key: String,
) {
    clearOnScrollListeners()
    addOnScrollListener(
        object : RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: RecyclerView,
                dx: Int,
                dy: Int,
            ) {
                positions[key] = recyclerView.computeVerticalScrollOffset()
            }
        },
    )
}

/** Waits for the first layout so the adapter has a measured scrolling range. */
internal fun RecyclerView.restoreScrollOnce(offset: Int) {
    viewTreeObserver.addOnGlobalLayoutListener(
        object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                scrollBy(0, offset)
            }
        },
    )
}
