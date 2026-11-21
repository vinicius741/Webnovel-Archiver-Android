package com.vinicius741.webnovelarchiver.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Source-scan checks for the layering and screen rules in android/AGENTS.md ("Architecture rules").
 * Text scans, not type-aware: they read `import` lines and fully qualified names in code (comments
 * excluded). An allowlist entry is a known seam with a reason; remove it when the seam goes away.
 *
 * The ratchets pin counts of the old screen pattern. A count above its baseline fails (the old
 * pattern grew); a count below fails too, so whoever shrinks it lowers the baseline in the same
 * change and the gain cannot be spent again.
 */
class ArchitectureRulesTest {
    private val root = File("src/main/java/com/vinicius741/webnovelarchiver")
    private val pkg = "com.vinicius741.webnovelarchiver"

    private val engines =
        listOf("data", "download", "sync", "epub", "cleanup", "narration", "tts", "ai", "source", "appupdate", "notification")

    /** `file:referenced` pairs that are allowed despite a rule. Keep each justified. */
    private val allowlist =
        setOf(
            // Retired-engine settings migration reads the built-in model catalog.
            "domain/settings/PreferenceNormalization.kt:tts.TtsEnginePlanning",
            // The download service's notification opens the Cloudflare solver activity.
            "download/DownloadForegroundService.kt:feature.browser.CloudflareSolveActivity",
            // Persisted AI record types that the AI screens display.
            "feature/ai/AiContextChapterPicker.kt:data.storage.EvidenceSelectionKind",
            "feature/ai/AiCoverGeneration.kt:data.storage.AiCoverDraftRecord",
            "feature/ai/AiCoverVersionsUi.kt:data.storage.AiCoverVersion",
            "feature/ai/AiJobChipPlanning.kt:data.storage.AiCoverResultKind",
        )

    private data class Ref(
        val file: String,
        val target: String,
    )

    private fun kotlinFiles(dir: String): List<File> = File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    /** Every app-package name referenced in code (imports and fully qualified uses), comments excluded. */
    private fun references(file: File): List<String> {
        val name = Regex("""\b${Regex.escape(pkg)}\.([a-z][\w]*(?:\.[\w]+)*)""")
        return file
            .readLines()
            .map { it.substringBefore("//").trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("package ") }
            .flatMap { line -> name.findAll(line).map { it.groupValues[1] }.toList() }
    }

    private fun violations(
        dirs: List<String>,
        forbidden: (String) -> Boolean,
    ): List<Ref> =
        dirs
            .flatMap { dir ->
                kotlinFiles(dir).flatMap { file ->
                    references(file)
                        .filter(forbidden)
                        .map { Ref(relative(file), it) }
                        .filterNot { ref ->
                            allowlist.any { ref.file == it.substringBefore(':') && ref.target.startsWith(it.substringAfter(':')) }
                        }
                }
            }.distinct()

    private fun assertNone(
        rule: String,
        found: List<Ref>,
    ) {
        assertTrue(
            "$rule\n" + found.joinToString("\n") { "  ${it.file} -> ${it.target}" },
            found.isEmpty(),
        )
    }

    @Test
    fun domainDependsOnNothingElse() {
        val androidImports =
            kotlinFiles("domain").filter { file ->
                file.readLines().any {
                    it.startsWith("import android.") ||
                        it.startsWith("import androidx.")
                }
            }
        assertTrue("domain/ must not import Android: ${androidImports.map(::relative)}", androidImports.isEmpty())
        assertNone("domain/ must not reference other app packages:", violations(listOf("domain")) { !it.startsWith("domain") })
    }

    @Test
    fun enginesDoNotReachIntoScreens() {
        assertNone(
            "Engines and data/ must not reference feature/ or ui/:",
            violations(engines) { it.startsWith("feature.") || it.startsWith("ui.") },
        )
    }

    @Test
    fun screensDoNotTouchStorage() {
        assertNone(
            "feature/ must not reference data.storage (go through the repository or operations/):",
            violations(listOf("feature")) { it.startsWith("data.storage") },
        )
    }

    @Test
    fun screenModelsStayFreeOfViews() {
        val models = File(root, "feature").walkTopDown().filter { it.isFile && it.name.endsWith("ScreenModel.kt") }
        val offenders =
            models
                .filter { file ->
                    file.readLines().any {
                        it.startsWith("import android.view") || it.startsWith("import android.widget") ||
                            it.startsWith("import android.content.Context")
                    }
                }.map(::relative)
                .toList()
        assertTrue("Screen models must not use Views or Context: $offenders", offenders.isEmpty())
    }

    private fun count(
        dir: String,
        pattern: Regex,
    ): Int = kotlinFiles(dir).sumOf { file -> file.readLines().count { line -> pattern.containsMatchIn(line.substringBefore("//")) } }

    private fun assertRatchet(
        name: String,
        actual: Int,
        baseline: Int,
    ) {
        assertTrue("$name grew to $actual (baseline $baseline). New code must use the screen-model pattern.", actual <= baseline)
        assertEquals("$name shrank to $actual. Lower its baseline in ArchitectureRulesTest to $actual.", baseline, actual)
    }

    @Test
    fun oldScreenPatternOnlyShrinks() {
        assertRatchet("`fun ScreenHost.` extensions", count(".", Regex("""\bfun\s+ScreenHost\.""")), baseline = 448)
        assertRatchet("`appContainer` lookups in feature/", count("feature", Regex("""\bappContainer\b""")), baseline = 93)
        val hostProperties =
            File(root, "navigation/ScreenHost.kt")
                .readLines()
                .dropWhile { !it.startsWith("interface ScreenHost") }
                .count { Regex("""^\s{4}(val|var)\s""").containsMatchIn(it) }
        assertRatchet("ScreenHost properties", hostProperties, baseline = 30)
    }

    @Test
    fun agentInstructionsStayLean() {
        val size = File("../AGENTS.md").length()
        assertTrue(
            "android/AGENTS.md is $size bytes; keep it under 15,000 and move subsystem detail to a package AGENTS.md.",
            size in 1..15_000,
        )
    }
}
