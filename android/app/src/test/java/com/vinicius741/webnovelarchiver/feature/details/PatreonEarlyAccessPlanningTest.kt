package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.PatreonEarlyAccessLink
import com.vinicius741.webnovelarchiver.domain.model.PatreonSignInIssue
import com.vinicius741.webnovelarchiver.source.PatreonCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatreonEarlyAccessPlanningTest {
    private val link = PatreonEarlyAccessLink(campaignId = "c", collectionId = "1", collectionTitle = "The Lich King Reincarnates")

    @Test
    fun `summary describes link state`() {
        assertEquals("Not linked · tap to choose a Patreon collection", PatreonEarlyAccessPlanning.summary(null, 0))
        assertEquals("The Lich King Reincarnates · checks on next sync", PatreonEarlyAccessPlanning.summary(link, 0))
        assertEquals("The Lich King Reincarnates · 29 chapters ahead", PatreonEarlyAccessPlanning.summary(link.copy(lastCheckedAt = 1), 29))
        assertEquals("The Lich King Reincarnates · 1 chapter ahead", PatreonEarlyAccessPlanning.summary(link.copy(lastCheckedAt = 1), 1))
        assertEquals(
            "The Lich King Reincarnates · no chapters ahead of the public release",
            PatreonEarlyAccessPlanning.summary(link.copy(lastCheckedAt = 1), 0),
        )
        assertEquals(
            "The Lich King Reincarnates · Expired",
            PatreonEarlyAccessPlanning.summary(link.copy(lastCheckedAt = 1, lastError = "Expired"), 3),
        )
    }

    @Test
    fun `an unlinked novel shows no sign-in prompt`() {
        val display = PatreonEarlyAccessPlanning.display(null, 0, sessionPresent = false, sessionStoredAt = 0)
        assertNull(display.prompt)
        assertEquals("Not linked · tap to choose a Patreon collection", display.summary)
    }

    @Test
    fun `a linked novel without a session asks to set up sign-in`() {
        val checked = link.copy(lastCheckedAt = 10, lockedCount = 3, signInIssue = PatreonSignInIssue.SIGNED_OUT)

        val display = PatreonEarlyAccessPlanning.display(checked, 0, sessionPresent = false, sessionStoredAt = 0)

        assertEquals(PatreonEarlyAccessPlanning.Action.SET_UP_SIGN_IN, display.prompt?.action)
        assertEquals("The Lich King Reincarnates · 3 early-access chapters need a Patreon sign-in", display.summary)
        assertEquals(
            PatreonEarlyAccessPlanning.Action.SET_UP_SIGN_IN,
            PatreonEarlyAccessPlanning.display(link, 0, sessionPresent = false, sessionStoredAt = 0).prompt?.action,
        )
    }

    @Test
    fun `signing in after a failed check offers a fresh check`() {
        val signedOut = link.copy(lastCheckedAt = 10, signInIssue = PatreonSignInIssue.SIGNED_OUT)
        assertEquals(
            PatreonEarlyAccessPlanning.Action.CHECK_NOW,
            PatreonEarlyAccessPlanning.display(signedOut, 0, sessionPresent = true, sessionStoredAt = 0).prompt?.action,
        )

        val rejected = link.copy(lastCheckedAt = 10, signInIssue = PatreonSignInIssue.REJECTED)
        assertEquals(
            PatreonEarlyAccessPlanning.Action.SIGN_IN_AGAIN,
            PatreonEarlyAccessPlanning.display(rejected, 0, sessionPresent = true, sessionStoredAt = 5).prompt?.action,
        )
        assertEquals(
            PatreonEarlyAccessPlanning.Action.CHECK_NOW,
            PatreonEarlyAccessPlanning.display(rejected, 0, sessionPresent = true, sessionStoredAt = 20).prompt?.action,
        )
    }

    @Test
    fun `locked chapters for a signed-in account point to the creator's tiers`() {
        val locked = link.copy(lastCheckedAt = 10, lockedCount = 2, lastError = "2 early-access chapters need a tier")

        val display = PatreonEarlyAccessPlanning.display(locked, 1, sessionPresent = true, sessionStoredAt = 0)

        assertEquals(PatreonEarlyAccessPlanning.Action.OPEN_PATREON, display.prompt?.action)
        assertEquals("The Lich King Reincarnates · 2 early-access chapters need a tier", display.summary)
        assertNull(PatreonEarlyAccessPlanning.display(link.copy(lastCheckedAt = 10), 4, true, 0).prompt)
    }

    @Test
    fun `ranks the collection named after the novel first`() {
        val collections =
            listOf(
                PatreonCollection("a", "Announcements", 17),
                PatreonCollection("b", "Eat Them All", 141),
                PatreonCollection("c", "The Lich King Reincarnates", 185),
                PatreonCollection("d", "The Last Mage King", 54),
            )

        val ranked = PatreonEarlyAccessPlanning.rankCollections("The Lich King Reincarnates as a Baby [B1 STUBBING OCT 3RD]", collections)

        assertEquals(listOf("c", "d", "a", "b"), ranked.map { it.id })
        assertEquals("The Lich King Reincarnates (185 posts)", PatreonEarlyAccessPlanning.optionLabel(collections[2]))
        assertEquals("X", PatreonEarlyAccessPlanning.optionLabel(PatreonCollection("x", "X", null)))
    }
}
