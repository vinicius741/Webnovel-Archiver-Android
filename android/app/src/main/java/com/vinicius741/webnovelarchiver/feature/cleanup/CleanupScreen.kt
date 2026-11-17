package com.vinicius741.webnovelarchiver.feature.cleanup

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vinicius741.webnovelarchiver.feature.settings.showSettings
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.SegmentedSelector
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.verticalFill

internal fun ScreenHost.showCleanupRules() {
    rerender = { showCleanupRules() }
    val state = cleanupScreenState
    val mode = state.mode
    screen(
        route = AppRoute.CleanupRules,
        title = "Text cleanup",
        subtitle = "Applied to read-aloud and downloaded text",
    ) {
        // The segmented control stays above the scrolling pane, even at the bottom of a long list.
        val counts = listOf(repository.sentenceRemovalList.get().size, repository.regexRules.get().size)
        val modes = CleanupMode.values().toList()
        addView(
            SegmentedSelector(context, modes.map { it.label }, modes.indexOf(mode), counts) { index ->
                state.mode = modes[index]
                showCleanupRules()
            }.view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.XS)
            },
        )
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                spacer(Space.LG)
            }
        when (mode) {
            CleanupMode.SENTENCES -> buildSentenceRules(body)
            CleanupMode.REGEX -> buildRegexRules(body)
        }
        addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(body)
                val scrollY = state.scrollPositions[mode] ?: 0
                post { scrollTo(0, scrollY) }
                setOnScrollChangeListener { _, _, y, _, _ -> state.scrollPositions[mode] = y }
            },
            verticalFill(),
        )
    }
}
