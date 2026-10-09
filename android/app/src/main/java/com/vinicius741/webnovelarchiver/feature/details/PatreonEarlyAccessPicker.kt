package com.vinicius741.webnovelarchiver.feature.details

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.setPatreonEarlyAccess
import com.vinicius741.webnovelarchiver.domain.model.PatreonEarlyAccessLink
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.feature.settings.showPatreonAccount
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.PatreonApi
import com.vinicius741.webnovelarchiver.source.PatreonCollection
import com.vinicius741.webnovelarchiver.source.PatreonSession
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Lets the user pick which collection on the creator's Patreon holds this novel's chapters. The
 * collections list is public, so linking works signed out; reading locked posts needs a session.
 */
internal fun ScreenHost.showPatreonEarlyAccessPicker(story: Story) {
    if (!StoryActionGuards.canModifyStory(story)) return toast(StoryActionGuards.archivedActionMessage("Linking Patreon"))
    val creatorUrl = story.patreonUrl ?: return toast("This novel has no Patreon link")
    toast("Loading Patreon collections...")
    scope.launch {
        val api = PatreonApi(app.appContainer.network)
        val loaded =
            try {
                val campaignId =
                    story.patreonEarlyAccess?.campaignId?.takeIf { it.isNotBlank() }
                        ?: api.campaignId(creatorUrl)
                        ?: error("Could not find this creator's Patreon campaign")
                campaignId to api.collections(campaignId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toast("Couldn't load Patreon collections: ${error.message ?: error.javaClass.simpleName}")
                return@launch
            }
        val (campaignId, collections) = loaded
        // The user may have left Details while collections loaded; don't pop a picker elsewhere.
        if (navigator.current != AppRoute.Details(story.id)) return@launch
        showCollectionOptions(story, campaignId, PatreonEarlyAccessPlanning.rankCollections(story.title, collections))
    }
}

private fun ScreenHost.showCollectionOptions(
    story: Story,
    campaignId: String,
    collections: List<PatreonCollection>,
) {
    val current = story.patreonEarlyAccess
    val items = mutableListOf<OptionsDialogItem>()
    if (collections.isEmpty()) {
        items += OptionsDialogItem.Section("This creator has no public collections")
    } else {
        items += OptionsDialogItem.Section("Collection with this novel's chapters")
        collections.forEach { collection ->
            val label = PatreonEarlyAccessPlanning.optionLabel(collection) + if (collection.id == current?.collectionId) " ✓" else ""
            items +=
                OptionsDialogItem.Option(label) {
                    val link =
                        PatreonEarlyAccessLink(campaignId = campaignId, collectionId = collection.id, collectionTitle = collection.title)
                    if (current != null && current.collectionId != collection.id && story.chapters.any { it.isPatreonCopy }) {
                        confirm("Switch collections? Patreon chapters stored from the current one are removed.", confirmLabel = "Switch") {
                            applyLink(story, link)
                        }
                    } else {
                        applyLink(story, link)
                    }
                }
        }
    }
    if (current != null) {
        items += OptionsDialogItem.Divider
        items +=
            OptionsDialogItem.Option("Unlink", destructive = true) {
                confirm("Unlink this collection? Stored Patreon chapters are removed.", confirmLabel = "Unlink") {
                    scope.launch {
                        repository.setPatreonEarlyAccess(story.id, null)
                        showDetails(story.id)
                    }
                }
            }
    }
    showStyledOptionsDialog("Early-access chapters", items)
}

private fun ScreenHost.applyLink(
    story: Story,
    link: PatreonEarlyAccessLink,
) {
    scope.launch {
        val updated = repository.setPatreonEarlyAccess(story.id, link) ?: return@launch
        syncStory(updated)
        if (!PatreonSession.isPresent()) offerPatreonSignIn(story.id)
    }
}

/** Linking works signed out, but locked posts need the user's own Patreon session. */
private fun ScreenHost.offerPatreonSignIn(storyId: String) {
    AlertDialog
        .Builder(app)
        .setTitle("Sign in to Patreon")
        .setMessage(
            "Early-access chapters are only readable by the creator's patrons. " +
                "Sign in with the Patreon account that supports this creator to fetch them.",
        ).setPositiveButton("Set up sign-in") { _, _ -> showPatreonAccount(storyId) }
        .setNegativeButton("Later", null)
        .show()
        .applyAppTheme()
}

/** Runs the Patreon card's next-step button for this story. */
internal fun ScreenHost.handleEarlyAccessPrompt(
    story: Story,
    action: PatreonEarlyAccessPlanning.Action,
) {
    when (action) {
        PatreonEarlyAccessPlanning.Action.SET_UP_SIGN_IN,
        PatreonEarlyAccessPlanning.Action.SIGN_IN_AGAIN,
        -> showPatreonAccount(story.id)
        PatreonEarlyAccessPlanning.Action.CHECK_NOW -> syncStory(story)
        PatreonEarlyAccessPlanning.Action.OPEN_PATREON ->
            story.patreonUrl?.let { url -> app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
