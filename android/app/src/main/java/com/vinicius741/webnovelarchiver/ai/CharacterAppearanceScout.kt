package com.vinicius741.webnovelarchiver.ai

/**
 * Free, on-device search for where one character is described: every mention of their names is
 * scored by the appearance words around it (hair, eyes, tall, freckles, wore...), so the paid
 * decision scan reads the chapters that describe the character instead of the ones that merely name
 * them most. A character who enters late or is described once ("those golden blonde strands")
 * is still found, wherever in the book that is.
 */
internal class CharacterAppearanceScout(
    names: List<String>,
) {
    /** One chapter worth judging: its best scored mention and a line of text to start the request at. */
    data class Spot(
        val chapter: Int,
        val score: Int,
        val mentions: Int,
        val anchor: String,
    )

    private val pattern = CharacterMining.namePattern(names)
    private val spots = mutableListOf<Spot>()

    fun add(chapter: CharacterMining.ChapterText) {
        val regex = pattern ?: return
        val text = chapter.text
        val mentions = regex.findAll(text).toList()
        // Descriptions usually follow the name ("Victoria squealed... blonde, wriggly"), so the
        // window reaches further forward than back.
        val best =
            mentions.maxByOrNull { match ->
                val window = text.substring(maxOf(0, match.range.first - BEFORE), minOf(text.length, match.range.last + AFTER))
                STRONG.findAll(window).count() * 2 + WEAK.findAll(window).count()
            } ?: return
        val window = text.substring(maxOf(0, best.range.first - BEFORE), minOf(text.length, best.range.last + AFTER))
        val lineEnd = text.indexOf('\n', best.range.first).let { if (it < 0) text.length else it }
        spots +=
            Spot(
                chapter = chapter.chapter,
                score = STRONG.findAll(window).count() * 2 + WEAK.findAll(window).count(),
                mentions = mentions.size,
                anchor = text.substring(best.range.first, minOf(lineEnd, best.range.first + ANCHOR_CHARS)),
            )
    }

    /**
     * Up to [limit] chapters: those with appearance words near a mention, best first, then the
     * chapters that name the character most, so a character never described still gets a scan.
     */
    fun best(limit: Int): List<Spot> {
        val described = spots.filter { it.score >= MIN_SCORE }.sortedWith(compareByDescending<Spot> { it.score }.thenBy { it.chapter })
        val rest = (spots - described.toSet()).sortedWith(compareByDescending<Spot> { it.mentions }.thenBy { it.chapter })
        return (described + rest).take(limit)
    }

    private companion object {
        const val BEFORE = 300
        const val AFTER = 900
        const val ANCHOR_CHARS = 60
        const val MIN_SCORE = 2

        /** Words that almost always describe looks. */
        val STRONG =
            Regex(
                "\\b(?:hair|haired|blond|blonde|brunette|redhead|freckles?|freckled|complexion|cheekbones?|jaw|beard|" +
                    "stubble|moustache|mustache|braids?|braided|ponytail|curls|curly|tall|petite|slim|slender|lanky|" +
                    "stocky|muscular|plump|scars?|scarred|tattoos?|horns?|fangs|scales|tail|wings|glasses|spectacles|" +
                    "pale|tanned|bronze|dress|gown|robes?|uniform|cloak|armou?r)\\b",
                RegexOption.IGNORE_CASE,
            )

        /** Words that often, but not always, describe looks ("eyes on the ground"). */
        val WEAK = Regex("\\b(?:eyes|eyed|face|features|skin|wore|wearing|dressed|look|resembled|height)\\b", RegexOption.IGNORE_CASE)
    }
}
