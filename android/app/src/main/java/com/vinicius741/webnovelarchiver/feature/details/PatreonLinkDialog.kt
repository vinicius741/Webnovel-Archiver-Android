package com.vinicius741.webnovelarchiver.feature.details

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.data.repository.setManualPatreonUrl
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonCreatorPlanning
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyFormStyle
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.styledDialogField
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/**
 * Lets the user enter the creator's Patreon link by hand, for novels whose source page doesn't name
 * it or names the wrong one. Syncs keep the entered link; the source's stays stored underneath, so
 * removing the entered link falls back to it.
 */
internal fun ScreenHost.showPatreonLinkDialog(story: Story) {
    if (!StoryActionGuards.canModifyStory(story)) return toast(StoryActionGuards.archivedActionMessage("Editing the Patreon link"))
    val current = story.effectivePatreonUrl.orEmpty()
    val input =
        styledDialogField(current, "https://www.patreon.com/creator", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    // An inline line rather than EditText.setError, whose popup covers the dialog's buttons.
    val errorLine =
        makeText(app, "Enter the creator's page link, like patreon.com/creator", Type.BODY_SMALL, ThemeManager.colors.error).apply {
            visibility = View.GONE
        }
    val container =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(Space.SM), dp(24), 0)
            addView(input)
            addView(errorLine)
        }
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("Patreon link")
            .setMessage(patreonLinkMessage(story))
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply {
                if (story.manualPatreonUrl != null) {
                    val removeLabel = if (story.patreonUrl != null) "Use source link" else "Remove"
                    setNeutralButton(removeLabel) { _, _ -> savePatreonLink(story, null) }
                }
            }.create()
    dialog.show()
    dialog.applyFormStyle()
    // Set after show so an invalid link keeps the dialog open with its error.
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val text = input.text.toString().trim()
        val normalized = PatreonCreatorPlanning.normalizeCreatorLink(text)
        when {
            text == current -> dialog.dismiss()
            text.isEmpty() -> {
                dialog.dismiss()
                savePatreonLink(story, null)
            }
            normalized == null -> errorLine.visibility = View.VISIBLE
            else -> {
                dialog.dismiss()
                savePatreonLink(story, normalized)
            }
        }
    }
}

private fun patreonLinkMessage(story: Story): String =
    when {
        story.manualPatreonUrl != null -> "You added this link. Syncing this novel won't change it."
        story.patreonUrl != null -> "This link came from the source page. Enter another one to replace it; syncing won't change yours."
        else -> "Enter the link to the creator's Patreon page. Syncing this novel won't change it."
    }

private fun ScreenHost.savePatreonLink(
    story: Story,
    url: String?,
) {
    val next = url ?: story.patreonUrl
    val changesCreator = !PatreonCreatorPlanning.sameCreator(story.effectivePatreonUrl, next)
    val save = {
        scope.launch {
            val updated = repository.setManualPatreonUrl(story.id, url) ?: return@launch
            // A new creator has no stats yet, and a sync is what fetches them.
            if (changesCreator && updated.effectivePatreonUrl != null) syncStory(updated) else showDetails(story.id)
        }
        Unit
    }
    if (changesCreator && story.patreonEarlyAccess != null) {
        confirm("Change to this link? It's a different creator, so early-access chapters are unlinked and stored ones removed.", "Change") {
            save()
        }
    } else {
        save()
    }
}
