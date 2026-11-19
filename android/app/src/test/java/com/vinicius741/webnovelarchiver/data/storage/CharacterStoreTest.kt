package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.model.CharacterRole
import com.vinicius741.webnovelarchiver.domain.model.StoryCharacter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CharacterStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store(root: File) = CharacterStore(root) { it.replace(Regex("[^A-Za-z0-9._-]"), "_") }

    private val mara = StoryCharacter("mara", "Mara", listOf("Clockmaker"), CharacterRole.PROTAGONIST, "Lead.", 40, 1, 30, listOf(1, 9))

    @Test fun `the roster survives recreation and a corrupt file reads as no roster`() {
        val root = temporary.newFolder()
        store(root).saveRoster("rr_1", listOf(mara))
        assertEquals(listOf(mara), store(root).roster("rr_1"))
        assertNull(store(root).roster("rr_2"))
        File(root, "ai_characters/rr_1/roster.json").writeText("{broken")
        assertNull(store(root).roster("rr_1"))
    }

    @Test fun `portraits list newest first, identical bytes are kept once, and delete removes one`() {
        val root = temporary.newFolder()
        val characters = store(root)
        val first = characters.savePortrait("rr_1", "mara", "first prompt", byteArrayOf(1, 2), "image/png")
        File(root, "ai_characters/rr_1/portraits/mara/$first.json").setLastModified(1_000)
        val second = characters.savePortrait("rr_1", "mara", "second prompt", byteArrayOf(3), "image/jpeg")
        assertEquals(first, characters.savePortrait("rr_1", "mara", "duplicate", byteArrayOf(1, 2), "image/png"))
        val portraits = characters.portraits("rr_1", "mara")
        assertEquals(listOf(second, first), portraits.map { it.id })
        assertEquals("first prompt", portraits[1].prompt)
        assertTrue(portraits[0].image.name.endsWith(".jpg"))
        assertEquals(mapOf("mara" to portraits[0].image), characters.latestPortraits("rr_1"))
        characters.deletePortrait("rr_1", "mara", second)
        assertEquals(listOf(first), characters.portraits("rr_1", "mara").map { it.id })
        characters.deleteAll("rr_1")
        assertTrue(characters.portraits("rr_1", "mara").isEmpty())
        assertNull(characters.roster("rr_1"))
    }
}
