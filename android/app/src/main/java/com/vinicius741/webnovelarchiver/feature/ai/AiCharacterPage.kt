package com.vinicius741.webnovelarchiver.feature.ai

import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.ai.AiJobForegroundService
import com.vinicius741.webnovelarchiver.ai.CharacterJobKind
import com.vinicius741.webnovelarchiver.ai.CharacterJobState
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.deleteCharacterPortrait
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
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

// One character's page inside the Characters tab: details, every portrait (tap for full size, with
// its prompt and Delete), and "Generate portrait". System Back returns to the list.

private const val HEADER_HEIGHT_DP = 180
private const val STRIP_WIDTH_DP = 96
private const val STRIP_HEIGHT_DP = 144
private const val DELETE_ACTION = "delete"

internal fun ScreenHost.addCharacterPage(
    container: LinearLayout,
    story: Story,
    character: StoryCharacter,
    portraits: List<CharacterPortrait>,
    job: CharacterJobState?,
) {
    val colors = ThemeManager.colors
    container.addView(
        makeText(app, "‹ All characters", Type.LABEL_LARGE, colors.primary).apply {
            setPadding(dp(Space.XS), dp(Space.SM), dp(Space.XS), dp(Space.SM))
            background =
                ripple(roundedBg(android.graphics.Color.TRANSPARENT, dp(Space.SM).toFloat()), dp(Space.SM).toFloat(), colors.onSurface)
            isClickable = true
            setOnClickListener { closeCharacterPage(story.id) }
        },
    )
    addCharacterHeader(container, story, character, portraits)
    if (portraits.isNotEmpty()) addPortraitStrip(container, story, character, portraits)
    addNewPortraitCard(container, story, character, portraits, job)
}

private fun ScreenHost.addCharacterHeader(
    container: LinearLayout,
    story: Story,
    character: StoryCharacter,
    portraits: List<CharacterPortrait>,
) {
    val colors = ThemeManager.colors
    container.addView(
        container.card {
            orientation = LinearLayout.HORIZONTAL
            addView(
                portraitThumb(character, portraits.firstOrNull()?.image, HEADER_WIDTH_DP, HEADER_HEIGHT_DP).apply {
                    if (portraits.isNotEmpty()) setOnClickListener { browsePortraits(story, character, portraits, 0) }
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(makeText(context, character.name, Type.TITLE_LARGE, colors.onSurface))
                    addView(
                        makeBadge(context, character.role.label, colors.tertiaryContainer, colors.onTertiaryContainer),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(Space.XS)
                            bottomMargin = dp(Space.XS)
                        },
                    )
                    listOfNotNull(
                        character.span,
                        character.mentions.takeIf { it > 0 }?.let { "%,d mentions".format(it) },
                    ).takeIf { it.isNotEmpty() }?.let {
                        addView(
                            makeText(context, it.joinToString(" · "), Type.LABEL_MEDIUM, colors.onSurfaceVariant),
                        )
                    }
                    if (character.aliases.isNotEmpty()) {
                        addView(
                            makeText(
                                context,
                                "Also called ${character.aliases.joinToString(", ")}",
                                Type.BODY_SMALL,
                                colors.onSurfaceVariant,
                            ).apply {
                                setPadding(0, dp(Space.XS), 0, 0)
                            },
                        )
                    }
                    if (character.bio.isNotBlank()) {
                        addView(
                            makeText(context, character.bio, Type.BODY_SMALL, colors.onSurface).apply {
                                setPadding(0, dp(Space.SM), 0, 0)
                                setLineSpacing(dp(Space.XS).toFloat(), 1f)
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        },
    )
}

private fun ScreenHost.addPortraitStrip(
    container: LinearLayout,
    story: Story,
    character: StoryCharacter,
    portraits: List<CharacterPortrait>,
) {
    container.section(if (portraits.size == 1) "Portrait" else "Portraits (${portraits.size})")
    val strip = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    portraits.forEachIndexed { index, portrait ->
        strip.addView(
            makeCover(app, STRIP_WIDTH_DP, STRIP_HEIGHT_DP).apply {
                loadImage(portrait.image, this)
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(Space.SM), dp(Space.MD), dp(Space.SM))
                contentDescription = "Portrait ${portraits.size - index} of ${character.name}. Tap to view it full size"
                setOnClickListener { browsePortraits(story, character, portraits, index) }
            },
        )
    }
    container.addView(
        HorizontalScrollView(app).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        },
    )
}

private fun ScreenHost.addNewPortraitCard(
    container: LinearLayout,
    story: Story,
    character: StoryCharacter,
    portraits: List<CharacterPortrait>,
    job: CharacterJobState?,
) {
    val colors = ThemeManager.colors
    container.section("New portrait")
    container.addView(
        container.card {
            val settings = repository.aiSettings.get()
            val usesCoverScan = character.role == CharacterRole.PROTAGONIST && story.aiCoverContextChapterIndices.isNullOrEmpty()
            val how =
                if (usesCoverScan) {
                    "Uses the passages the cover scan found about the lead, at the age set in Story phase on the Cover tab. " +
                        "That scan is free once a cover was made; otherwise it checks about 60 chapters first (around 2 cents). "
                } else {
                    "Your phone finds the chapters that describe ${character.name}, then the decision model checks up to 16 of " +
                        "them for passages about their looks. "
                }
            val style = if (AiCoverPlanning.isAiCoverActive(story)) ", matching your current cover's art style," else ""
            text(
                how + "${settings.descriptionModel} writes the prompt$style and ${settings.imageModel} paints it " +
                    "(the image is most of the cost).",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            ).apply { setLineSpacing(dp(Space.XS).toFloat(), 1f) }
            if (!canRunCharacters(story)) {
                text(
                    if (story.isArchived == true) "Archived snapshots are read-only." else "Download chapters before painting a portrait.",
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).apply { setPadding(0, dp(Space.SM), 0, 0) }
                return@card
            }
            val mine = job?.kind == CharacterJobKind.PORTRAIT && job.characterId == character.id
            fullButton(
                when {
                    mine -> "Painting..."
                    portraits.isEmpty() -> "Generate portrait"
                    else -> "Generate another portrait"
                },
                Btn.FILLED,
                R.drawable.wna_auto_awesome,
                enabled = !characterJobsBusy(),
                topMarginDp = Space.MD,
                bottomMarginDp = 0,
            ) { startPortrait(story, character, firstRun = portraits.isEmpty()) }
            if (job != null && !mine) {
                text("Wait for the current character job to finish.", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                    setPadding(0, dp(Space.SM), 0, 0)
                }
            }
            addOtherNovelBusyNote(this, job)
            if (mine) job?.let { addCharacterProgress(this, story.id, it) }
        },
    )
}

/** Every portrait full screen, newest first, with its prompt and a Delete action. */
private fun ScreenHost.browsePortraits(
    story: Story,
    character: StoryCharacter,
    portraits: List<CharacterPortrait>,
    start: Int,
) {
    val items =
        portraits.mapIndexed { index, portrait ->
            CoverViewerItem(portrait.image, "${character.name} · ${portraits.size - index}", prompt = portrait.prompt.ifBlank { null })
        }
    showCoverViewer(items, start, actions = { listOf(CoverViewerAction(DELETE_ACTION, "Delete", R.drawable.wna_delete)) }) { _, viewer ->
        val portrait = portraits[viewer.index]
        viewer.close()
        confirm("Delete this portrait of ${character.name}?", confirmLabel = "Delete") {
            scope.launch {
                runCatching { repository.deleteCharacterPortrait(story.id, character.id, portrait.id) }
                    .onFailure { toast("Could not delete the portrait") }
                if (frameIsAiControls(story.id)) showAiControls(story.id)
            }
        }
    }
}
