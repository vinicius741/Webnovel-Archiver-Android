package com.vinicius741.webnovelarchiver.feature.ai

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiJobForegroundService
import com.vinicius741.webnovelarchiver.ai.CharacterJobKind
import com.vinicius741.webnovelarchiver.ai.CharacterJobState
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.characterPortraits
import com.vinicius741.webnovelarchiver.data.repository.characterRoster
import com.vinicius741.webnovelarchiver.data.repository.deleteCharacterPortrait
import com.vinicius741.webnovelarchiver.data.repository.latestCharacterPortraits
import com.vinicius741.webnovelarchiver.domain.model.CharacterPortrait
import com.vinicius741.webnovelarchiver.domain.model.CharacterRole
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryCharacter
import com.vinicius741.webnovelarchiver.feature.story.CoverViewerAction
import com.vinicius741.webnovelarchiver.feature.story.CoverViewerItem
import com.vinicius741.webnovelarchiver.feature.story.showCoverViewer
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeCover
import com.vinicius741.webnovelarchiver.ui.makeSectionHeader
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch
import java.io.File

/*
 * Characters tab of the AI Controls screen. Before the first run it is one card with "Find
 * characters"; afterwards the list (lead, main characters, supporting collapsed) with each
 * character's newest portrait as its thumbnail. Tapping a character opens its page inside the tab:
 * details, every portrait (tap for full size), and "Generate portrait". System Back returns to the
 * list. Jobs run on the process-wide CharacterJobCoordinator, so they survive leaving the screen.
 */

private const val THUMB_WIDTH_DP = 56
private const val THUMB_HEIGHT_DP = 84
internal const val HEADER_WIDTH_DP = 120

internal fun ScreenHost.addAiCharactersTab(
    container: LinearLayout,
    story: Story,
) {
    val slot = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    container.addView(slot)
    val job = app.appContainer.characterJobCoordinator.jobFor(story.id)
    val openId = aiControlsScreenState.openCharacters[story.id]
    scope.launch {
        val roster = repository.characterRoster(story.id)
        val thumbs = runCatching { repository.latestCharacterPortraits(story.id) }.getOrDefault(emptyMap())
        if (!frameIsAiControls(story.id) || aiControlsTab(story.id) != AiControlsTab.CHARACTERS) return@launch
        val open = roster?.firstOrNull { it.id == openId }
        if (open != null) {
            val portraits = runCatching { repository.characterPortraits(story.id, open.id) }.getOrDefault(emptyList())
            addCharacterPage(slot, story, open, portraits, job)
        } else {
            if (openId != null) aiControlsScreenState.openCharacters.remove(story.id)
            if (roster.isNullOrEmpty()) addFindCharactersCard(slot, story, job) else addCharacterList(slot, story, roster, thumbs, job)
        }
        val settings = repository.aiSettings.get()
        addAiModelFooter(
            slot,
            "Text" to settings.descriptionModel,
            "Image" to settings.imageModel,
        )
    }
}

/** True while the tab shows one character's page; system Back then returns to the list. */
internal fun ScreenHost.characterPageOpen(storyId: String): Boolean =
    aiControlsTab(storyId) == AiControlsTab.CHARACTERS && aiControlsScreenState.openCharacters[storyId] != null

internal fun ScreenHost.closeCharacterPage(storyId: String) {
    aiControlsScreenState.openCharacters.remove(storyId)
    showAiControls(storyId)
}

internal fun ScreenHost.canRunCharacters(story: Story): Boolean = story.isArchived != true && story.chapters.any { it.downloaded }

private fun ScreenHost.addFindCharactersCard(
    container: LinearLayout,
    story: Story,
    job: CharacterJobState?,
) {
    val colors = ThemeManager.colors
    container.addView(
        container.card {
            text("Meet the cast", Type.TITLE_MEDIUM, colors.onSurface)
            spacer(Space.XS)
            text(
                "Find the novel's characters, then paint a portrait of anyone you like. Names are counted on your phone " +
                    "for free; one AI call groups them into a list with roles and short bios, usually a few cents.",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setLineSpacing(dp(Space.XS).toFloat(), 1f) }
            if (!canRunCharacters(story)) {
                spacer(Space.SM)
                text("Download at least one chapter before finding characters.", Type.BODY_SMALL, colors.onSurfaceVariant)
                return@card
            }
            fullButton(
                if (job?.kind == CharacterJobKind.FIND) "Finding characters..." else "Find characters",
                Btn.FILLED,
                R.drawable.wna_group,
                enabled = job == null,
                topMarginDp = Space.MD,
                bottomMarginDp = 0,
            ) { startFindCharacters(story) }
            job?.let { addCharacterProgress(this, story.id, it) }
        },
    )
}

private fun ScreenHost.addCharacterList(
    container: LinearLayout,
    story: Story,
    roster: List<StoryCharacter>,
    thumbs: Map<String, File>,
    job: CharacterJobState?,
) {
    val colors = ThemeManager.colors
    val groups = roster.groupBy { it.role }
    groups[CharacterRole.PROTAGONIST]?.let { lead ->
        container.section("Lead")
        lead.forEach { container.addView(characterRow(story, it, thumbs[it.id], job)) }
    }
    groups[CharacterRole.MAJOR]?.let { major ->
        container.section("Main characters")
        major.forEach { container.addView(characterRow(story, it, thumbs[it.id], job)) }
    }
    groups[CharacterRole.SUPPORTING]?.let { supporting ->
        val expanded = story.id in aiControlsScreenState.expandedSupporting
        container.addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    makeSectionHeader(app, "Supporting (${supporting.size})"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    ImageView(app).apply {
                        setImageDrawable(
                            app.tintedIcon(
                                if (expanded) R.drawable.wna_chevron_up else R.drawable.wna_chevron_down,
                                colors.onSurfaceVariant,
                            ),
                        )
                    },
                    LinearLayout.LayoutParams(dp(Space.XL), dp(Space.XL)),
                )
                background =
                    ripple(roundedBg(android.graphics.Color.TRANSPARENT, dp(Space.SM).toFloat()), dp(Space.SM).toFloat(), colors.onSurface)
                isClickable = true
                contentDescription = if (expanded) "Hide supporting characters" else "Show supporting characters"
                setOnClickListener {
                    if (expanded) {
                        aiControlsScreenState.expandedSupporting -= story.id
                    } else {
                        aiControlsScreenState.expandedSupporting +=
                            story.id
                    }
                    showAiControls(story.id)
                }
            },
        )
        if (expanded) supporting.forEach { container.addView(characterRow(story, it, thumbs[it.id], job)) }
    }
    container.addView(
        container.card {
            text(
                "Tap a character to see their portraits or paint a new one. Finding characters again rebuilds the list; " +
                    "portraits stay with characters whose name is unchanged.",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setLineSpacing(dp(Space.XS).toFloat(), 1f) }
            if (canRunCharacters(story)) {
                fullButton(
                    if (job?.kind == CharacterJobKind.FIND) "Finding characters..." else "Find characters again",
                    Btn.OUTLINED,
                    R.drawable.wna_group,
                    enabled = job == null,
                    topMarginDp = Space.MD,
                    bottomMarginDp = 0,
                ) {
                    confirm(
                        "Find the characters again? This reads every downloaded chapter and makes one AI call with " +
                            "${repository.aiSettings.get().descriptionModel}, which uses your OpenRouter credits.",
                        confirmLabel = "Find characters",
                    ) { startFindCharacters(story) }
                }
            }
            job?.takeIf { it.kind == CharacterJobKind.FIND }?.let { addCharacterProgress(this, story.id, it) }
        },
    )
}

/** Thumbnail, name, role and chapters, a two-line bio; a running portrait shows on its row. */
private fun ScreenHost.characterRow(
    story: Story,
    character: StoryCharacter,
    thumb: File?,
    job: CharacterJobState?,
): View {
    val colors = ThemeManager.colors
    val radius = dp(ThemeManager.shapes.cardRadius).toFloat()
    val meta =
        listOfNotNull(
            character.role.label,
            character.span,
            character.mentions.takeIf { it > 0 }?.let { if (it == 1) "1 mention" else "%,d mentions".format(it) },
        ).joinToString(" · ")
    val cardView =
        LinearLayout(app).card {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(portraitThumb(character, thumb, THUMB_WIDTH_DP, THUMB_HEIGHT_DP))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(makeText(context, character.name, Type.TITLE_SMALL, colors.onSurface))
                    addView(makeText(context, meta, Type.LABEL_MEDIUM, colors.onSurfaceVariant))
                    if (character.bio.isNotBlank()) {
                        addView(
                            makeText(context, character.bio, Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                                maxLines = 2
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                setPadding(0, dp(Space.XS), 0, 0)
                            },
                        )
                    }
                    if (job?.kind == CharacterJobKind.PORTRAIT && job.characterId == character.id) {
                        addView(
                            makeText(
                                context,
                                "Painting a portrait...",
                                Type.LABEL_MEDIUM,
                                colors.tertiary,
                            ).apply { setPadding(0, dp(Space.XS), 0, 0) },
                        )
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                ImageView(context).apply { setImageDrawable(context.tintedIcon(R.drawable.wna_chevron_right, colors.onSurfaceVariant)) },
                LinearLayout.LayoutParams(dp(Space.LG), dp(Space.LG)),
            )
        }
    cardView.foreground = ripple(roundedBg(android.graphics.Color.TRANSPARENT, radius), radius, colors.onSurface)
    cardView.isClickable = true
    cardView.contentDescription = "${character.name}, ${character.role.label}. Open portraits"
    cardView.setOnClickListener {
        aiControlsScreenState.openCharacters[story.id] = character.id
        showAiControls(story.id)
    }
    return cardView
}

/** The newest portrait, or the character's initials on a cover-shaped tile. */
internal fun ScreenHost.portraitThumb(
    character: StoryCharacter,
    image: File?,
    widthDp: Int,
    heightDp: Int,
): View {
    if (image != null) {
        return makeCover(app, widthDp, heightDp).apply {
            loadImage(image, this)
            contentDescription = "Portrait of ${character.name}"
        }
    }
    val colors = ThemeManager.colors
    val initials =
        character.name
            .split(' ', '-')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.take(1).uppercase() }
    return TextView(app).apply {
        text = initials
        gravity = Gravity.CENTER
        textSize = if (widthDp >= HEADER_WIDTH_DP) 36f else 18f
        setTextColor(colors.onSurfaceVariant)
        background = roundedBg(colors.surfaceVariant, dp(ThemeManager.shapes.cardRadius).toFloat() * 0.7f)
        contentDescription = "No portrait of ${character.name} yet"
        layoutParams =
            LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)).apply { setMargins(0, 0, dp(Space.LG), 0) }
    }
}
