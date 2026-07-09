package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RegexCircuitBreakerTest {
    @Before
    fun setUp() = RegexCircuitBreaker.reset()

    @After
    fun tearDown() = RegexCircuitBreaker.reset()

    private val rule =
        RegexCleanupRule(id = "r1", name = "pathological", pattern = "(a+)+$", flags = "", enabled = true, appliesTo = "both")

    @Test
    fun ruleIsNotDisabledBeforeAnyStrikes() {
        assertFalse(RegexCircuitBreaker.isDisabled(rule))
    }

    @Test
    fun ruleDisabledAfterRepeatedSlowApplications() {
        // Report three slow applications (each over the 250ms threshold). After the third, the rule
        // is disabled for the session.
        repeat(3) { RegexCircuitBreaker.report(rule, elapsedNanos = 300_000_000L) }

        assertTrue(RegexCircuitBreaker.isDisabled(rule))
    }

    @Test
    fun ruleNotDisabledBeforeStrikeThreshold() {
        repeat(2) { RegexCircuitBreaker.report(rule, elapsedNanos = 300_000_000L) }

        assertFalse(RegexCircuitBreaker.isDisabled(rule))
    }

    @Test
    fun fastApplicationResetsStrikeCount() {
        RegexCircuitBreaker.report(rule, elapsedNanos = 300_000_000L)
        RegexCircuitBreaker.report(rule, elapsedNanos = 300_000_000L)
        // A fast success resets the consecutive-strike count, so the next slow call does not trip.
        RegexCircuitBreaker.report(rule, elapsedNanos = 10_000_000L)
        RegexCircuitBreaker.report(rule, elapsedNanos = 300_000_000L)

        assertFalse(RegexCircuitBreaker.isDisabled(rule))
    }

    @Test
    fun failingApplicationCountsAsStrike() {
        repeat(3) { RegexCircuitBreaker.report(rule, elapsedNanos = 1_000L, failed = true) }

        assertTrue(RegexCircuitBreaker.isDisabled(rule))
    }

    @Test
    fun distinctRulesTrackedIndependently() {
        val otherRule = rule.copy(id = "r2", pattern = "(b+)+$")

        repeat(3) { RegexCircuitBreaker.report(rule, elapsedNanos = 300_000_000L) }

        assertTrue(RegexCircuitBreaker.isDisabled(rule))
        assertFalse(RegexCircuitBreaker.isDisabled(otherRule))
    }

    @Test
    fun cleanupEngineSkipsDisabledRule() {
        // Use a rule that WOULD match if applied, so skipping is observable: a disabled rule leaves
        // the matched text in place. The breaker key is pattern+flags+appliesTo, so this rule and the
        // one disabled above share a key only if identical — use a distinct matching rule here.
        val matchingRule =
            RegexCleanupRule(id = "r-match", name = "remover", pattern = "marker", flags = "", enabled = true, appliesTo = "download")
        repeat(3) { RegexCircuitBreaker.report(matchingRule, elapsedNanos = 300_000_000L) }

        val html = "<p>marker here</p>"
        val result = CleanupEngine().applyDownloadWithStats(html, emptyList(), listOf(matchingRule))

        // "marker" would have been removed if the rule applied; since it is disabled, it survives.
        assertTrue("disabled rule must not alter the text", result.html.contains("marker"))
    }
}
