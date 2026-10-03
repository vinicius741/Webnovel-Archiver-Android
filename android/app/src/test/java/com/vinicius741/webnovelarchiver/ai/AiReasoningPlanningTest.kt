package com.vinicius741.webnovelarchiver.ai

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.settings.AiReasoningEffort
import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AiReasoningPlanningTest {
    @Test
    fun `catalog restricts levels and mandatory models cannot turn reasoning off`() {
        val model = model(OpenRouterReasoningOptions(listOf("xhigh", "high", "none", "future"), mandatory = true))
        assertEquals(listOf("high", "xhigh"), AiReasoningPlanning.allowedEfforts(model))
        assertNull(AiReasoningPlanning.effortFor(AiSettings(reasoningEfforts = mapOf("m" to "none")), "m", model))
    }

    @Test
    fun `null efforts accept gateway levels while omitted efforts and non reasoning models expose none`() {
        assertEquals(AiReasoningEffort.levels, AiReasoningPlanning.allowedEfforts(model(OpenRouterReasoningOptions(null))))
        assertEquals(emptyList<String>(), AiReasoningPlanning.allowedEfforts(model(OpenRouterReasoningOptions())))
        assertEquals(emptyList<String>(), AiReasoningPlanning.allowedEfforts(model(null)))
        assertEquals(AiReasoningEffort.levels, AiReasoningPlanning.allowedEfforts(null))
    }

    @Test
    fun `choices are retained by model and default omits an explicit effort`() {
        val settings = AiSettings(reasoningEfforts = mapOf("rewriter" to "high", "verifier" to "minimal", "m" to "default"))
        assertEquals("high", AiReasoningPlanning.effortFor(settings, "rewriter", null))
        assertEquals("minimal", AiReasoningPlanning.effortFor(settings, "verifier", null))
        assertNull(AiReasoningPlanning.effortFor(settings, "m", null))
        assertEquals("low", AiReasoningPlanning.effortFor(settings, "new-model", null))
    }

    @Test
    fun `existing low default is retained only when the catalog allows it`() {
        assertEquals("low", AiReasoningPlanning.effortFor(AiSettings(), "m", model(OpenRouterReasoningOptions(null))))
        assertNull(AiReasoningPlanning.effortFor(AiSettings(), "m", model(OpenRouterReasoningOptions(listOf("high")))))
        assertNull(AiReasoningPlanning.effortFor(AiSettings(), "m", model(null)))
    }

    @Test
    fun `settings round trip remembers levels and old settings retain the low default`() {
        val gson = Gson()
        val settings = AiSettings(reasoningEfforts = mapOf("m" to "xhigh", "other" to "default"))
        assertEquals(settings, PreferenceNormalization.aiSettings(gson.fromJson(gson.toJson(settings), AiSettings::class.java)))
        val legacy = PreferenceNormalization.aiSettings(gson.fromJson("{}", AiSettings::class.java))
        assertEquals(emptyMap<String, String>(), legacy.reasoningEfforts)
        assertEquals("low", AiReasoningPlanning.effortFor(legacy, "m", null))
    }

    @Test
    fun `normalization drops malformed values without losing model choices`() {
        val parsed =
            Gson().fromJson(
                """{"reasoningEfforts":{" m ":" high ","bad":"ultra","":"low","null":null}}""",
                AiSettings::class.java,
            )
        assertEquals(mapOf("m" to "high"), PreferenceNormalization.aiSettings(parsed).reasoningEfforts)
        val nullMap = Gson().fromJson("""{"reasoningEfforts":null}""", AiSettings::class.java)
        assertFalse(PreferenceNormalization.aiSettings(nullMap).reasoningEfforts.isNotEmpty())
    }

    private fun model(options: OpenRouterReasoningOptions?) = OpenRouterModel("m", "Model", "0", "0", reasoning = options)
}
