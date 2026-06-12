package com.vinicius741.webnovelarchiver.core

data class SentenceSaveResult(
    val valid: Boolean,
    val sentences: MutableList<String>,
    val error: String? = null,
)

object SentenceRemovalPlanning {
    fun save(sentences: List<String>, rawSentence: String, editingIndex: Int? = null): SentenceSaveResult {
        val trimmed = rawSentence.trim()
        if (trimmed.isBlank()) {
            return SentenceSaveResult(false, sentences.toMutableList(), "Sentence cannot be empty")
        }

        val existingIndex = sentences.indexOf(trimmed)
        if (existingIndex != -1 && (editingIndex == null || existingIndex != editingIndex)) {
            return SentenceSaveResult(false, sentences.toMutableList(), "This sentence is already in the removal list")
        }

        val next = sentences.toMutableList()
        if (editingIndex != null && editingIndex in next.indices) {
            next[editingIndex] = trimmed
        } else {
            next.add(0, trimmed)
        }
        return SentenceSaveResult(true, next)
    }

    fun delete(sentences: List<String>, index: Int): MutableList<String> =
        sentences.filterIndexed { currentIndex, _ -> currentIndex != index }.toMutableList()
}
