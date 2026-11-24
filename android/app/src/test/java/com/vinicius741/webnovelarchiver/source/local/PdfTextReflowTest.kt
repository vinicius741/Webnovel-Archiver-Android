package com.vinicius741.webnovelarchiver.source.local

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTextReflowTest {
    @Test
    fun rebuildsParagraphsAndDropsPageFurniture() {
        val towns = listOf("Ashford", "Brill", "Calder", "Dunmore")
        val pages =
            towns.mapIndexed { index, town ->
                listOf(
                    "The Long Road",
                    if (index == 0) "Chapter 1" else "",
                    "It was a long road that wound through the hills of $town and the trees and",
                    "the rivers, and every travel-",
                    "er from $town knew it well.",
                    "${index + 1}",
                ).joinToString("\n")
            }

        val paragraphs = PdfTextReflow.reflow(pages).split("\n\n")

        assertEquals("Chapter 1", paragraphs.first())
        assertEquals(
            "It was a long road that wound through the hills of Ashford and the trees and the rivers, and every traveler from Ashford knew it well.",
            paragraphs[1],
        )
        assertEquals(5, paragraphs.size)
        assertEquals(false, paragraphs.any { it.contains("The Long Road") || it == "2" })
    }

    @Test
    fun textRepeatedOnEveryPageStaysWhenItIsNotAtThePageEdge() {
        val body = "The clerks moved between the shelves with lamps held low so the old paper would not curl."
        val pages = List(5) { listOf("The Archive", body, "Short line one.", body, "${it + 1}").joinToString("\n") }

        val text = PdfTextReflow.reflow(pages)

        assertEquals(10, Regex("The clerks moved").findAll(text).count())
        assertEquals(false, text.contains("The Archive"))
    }

    @Test
    fun aNumberInsideThePageIsText() {
        val pages = List(4) { listOf("Title", "The war ended in", "1945", "and nobody forgot it.", "${it + 1}").joinToString("\n") }

        assertEquals(4, Regex("\\b1945\\b").findAll(PdfTextReflow.reflow(pages)).count())
    }
}
