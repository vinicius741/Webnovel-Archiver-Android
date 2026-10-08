package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.PatreonEarlyAccessLink
import com.vinicius741.webnovelarchiver.source.PatreonCollection
import org.junit.Assert.assertEquals
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
