package com.vinicius741.webnovelarchiver.feature.ai

import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import com.vinicius741.webnovelarchiver.data.repository.setShowAiCover
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import kotlinx.coroutines.launch

internal fun ScreenHost.addAiCoverDisplaySelector(
    container: LinearLayout,
    story: Story,
) {
    val colors = ThemeManager.colors
    container.text("Displayed cover", Type.LABEL_MEDIUM, colors.onSurfaceVariant)
    val group = RadioGroup(app).apply { orientation = RadioGroup.HORIZONTAL }
    listOf("Source", "Generated").forEachIndexed { index, label ->
        group.addView(
            RadioButton(app).apply {
                id = View.generateViewId()
                text = label
                setTextColor(colors.onSurface)
                buttonTintList = ColorStateList.valueOf(colors.primary)
                isChecked = story.showAiCover == (index == 1)
                setOnClickListener {
                    scope.launch {
                        coverUiAttempt {
                            repository.setShowAiCover(story.id, index == 1)
                            if (frameIsAiControls(story.id)) showAiControls(story.id)
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }
    container.addView(group)
}

internal fun ScreenHost.addAiCoverGenerationOptions(
    container: LinearLayout,
    story: Story,
    oneStep: Boolean,
) {
    val expanded = aiControlsScreenState.coverOptionsExpanded
    val options =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (story.id in expanded) View.VISIBLE else View.GONE
        }
    val disclosure = container.fullButton("Generation options", Btn.TEXT) {}

    fun describeExpansion() {
        disclosure.contentDescription = "Generation options, " + if (story.id in expanded) "expanded" else "collapsed"
    }
    describeExpansion()
    disclosure.setOnClickListener {
        if (!expanded.add(story.id)) expanded.remove(story.id)
        options.visibility = if (story.id in expanded) View.VISIBLE else View.GONE
        describeExpansion()
    }
    addAiContextChaptersRow(options, story, forCover = true)
    options.spacer(Space.SM)
    addAiCoverModeRow(options, story, oneStep)
    options.fullButton("Write your own prompt", Btn.TEXT) { editAiCoverPrompt(story, "") }
    container.addView(options)
}
