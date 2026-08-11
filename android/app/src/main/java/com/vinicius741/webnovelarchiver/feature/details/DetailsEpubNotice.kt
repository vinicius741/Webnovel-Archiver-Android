package com.vinicius741.webnovelarchiver.feature.details

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.story.generateConfiguredEpub
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.disableButton
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText

/** "EPUB out of date" notice with an inline Regenerate button (D6). */
internal fun ScreenHost.buildStaleEpubNotice(
    story: Story,
    isBusy: Boolean,
): LinearLayout =
    LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(
            makeText(app, "EPUB out of date", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            },
        )
        val regenerateButton =
            makeButton(app, "Regenerate", Btn.TEXT, R.drawable.wna_refresh) {
                val config =
                    story.epubConfig ?: EpubConfig(
                        maxChaptersPerEpub = repository.getSettings().maxChaptersPerEpub,
                        rangeStart = 1,
                        rangeEnd = story.chapters.size,
                        startAtBookmark = false,
                    )
                generateConfiguredEpub(story, config)
            }
        if (isBusy) disableButton(regenerateButton)
        addView(
            regenerateButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Space.XS)
            },
        )
    }
