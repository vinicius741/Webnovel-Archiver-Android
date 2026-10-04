package com.vinicius741.webnovelarchiver.feature.cleanup

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.feature.settings.showSettings
import com.vinicius741.webnovelarchiver.feature.story.share
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.launch

internal fun ScreenHost.showCleanupRules() {
    val state = cleanupScreenState
    val mode = state.mode
    screen(
        route = AppRoute.CleanupRules,
        title = "Text Cleanup",
        onBack = { showSettings() },
        actions =
            listOf(
                AppBarAction(R.drawable.wna_share, "Export cleanup rules") {
                    scope.launch { share(repository.exportCleanupRules()) }
                },
            ),
    ) {
        // Tabs stay above the scrolling pane, even at the bottom of a long sentence list.
        row {
            CleanupMode.values().forEach { tab ->
                addView(
                    makeButton(context, tab.label, if (tab == mode) Btn.FILLED else Btn.TONAL) {
                        state.mode = tab
                        showCleanupRules()
                    }.apply { isSelected = tab == mode },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (tab == CleanupMode.REGEX) marginStart = dp(Space.SM)
                    },
                )
            }
        }
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                spacer(Space.SM)
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
