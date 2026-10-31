package com.vinicius741.webnovelarchiver.feature.ai

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.data.repository.listAiCoverVersions
import com.vinicius741.webnovelarchiver.data.repository.saveAiCoverPromptDraft
import com.vinicius741.webnovelarchiver.data.repository.setAiCover
import com.vinicius741.webnovelarchiver.data.repository.setShowAiCover
import com.vinicius741.webnovelarchiver.data.storage.AiCoverVersion
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.sha256Hex
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeCover
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Cover tab's strip of every cover the novel can show: the source cover first, then saved AI
// versions newest first. Tapping one makes it the displayed cover (free and reversible); a
// long-press opens its prompt. File-backed thumbnails keep the strip from decoding full images.

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

internal fun ScreenHost.addAiCoverStrip(
    container: LinearLayout,
    story: Story,
) {
    val slot = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    container.addView(slot)
    val pendingBytes = aiControlsScreenState.coverDrafts[story.id]?.bytes
    scope.launch {
        val (versions, newId) =
            coverUiAttempt {
                val versions = repository.listAiCoverVersions(story.id)
                versions to pendingBytes?.let { withContext(Dispatchers.Default) { sha256Hex(it) } }
            } ?: return@launch
        if (!frameIsAiControls(story.id)) return@launch
        val sourceInUse = !AiCoverPlanning.isAiCoverActive(story)
        val choices =
            buildList {
                story.coverUrl?.takeIf { it.isNotBlank() }?.let { add(CoverChoice("Source", it, sourceInUse, false, null)) }
                versions.forEachIndexed { index, version ->
                    add(CoverChoice("AI ${versions.size - index}", version.image, version.isCurrent, version.id == newId, version))
                }
            }
        if (choices.isEmpty()) {
            slot.text("No cover yet — make one below.", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
            return@launch
        }
        val strip = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        choices.forEach { choice -> strip.addView(coverChoiceView(story, choice)) }
        slot.addView(HorizontalScrollView(app).apply { addView(strip) })
        slot.text("Tap a cover to use it. Long-press to see its prompt.", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(0, dp(Space.XS), 0, dp(Space.SM))
        }
    }
}

private fun ScreenHost.coverChoiceView(
    story: Story,
    choice: CoverChoice,
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
            if (!choice.inUse) append(". Tap to use this cover")
        }
    frame.setOnClickListener { useCoverChoice(story, choice) }
    frame.setOnLongClickListener {
        showCoverChoiceDetails(story, choice)
        true
    }
    item.addView(frame)
    item.addView(
        makeText(app, if (choice.inUse) "${choice.label} · In use" else choice.label, Type.LABEL_SMALL, colors.onSurfaceVariant).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(Space.XS), 0, 0)
        },
    )
    return item
}

/** Makes [choice] the displayed cover; choosing anything also settles a waiting "New cover ready". */
private fun ScreenHost.useCoverChoice(
    story: Story,
    choice: CoverChoice,
) {
    if (choice.inUse) return
    if (story.isArchived == true) {
        toast("Archived snapshots are read-only")
        return
    }
    if (aiCoverOperationFor(story.id) != null) {
        toast("Wait for cover generation to finish")
        return
    }
    scope.launch {
        coverUiAttempt {
            val version = choice.version
            if (version == null) {
                repository.setShowAiCover(story.id, false)
            } else {
                val bytes = withContext(Dispatchers.IO) { runCatching { version.image.readBytes() }.getOrNull() }
                if (bytes == null) {
                    toast("This cover is no longer available")
                    return@coverUiAttempt
                }
                repository.setAiCover(story.id, bytes, version.mediaType)
            }
            aiControlsScreenState.coverDrafts[story.id]?.let { draft -> closeAiCoverDraft(story.id, draft.prompt) }
            if (frameIsAiControls(story.id)) showAiControls(story.id)
        }
    }
}

/** Larger view of one cover beside the one in use, with its prompt and the free reuse actions. */
private fun ScreenHost.showCoverChoiceDetails(
    story: Story,
    choice: CoverChoice,
) {
    if (!frameIsAiControls(story.id)) return
    val colors = ThemeManager.colors
    val (dialog, content) = makeAiCoverDialog(if (choice.version == null) "Source cover" else choice.label)
    val editable = story.isArchived != true && aiCoverOperationFor(story.id) == null
    if (choice.inUse) {
        content.addView(
            makeCover(app, 160, 240).apply { loadImage(choice.image, this) },
            LinearLayout.LayoutParams(dp(160), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
    } else {
        addAiCoverCompareRow(content, story, choice.image, "This cover")
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
            useCoverChoice(story, choice)
        }
    }
    if (version != null && version.prompt.isNotBlank() && editable && story.chapters.any { it.downloaded }) {
        content.fullButton("Put prompt in the box", Btn.OUTLINED) {
            dialog.dismiss()
            reuseCoverPrompt(story, version.prompt)
        }
    }
    content.fullButton("Close", Btn.TEXT) { dialog.dismiss() }
    dialog.show()
}

/** Reusing a prompt is free: it only fills the box. Generate from prompt starts the billable call. */
private fun ScreenHost.reuseCoverPrompt(
    story: Story,
    prompt: String,
) {
    scope.launch {
        coverUiAttempt {
            aiControlsScreenState.coverDrafts[story.id]?.let { draft -> closeAiCoverDraft(story.id, draft.prompt) }
            repository.saveAiCoverPromptDraft(story.id, prompt)
            aiControlsScreenState.coverPrompts[story.id] = prompt
            if (frameIsAiControls(story.id)) showAiControls(story.id)
        }
    }
}
