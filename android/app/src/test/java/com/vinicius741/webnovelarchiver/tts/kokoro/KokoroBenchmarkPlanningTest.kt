package com.vinicius741.webnovelarchiver.tts.kokoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroBenchmarkPlanningTest {
    @Test
    fun `the plan starts and ends on the app's setting with other thread counts and two copies between`() {
        assertEquals(
            listOf(
                KokoroBenchmarkStep(1, 4),
                KokoroBenchmarkStep(1, 2),
                KokoroBenchmarkStep(1, 6),
                KokoroBenchmarkStep(1, 8),
                KokoroBenchmarkStep(2, 4),
                KokoroBenchmarkStep(1, 4),
            ),
            KokoroBenchmarkPlanning.plan(cores = 8, appThreads = 4),
        )
    }

    @Test
    fun `the plan never asks for more threads than the phone has cores`() {
        val plan = KokoroBenchmarkPlanning.plan(cores = 4, appThreads = 4)
        assertEquals(
            listOf(KokoroBenchmarkStep(1, 4), KokoroBenchmarkStep(1, 2), KokoroBenchmarkStep(2, 2), KokoroBenchmarkStep(1, 4)),
            plan,
        )
        assertEquals(listOf(KokoroBenchmarkStep(1, 4)), KokoroBenchmarkPlanning.plan(cores = 1, appThreads = 4))
    }

    @Test
    fun `a task stat line yields its cpu time and core even when the name has spaces and parentheses`() {
        val line =
            "4242 (onnx (pool) 1) R 100 100 0 0 -1 4194368 120 0 0 0 350 25 0 0 20 0 30 0 " +
                "12345 1000000 500 18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 0 -1 6 0 0 0 0 0"
        assertEquals(KokoroTaskSample(cpuTicks = 375L, core = 6), KokoroBenchmarkPlanning.parseTaskStat(line))
        assertNull(KokoroBenchmarkPlanning.parseTaskStat("4242 (short) R 1 2 3"))
        assertNull(KokoroBenchmarkPlanning.parseTaskStat("garbage"))
    }

    @Test
    fun `core shares are whole percents and cores with no work count as zero`() {
        assertEquals(listOf(0, 25, 0, 75), KokoroBenchmarkPlanning.percents(mapOf(1 to 10L, 3 to 30L), cores = 4))
        assertEquals(listOf(0, 0), KokoroBenchmarkPlanning.percents(emptyMap(), cores = 2))
    }

    @Test
    fun `cores group into clusters by top clock`() {
        assertEquals(
            listOf(listOf(0, 1, 2) to 2_000, listOf(3, 4, 5, 6) to 3_200, listOf(7) to 4_300),
            KokoroBenchmarkPlanning.clusters(listOf(2_000, 2_000, 2_000, 3_200, 3_200, 3_200, 3_200, 4_300)),
        )
    }

    @Test
    fun `the verdict compares generation speed with the listening speed`() {
        val slow = run(threads = 4, audioMs = 8_000L, wallMs = 10_000L)
        assertTrue(KokoroBenchmarkPlanning.verdict(slow, 1.0f).contains("pauses between sentences"))
        val fast = run(threads = 4, audioMs = 30_000L, wallMs = 10_000L)
        assertTrue(KokoroBenchmarkPlanning.verdict(fast, 1.5f).contains("keeps up"))
        val close = run(threads = 4, audioMs = 15_500L, wallMs = 10_000L)
        assertTrue(KokoroBenchmarkPlanning.verdict(close, 1.5f).contains("barely"))
        assertTrue(KokoroBenchmarkPlanning.verdict(null, 1.0f).startsWith("No result"))
    }

    @Test
    fun `the report states headroom, throttling, the fastest setup and carries the data`() {
        val first =
            run(
                threads = 4,
                audioMs = 9_000L,
                wallMs = 10_000L,
                cpu = KokoroCpuUsage(32_000L, 10_000L, listOf(5, 5, 5, 20, 20, 20, 20, 5), emptyList()),
            )
        val parallel = run(threads = 4, audioMs = 24_000L, wallMs = 12_000L, engines = 2)
        val repeat = run(threads = 4, audioMs = 7_000L, wallMs = 10_000L)
        val failed = KokoroBenchmarkRun(engines = 1, threads = 8, error = "the model failed to load")
        val report = KokoroBenchmarkReport.report(result(listOf(first, failed, parallel, repeat)))

        assertTrue(report.contains("makes audio 0.90x as fast as it plays"))
        assertTrue(report.contains("Fastest: 2 copies × 4 threads at 2.00x, 2.22x the app's setting."))
        assertTrue(report.contains("kept 3.2 of 8 cores busy (40%)"))
        assertTrue(report.contains("cores 0-2 (2000 MHz) 15%, cores 3-6 (3200 MHz) 80%, core 7 (4300 MHz) 5%"))
        assertTrue(report.contains("the phone slowed down as it warmed up"))
        assertTrue(report.contains("room for about 6 copies"))
        assertTrue(report.contains("8 threads · the model failed to load"))
        val data = org.json.JSONObject(report.substringAfter("--- data ---"))
        assertEquals(4, data.getJSONArray("runs").length())
        assertEquals(2, data.getJSONArray("runs").getJSONObject(2).getInt("engines"))
        assertFalse(data.getBoolean("cancelled"))
    }

    private fun run(
        threads: Int,
        audioMs: Long,
        wallMs: Long,
        engines: Int = 1,
        cpu: KokoroCpuUsage? = null,
    ) = KokoroBenchmarkRun(
        engines = engines,
        threads = threads,
        loadMs = 2_000L,
        warmupMs = 900L,
        modelMemoryMb = 500L,
        wallMs = wallMs,
        sentences = listOf(KokoroSentenceTiming(100, audioMs / 2, wallMs / 2), KokoroSentenceTiming(100, audioMs / 2, wallMs / 2)),
        cpu = cpu,
    )

    private fun result(runs: List<KokoroBenchmarkRun>): KokoroBenchmarkResult {
        val device =
            KokoroBenchmarkDevice(
                manufacturer = "Maker",
                model = "Phone",
                soc = "Chip",
                androidRelease = "16",
                sdk = 36,
                cores = 8,
                coreMaxMhz = listOf(2_000, 2_000, 2_000, 3_200, 3_200, 3_200, 3_200, 4_300),
                totalRamMb = 12_288L,
            )
        return KokoroBenchmarkResult(
            setup =
                KokoroBenchmarkSetup(
                    appVersion = "test",
                    startedAt = "2026-11-11 10:00",
                    device = device,
                    voiceName = "Bella",
                    listeningRate = 1.0f,
                    appThreads = 4,
                    narrationModelLoaded = false,
                    atStart = KokoroHeadroom(availableRamMb = 3_000L, thermalStatus = "none", thermalHeadroom = 0.3f),
                ),
            runs = runs,
            atEnd = KokoroHeadroom(availableRamMb = 2_800L, thermalStatus = "light", thermalHeadroom = 0.6f),
            totalMs = 200_000L,
        )
    }
}
