package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceRemovalPlanningTest {
    @Test
    fun saveAddsTrimmedNewSentenceAtBeginning() {
        val result = SentenceRemovalPlanning.save(listOf("sentence one", "sentence two"), "  trimmed sentence  ")

        assertTrue(result.valid)
        assertEquals(listOf("trimmed sentence", "sentence one", "sentence two"), result.sentences)
    }

    @Test
    fun saveRejectsBlankAndDuplicateSentences() {
        val sentences = listOf("sentence one", "sentence two")

        val blank = SentenceRemovalPlanning.save(sentences, "   ")
        val duplicate = SentenceRemovalPlanning.save(sentences, "sentence one")

        assertFalse(blank.valid)
        assertEquals("Sentence cannot be empty", blank.error)
        assertFalse(duplicate.valid)
        assertEquals("This sentence is already in the removal list", duplicate.error)
    }

    @Test
    fun saveAllowsEditingSameIndexAndRejectsDuplicateOtherIndex() {
        val sentences = listOf("sentence one", "sentence two")

        val same = SentenceRemovalPlanning.save(sentences, "sentence one", editingIndex = 0)
        val duplicateOther = SentenceRemovalPlanning.save(sentences, "sentence two", editingIndex = 0)

        assertTrue(same.valid)
        assertEquals(sentences, same.sentences)
        assertFalse(duplicateOther.valid)
    }

    @Test
    fun saveUpdatesExistingSentenceAtIndex() {
        val result = SentenceRemovalPlanning.save(listOf("sentence one", "sentence two"), "updated sentence", editingIndex = 0)

        assertTrue(result.valid)
        assertEquals(listOf("updated sentence", "sentence two"), result.sentences)
    }

    @Test
    fun deleteRemovesSentenceByIndex() {
        assertEquals(
            listOf("sentence two"),
            SentenceRemovalPlanning.delete(listOf("sentence one", "sentence two"), 0),
        )
    }
}
