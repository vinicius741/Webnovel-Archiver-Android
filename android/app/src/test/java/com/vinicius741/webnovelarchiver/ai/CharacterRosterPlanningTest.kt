package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.CharacterRole
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryCharacter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CharacterRosterPlanningTest {
    @Test fun `the reply is parsed from fenced JSON with merged aliases, roles and stable ids`() {
        val reply =
            """
            ```json
            {"characters":[
              {"name":"Ivo","aliases":[],"role":"major","bio":"The apprentice."},
              {"name":"Mara Venn","aliases":["Mara","mara venn","Mara",""],"role":"Protagonist","bio":"A clockmaker."},
              {"name":"Rhys","role":"wizard"},
              {"name":"","role":"major"},
              {"name":"Mara Venn","role":"major"}
            ]}
            ```
            """.trimIndent()
        val characters = CharacterRosterPlanning.parse(reply)
        assertEquals(listOf("Ivo", "Mara Venn", "Rhys"), characters.map { it.name })
        val mara = characters[1]
        assertEquals("mara-venn", mara.id)
        assertEquals(listOf("Mara"), mara.aliases)
        assertEquals(CharacterRole.PROTAGONIST, mara.role)
        assertEquals(CharacterRole.SUPPORTING, characters[2].role)
        assertEquals(listOf("Mara Venn", "Ivo", "Rhys"), CharacterRosterPlanning.ordered(characters).map { it.name })
        assertEquals(listOf("Mara Venn", "Mara"), mara.names)
    }

    @Test(expected = IOException::class)
    fun `a reply without a character list fails`() {
        CharacterRosterPlanning.parse("I could not find anyone.")
    }

    @Test fun `ids are stable slugs, so a new list keeps portraits`() {
        assertEquals("lin-feng", CharacterRosterPlanning.characterId("Lin  Feng!"))
        assertEquals("lin-feng", CharacterRosterPlanning.characterId("lin feng"))
        // Letters lost to the ASCII folder name add a hash, so "Zoë" and "Zoé" never share portraits.
        val zoe1 = CharacterRosterPlanning.characterId("Zoë")
        val zoe2 = CharacterRosterPlanning.characterId("Zoé")
        assertTrue(zoe1.startsWith("zoe-") && zoe2.startsWith("zoe-") && zoe1 != zoe2)
        assertTrue(CharacterRosterPlanning.characterId("林凡").matches(Regex("character-[0-9a-f]{6}")))
        assertTrue(CharacterRosterPlanning.characterId("!!!").startsWith("character-"))
    }

    @Test fun `the request sends candidates as data inside source markers`() {
        val candidate =
            CharacterMining.Candidate("Mara", 12, 3, 4, 1, 9, emptyList(), listOf(CharacterMining.Snippet(1, "Mara lifted her clock.")))
        val messages = CharacterRosterPlanning.buildMessages(Story(title = "The Clockwork Library"), listOf(candidate))
        assertTrue(messages[0].content.startsWith(CharacterRosterPlanning.SYSTEM_OPENING))
        assertTrue(messages[1].content.startsWith("SOURCE_DATA_START"))
        assertTrue(messages[1].content.contains("Ch 1: Mara lifted her clock."))
    }

    @Test fun `only the first protagonist stays the lead`() {
        val reply = """{"characters":[{"name":"A","role":"protagonist"},{"name":"B","role":"protagonist"}]}"""
        assertEquals(listOf(CharacterRole.PROTAGONIST, CharacterRole.MAJOR), CharacterRosterPlanning.parse(reply).map { it.role })
    }

    @Test fun `a new list keeps old ids by shared names and drops shared or title aliases`() {
        fun character(
            name: String,
            vararg aliases: String,
        ) = StoryCharacter(CharacterRosterPlanning.characterId(name), name, aliases.toList(), CharacterRole.MAJOR, "", 0, null, null)
        val previous = listOf(character("Jason"), character("Farrah"))
        val fresh =
            listOf(
                character("Jason Asano", "Jason", "Asano", "Captain"),
                character("Li Qing", "Li"),
                character("Li Mei", "Li"),
            )
        val reconciled = CharacterRosterPlanning.reconcile(fresh, previous)
        assertEquals(listOf("jason", "li-qing", "li-mei"), reconciled.map { it.id })
        assertEquals(listOf("Jason", "Asano"), reconciled[0].aliases)
        assertEquals(emptyList<String>(), reconciled[1].aliases)
        assertEquals(emptyList<String>(), reconciled[2].aliases)
    }

    @Test fun `a surname shared with another character's name is not an alias`() {
        fun character(
            name: String,
            vararg aliases: String,
        ) = StoryCharacter(CharacterRosterPlanning.characterId(name), name, aliases.toList(), CharacterRole.MAJOR, "", 0, null, null)
        val reconciled =
            CharacterRosterPlanning.reconcile(
                listOf(character("Isabel Vornholt", "Isabel", "Vornholt", "Lord Dread", "Doctor"), character("Agrian Vornholt", "Agrian")),
                emptyList(),
            )
        assertEquals(listOf("Isabel", "Lord Dread"), reconciled[0].aliases)
    }
}
