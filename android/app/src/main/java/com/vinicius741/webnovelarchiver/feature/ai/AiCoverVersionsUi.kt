package com.vinicius741.webnovelarchiver.feature.ai

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.data.storage.AiCoverVersion
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.story.CoverViewerAction
import com.vinicius741.webnovelarchiver.feature.story.CoverViewerItem
import com.vinicius741.webnovelarchiver.feature.story.showCoverViewer
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.activeCoverSource
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeCover
import com.vinicius741.webnovelarchiver.ui.makeSectionHeader
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast

// The Cover tab's strip of every cover the novel can show: the source cover first, then saved AI
// versions newest first. Tapping one opens every version full screen (swipe between them, read the
// prompt, Use this cover); a long-press opens its prompt and reuse actions. File-backed thumbnails
// keep the strip from decoding full images.

private const val STRIP_THUMB_WIDTH_DP = 96
private const val STRIP_THUMB_HEIGHT_DP = 144

/** One strip entry; [version] is null for the source cover. */
private class CoverChoice(
    val label: String,
    val image: Any,
    val inUse: Boolean,
    val isNew: Boolean,
    val version: AiCoverVersion?,
)

/** The strip's count label and the slot its covers fill once the saved versions load. */
internal class CoverStripSlot(
    val countLabel: TextView,
    val slot: LinearLayout,
)

internal fun ScreenHost.addAiCoverStrip(
    container: LinearLayout,
    render: AiTabRender,
) {
    val colors = ThemeManager.colors
    val countLabel = makeText(app, "", Type.LABEL_MEDIUM, colors.onSurfaceVariant)
    container.addView(
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(makeSectionHeader(app, "Covers"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(countLabel)
        },
    )
    val slot = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    container.addView(slot)
    val strip = CoverStripSlot(countLabel, slot)
    render.coverStrip = strip
    fillCoverStrip(strip, render.story, render.model, render.state)
}

/** Fills the strip from [state]'s saved versions; empty until they load. */
internal fun ScreenHost.fillCoverStrip(
    strip: CoverStripSlot,
    story: Story,
    model: AiControlsScreenModel,
    state: AiControlsUiState,
) {
    val (versions, newId) = state.coverVersions ?: return
    val colors = ThemeManager.colors
    val slot = strip.slot
    slot.removeAllViews()
    val sourceInUse = !AiCoverPlanning.isAiCoverActive(story)
    val choices =
        buildList {
            story.coverUrl?.takeIf { it.isNotBlank() }?.let { add(CoverChoice("Source", it, sourceInUse, false, null)) }
            versions.forEachIndexed { index, version ->
                add(CoverChoice("AI ${versions.size - index}", version.image, version.isCurrent, version.id == newId, version))
            }
        }
    if (choices.isEmpty()) {
        strip.countLabel.text = ""
        slot.text("No cover yet — make one below.", Type.BODY_SMALL, colors.onSurfaceVariant)
        return
    }
    strip.countLabel.text = if (choices.size == 1) "1 version" else "${choices.size} versions"
    val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    choices.forEachIndexed { index, choice ->
        row.addView(coverChoiceView(story, model, choice) { browseCoverChoices(model, choices, index) })
    }
    slot.addView(
        HorizontalScrollView(app).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        },
    )
    slot.text("Tap a cover to see it full size, compare versions and choose one.", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
        setPadding(0, dp(Space.SM), 0, dp(Space.SM))
    }
}

/** Every version full screen: swipe between them, read each prompt, and use one. */
private fun ScreenHost.browseCoverChoices(
    model: AiControlsScreenModel,
    choices: List<CoverChoice>,
    start: Int,
) {
    var inUse = choices.indexOfFirst { it.inUse }
    var committing = false

    fun subtitle(index: Int): String? =
        when {
            index == inUse -> "In use"
            choices[index].isNew -> "New"
            else -> null
        }
    val items =
        choices
            .mapIndexed { index, choice ->
                CoverViewerItem(
                    choice.image,
                    if (choice.version ==
                        null
                    ) {
                        "Source cover"
                    } else {
                        choice.label
                    },
                    subtitle(index),
                    choice.version?.prompt,
                )
            }.toMutableList()
    showCoverViewer(
        items,
        start,
        actions = { index ->
            listOf(
                if (index == inUse) {
                    CoverViewerAction(IN_USE_ACTION, "In use", R.drawable.wna_check, enabled = false)
                } else {
                    CoverViewerAction(USE_THIS_ACTION, "Use this cover", R.drawable.wna_check, primary = true)
                },
            )
        },
    ) { action, viewer ->
        // One choice at a time, so two quick taps cannot commit out of order.
        if (action != USE_THIS_ACTION || committing) return@showCoverViewer
        val index = viewer.index
        committing = true
        val started =
            model.useCoverVersion(choices[index].version) { used ->
                committing = false
                if (!used) return@useCoverVersion
                val previous = inUse
                inUse = index
                listOf(previous, index).filter { it in items.indices }.forEach { items[it] = items[it].copy(subtitle = subtitle(it)) }
                toast("Using ${items[index].title}")
                viewer.refresh()
            }
        if (!started) committing = false
    }
}

private fun ScreenHost.coverChoiceView(
    story: Story,
    model: AiControlsScreenModel,
    choice: CoverChoice,
    onOpen: () -> Unit,
): LinearLayout {
    val colors = ThemeManager.colors
    val radius = dp(ThemeManager.shapes.cardRadius).toFloat() * 0.7f
    val item =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(Space.SM), dp(Space.MD), dp(Space.XS))
        }
    val frame = FrameLayout(app)
    frame.addView(
        makeCover(app, STRIP_THUMB_WIDTH_DP, STRIP_THUMB_HEIGHT_DP).apply {
            loadImage(choice.image, this)
            layoutParams = FrameLayout.LayoutParams(dp(STRIP_THUMB_WIDTH_DP), dp(STRIP_THUMB_HEIGHT_DP))
        },
    )
    if (choice.inUse) {
        // Outline plus a corner check: the cover in use reads at a glance without its label.
        frame.foreground = strokeBg(Color.TRANSPARENT, radius, colors.primary, dp(3))
        val size = dp(24)
        frame.addView(
            ImageView(app).apply {
                setImageDrawable(app.tintedIcon(R.drawable.wna_check, colors.onPrimary))
                setPadding(dp(Space.XS), dp(Space.XS), dp(Space.XS), dp(Space.XS))
                background = roundedBg(colors.primary, size / 2f)
            },
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(Space.XS), dp(Space.XS), 0) },
        )
    }
    if (choice.isNew) {
        frame.addView(
            makeBadge(app, "New", colors.tertiaryContainer, colors.onTertiaryContainer),
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START,
                ).apply { setMargins(dp(Space.XS), 0, 0, dp(Space.XS)) },
        )
    }
    frame.contentDescription =
        buildString {
            append(choice.label)
            if (choice.inUse) append(", in use")
            if (choice.isNew) append(", new")
            append(". Tap to view it full size")
        }
    frame.setOnClickListener { onOpen() }
    frame.setOnLongClickListener {
        showCoverChoiceDetails(story, model, choice)
        true
    }
    item.addView(frame)
    item.addView(
        makeText(app, choice.label, Type.LABEL_LARGE, if (choice.inUse) colors.primary else colors.onSurfaceVariant).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(Space.XS + 2), 0, 0)
        },
    )
    return item
}

/** Larger view of one cover beside the one in use, with its prompt and the free reuse actions. */
private fun ScreenHost.showCoverChoiceDetails(
    story: Story,
    model: AiControlsScreenModel,
    choice: CoverChoice,
) {
    val colors = ThemeManager.colors
    val (dialog, content) = makeAiCoverDialog(if (choice.version == null) "Source cover" else choice.label)
    val editable = story.isArchived != true
    // Reusing a prompt rewrites the prompt box, which a running cover job owns until it finishes.
    val canReusePrompt = editable && model.state.value.coverJob == null
    if (choice.inUse) {
        content.addView(
            makeCover(app, 160, 240).apply {
                loadImage(choice.image, this)
                setOnClickListener { showCoverViewer(listOf(CoverViewerItem(choice.image, choice.label))) }
            },
            LinearLayout.LayoutParams(dp(160), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
    } else {
        addAiCoverCompareRow(content, story, choice.image, "This cover") { index ->
            showCoverViewer(
                listOfNotNull(
                    activeCoverSource(story)?.let {
                        CoverViewerItem(it, "Current cover")
                    },
                    CoverViewerItem(choice.image, choice.label),
                ),
                index,
            )
        }
    }
    val version = choice.version
    if (version != null) {
        content.text("Image prompt", Type.LABEL_MEDIUM, colors.onSurfaceVariant).apply { setPadding(0, dp(Space.MD), 0, 0) }
        content
            .text(
                version.prompt.ifBlank { "The prompt for this older cover is unavailable." },
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply {
                setLineSpacing(dp(Space.XS).toFloat(), 1f)
                setTextIsSelectable(true)
            }
    }
    content.addView(View(app), LinearLayout.LayoutParams(1, dp(Space.MD)))
    if (!choice.inUse && editable) {
        content.fullButton("Use this cover", Btn.FILLED, R.drawable.wna_check) {
            dialog.dismiss()
            model.useCoverVersion(choice.version)
        }
    }
    if (version != null && version.prompt.isNotBlank() && canReusePrompt && story.chapters.any { it.downloaded }) {
        content.fullButton("Reuse this prompt", Btn.OUTLINED) {
            dialog.dismiss()
            model.reuseCoverPrompt(version.prompt)
        }
    }
    // Rarely needed, so it lives only here: drop a bad generation from history. Never the cover in use
    // or an unapplied preview (closing the preview would save it back).
    if (version != null && !choice.inUse && !choice.isNew && editable) {
        content
            .fullButton("Delete this cover", Btn.TEXT) {
                confirm("Delete ${choice.label}? Its image and prompt are removed from this device.", "Delete") {
                    dialog.dismiss()
                    model.deleteCoverVersion(version)
                }
            }.apply {
                setTextColor(colors.error)
                app.tintedIcon(R.drawable.wna_delete, colors.error)?.let {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(it, null, null, null)
                    compoundDrawablePadding = dp(6)
                }
            }
    }
    content.fullButton("Close", Btn.TEXT) { dialog.dismiss() }
    dialog.show()
}

private const val USE_THIS_ACTION = "use"
private const val IN_USE_ACTION = "in_use"
