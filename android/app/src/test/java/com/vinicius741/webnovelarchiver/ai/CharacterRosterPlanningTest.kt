package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.CharacterRole
import com.vinicius741.webnovelarchiver.domain.model.Story
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
        assertEquals("zoë-o-neil", CharacterRosterPlanning.characterId("Zoë O'Neil"))
        assertEquals("character", CharacterRosterPlanning.characterId("!!!"))
    }

    @Test fun `the request sends candidates as data inside source markers`() {
        val candidate =
            CharacterMining.Candidate("Mara", 12, 3, 4, 1, 9, listOf(CharacterMining.Snippet(1, "Mara lifted her clock.")))
        val messages = CharacterRosterPlanning.buildMessages(Story(title = "The Clockwork Library"), listOf(candidate))
        assertTrue(messages[0].content.startsWith(CharacterRosterPlanning.SYSTEM_OPENING))
        assertTrue(messages[1].content.startsWith("SOURCE_DATA_START"))
        assertTrue(messages[1].content.contains("Ch 1: Mara lifted her clock."))
    }
}
