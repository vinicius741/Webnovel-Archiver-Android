package com.vinicius741.webnovelarchiver.ai

import kotlin.math.sqrt

/**
 * Deterministic cadence metrics over parsed chapter blocks. Style change gets a countable report so
 * it can run through the pipeline, the comparison screen, and the release gates without any model
 * calls. Operates on *addressable prose blocks* — protected System panels and spacers are excluded
 * because their shape is authorial interface content, not prose rhythm.
 *
 * Reference numbers (Phase-1 spike): the problem chapter parses to ~186 prose paragraphs, ~94
 * fragment paragraphs, and ~10 clusters; `ChapterCadenceReportTest` pins that regression.
 */
data class CadenceReport(
    val paragraphCount: Int,
    val wordCount: Int,
    val sentenceCount: Int,
    val fragmentParagraphs: Int,
    val fragmentShare: Double,
    val clusterCount: Int,
    val clusterParagraphs: Int,
    val tripletCount: Int,
    val tripletRatePer100Paragraphs: Double,
    val sentenceLengthMean: Double,
    val sentenceLengthStdev: Double,
    val sentenceLengthCv: Double,
    val emDashDensityPer1000Words: Double,
    val lengthBucketShares: Map<String, Double>,
    val dominantBucket: String,
    val dominantBucketShare: Double,
)

/** Before/after comparison plus the template-swap detector. */
data class CadenceComparison(
    val before: CadenceReport,
    val after: CadenceReport,
    val fragmentShareDelta: Double,
    val clusterDelta: Int,
    val tripletDelta: Int,
    val cvDelta: Double,
    val emDashDelta: Double,
    val templateSwapWarning: Boolean,
    val templateSwapDetail: String,
)

object ChapterCadenceReport {
    const val FRAGMENT_MAX_WORDS = 5
    const val CLUSTER_MIN_RUN = 3
    const val TRIPLET_MAX_WORDS = 5
    const val DOMINANT_SHARE_WARN = 0.55

    private val BUCKETS = listOf("1-5" to (1..5), "6-12" to (6..12), "13-20" to (13..20), "21-35" to (21..35), "36+" to (36..Int.MAX_VALUE))
    private val ABBREV = Regex("""\b(?:Mr|Mrs|Ms|Dr|St|Sr|Jr|vs|etc|Inc|Ltd|Prof)\.$""")
    private val SENTENCE_SPLIT = Regex("""(?<=[.!?…])["'”’)\]]*\s+""")
    private val WHITESPACE = Regex("""\s+""")

    /** Sentence segmentation good enough for length statistics (not for display). */
    fun splitSentences(text: String): List<String> {
        val normalized = WHITESPACE.replace(text, " ").trim()
        if (normalized.isEmpty()) return emptyList()
        val parts = SENTENCE_SPLIT.split(normalized)
        val out = mutableListOf<String>()
        for (part in parts) {
            if (part.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && ABBREV.containsMatchIn(last)) {
                out[out.size - 1] = "$last $part"
            } else {
                out.add(part)
            }
        }
        return out
    }

    fun words(text: String): List<String> = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    fun cadenceOf(blocks: List<ChapterBlock>): CadenceReport {
        val prose = blocks.filter { !it.protected }
        val texts = prose.map { ChapterBlockParsing.textOf(it.html) }
        val paragraphWordCounts = texts.map { words(it).size }
        val allText = texts.joinToString(" ")

        val sentenceLengths = mutableListOf<Int>()
        var tripletCount = 0
        for (text in texts) {
            val lengths = splitSentences(text).map { words(it).size }
            sentenceLengths.addAll(lengths)
            for (i in 0..lengths.size - 3) {
                if ((i..i + 2).all { lengths[it] <= TRIPLET_MAX_WORDS }) tripletCount++
            }
        }

        val fragmentFlags = paragraphWordCounts.map { it in 1..FRAGMENT_MAX_WORDS }
        var clusterCount = 0
        var clusterParagraphs = 0
        var run = 0
        for (flag in fragmentFlags + listOf(false)) {
            if (flag) {
                run++
            } else {
                if (run >= CLUSTER_MIN_RUN) {
                    clusterCount++
                    clusterParagraphs += run
                }
                run = 0
            }
        }

        val totalWords = paragraphWordCounts.sum()
        val sentenceMean = if (sentenceLengths.isNotEmpty()) sentenceLengths.average() else 0.0
        val sentenceStdev = sampleStdev(sentenceLengths)

        val bucketCounts = BUCKETS.associate { (name, _) -> name to 0 }.toMutableMap()
        for (length in sentenceLengths) {
            for ((name, range) in BUCKETS) {
                if (length in range) {
                    bucketCounts[name] = bucketCounts.getValue(name) + 1
                    break
                }
            }
        }
        val sentenceTotal = sentenceLengths.size
        val bucketShares =
            bucketCounts.mapValues { (_, count) ->
                if (sentenceTotal > 0) count.toDouble() / sentenceTotal else 0.0
            }
        val dominant = bucketShares.maxByOrNull { it.value }?.key ?: ""

        return CadenceReport(
            paragraphCount = prose.size,
            wordCount = totalWords,
            sentenceCount = sentenceTotal,
            fragmentParagraphs = fragmentFlags.count { it },
            fragmentShare = if (fragmentFlags.isNotEmpty()) fragmentFlags.count { it }.toDouble() / fragmentFlags.size else 0.0,
            clusterCount = clusterCount,
            clusterParagraphs = clusterParagraphs,
            tripletCount = tripletCount,
            tripletRatePer100Paragraphs = if (texts.isNotEmpty()) 100.0 * tripletCount / texts.size else 0.0,
            sentenceLengthMean = round(sentenceMean, 2),
            sentenceLengthStdev = round(sentenceStdev, 2),
            sentenceLengthCv = if (sentenceMean > 0) round(sentenceStdev / sentenceMean, 3) else 0.0,
            emDashDensityPer1000Words = if (totalWords > 0) round(1000.0 * occurrences(allText, "—") / totalWords, 2) else 0.0,
            lengthBucketShares = bucketShares.mapValues { (_, share) -> round(share, 4) },
            dominantBucket = dominant,
            dominantBucketShare = if (dominant.isNotEmpty()) round(bucketShares[dominant] ?: 0.0, 4) else 0.0,
        )
    }

    fun compare(
        before: CadenceReport,
        after: CadenceReport,
    ): CadenceComparison {
        val fragDelta = round(after.fragmentShare - before.fragmentShare, 4)
        val clusterDelta = after.clusterCount - before.clusterCount
        val tripletDelta = after.tripletCount - before.tripletCount
        val cvDelta = round(after.sentenceLengthCv - before.sentenceLengthCv, 3)
        val emDashDelta = round(after.emDashDensityPer1000Words - before.emDashDensityPer1000Words, 2)

        val clustersReduced = clusterDelta <= -maxOf(1, Math.round(before.clusterCount * 0.3).toInt())
        val fragmentsReduced = fragDelta <= -0.10
        val dominantGrew = after.dominantBucketShare >= maxOf(DOMINANT_SHARE_WARN, before.dominantBucketShare + 0.05)
        val templateSwap = (clustersReduced || fragmentsReduced) && dominantGrew
        val detail =
            if (templateSwap) {
                "fragment clusters ${before.clusterCount}->${after.clusterCount} but a single " +
                    "sentence-length bucket (${after.dominantBucket}) now holds " +
                    "${percent(after.dominantBucketShare)} of sentences (was " +
                    "${percent(before.dominantBucketShare)} in ${before.dominantBucket}): the rewrite " +
                    "likely swapped one template rhythm for another instead of varying rhythm."
            } else {
                ""
            }
        return CadenceComparison(
            before = before,
            after = after,
            fragmentShareDelta = fragDelta,
            clusterDelta = clusterDelta,
            tripletDelta = tripletDelta,
            cvDelta = cvDelta,
            emDashDelta = emDashDelta,
            templateSwapWarning = templateSwap,
            templateSwapDetail = detail,
        )
    }

    private fun occurrences(
        text: String,
        substring: String,
    ): Int = text.split(substring).size - 1

    private fun sampleStdev(values: List<Int>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance)
    }

    private fun round(
        value: Double,
        places: Int,
    ): Double {
        val factor = Math.pow(10.0, places.toDouble())
        return Math.round(value * factor) / factor
    }

    private fun percent(share: Double): String = "${Math.round(share * 100)}%"
}
