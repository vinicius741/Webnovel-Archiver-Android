package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.PatreonEarlyAccessLink
import com.vinicius741.webnovelarchiver.domain.model.PatreonSignInIssue
import com.vinicius741.webnovelarchiver.source.PatreonCollection

/** Display rules for the Details screen's Patreon early-access row and collection picker. */
object PatreonEarlyAccessPlanning {
    private val words = Regex("""[^\p{L}\p{N}]+""")
    private val ignoredWords = setOf("the", "a", "an", "of", "and", "as", "to", "in", "on", "book", "books")

    fun summary(
        link: PatreonEarlyAccessLink?,
        copyCount: Int,
    ): String =
        when {
            link == null -> "Not linked · tap to choose a Patreon collection"
            link.lastError != null -> "${link.collectionTitle} · ${link.lastError}"
            link.lastCheckedAt == null -> "${link.collectionTitle} · checks on next sync"
            copyCount == 0 -> "${link.collectionTitle} · no chapters ahead of the public release"
            else -> "${link.collectionTitle} · $copyCount ${if (copyCount == 1) "chapter" else "chapters"} ahead"
        }

    enum class Action {
        /** Open the Patreon sign-in guide; nothing is signed in yet. */
        SET_UP_SIGN_IN,

        /** Open the guide to replace a session Patreon refused. */
        SIGN_IN_AGAIN,

        /** A session was saved after the failed check: sync to read the posts now. */
        CHECK_NOW,

        /** Signed in, but the account's tier cannot read some posts. */
        OPEN_PATREON,
    }

    data class Prompt(
        val action: Action,
        val buttonLabel: String,
        /** One line under the summary when the button alone does not explain the next step. */
        val hint: String? = null,
    )

    data class Display(
        val summary: String,
        val prompt: Prompt?,
    )

    /**
     * The row's text and its one next step. [sessionStoredAt] is when this process last saved a
     * session, so a check that failed before the user signed in offers a fresh check instead of
     * another sign-in.
     */
    fun display(
        link: PatreonEarlyAccessLink?,
        copyCount: Int,
        sessionPresent: Boolean,
        sessionStoredAt: Long,
    ): Display {
        if (link == null) return Display(summary(null, copyCount), null)
        val title = link.collectionTitle
        val signedInSinceCheck = sessionStoredAt > (link.lastCheckedAt ?: 0L)
        return when {
            !sessionPresent ->
                Display(
                    if (link.lockedCount > 0) {
                        "$title · ${link.lockedCount} early-access ${chapters(link.lockedCount)} need a Patreon sign-in"
                    } else {
                        "$title · sign in to read early-access chapters"
                    },
                    Prompt(
                        Action.SET_UP_SIGN_IN,
                        "Set up Patreon sign-in",
                        "Use the Patreon account that supports this creator. The guide walks you through it.",
                    ),
                )
            link.signInIssue == PatreonSignInIssue.SIGNED_OUT ||
                (link.signInIssue == PatreonSignInIssue.REJECTED && signedInSinceCheck) ->
                Display("$title · signed in, not checked yet", Prompt(Action.CHECK_NOW, "Check for early-access chapters"))
            link.signInIssue == PatreonSignInIssue.REJECTED ->
                Display(
                    "$title · Patreon didn't accept your saved sign-in",
                    Prompt(Action.SIGN_IN_AGAIN, "Sign in again", "Saved sign-ins expire after you sign out of Patreon in the browser."),
                )
            link.lockedCount > 0 && link.lastError != null ->
                Display(summary(link, copyCount), Prompt(Action.OPEN_PATREON, "View tiers on Patreon"))
            else -> Display(summary(link, copyCount), null)
        }
    }

    private fun chapters(count: Int) = if (count == 1) "chapter" else "chapters"

    /** Collections sharing the most title words with the novel come first; ties keep Patreon's order. */
    fun rankCollections(
        storyTitle: String,
        collections: List<PatreonCollection>,
    ): List<PatreonCollection> {
        val storyWords = significantWords(storyTitle)
        return collections
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<PatreonCollection>> { (_, collection) ->
                    overlap(storyWords, collection.title)
                }.thenBy { it.index },
            ).map { it.value }
    }

    fun optionLabel(collection: PatreonCollection): String =
        collection.postCount?.let { "${collection.title} ($it ${if (it == 1) "post" else "posts"})" } ?: collection.title

    private fun overlap(
        storyWords: Set<String>,
        title: String,
    ): Int = significantWords(title).count { it in storyWords }

    private fun significantWords(text: String): Set<String> =
        text
            .lowercase()
            .split(words)
            .filter { it.isNotBlank() && it !in ignoredWords }
            .toSet()
}
