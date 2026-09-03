package com.zeroclaw.android.ui.screen.dashboard

import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WelcomeViewModelTest {
    @Test
    fun `buildGreetingText uses evening before five am`() {
        val result = buildGreetingText("", LocalTime.of(4, 59))
        assertTrue(result.endsWith(", User!"), "Should end with ', User!': $result")
        assertTrue(result in EVENING_GREETINGS.map { "$it, User!" }, "Should use evening greeting: $result")
    }

    @Test
    fun `buildGreetingText uses morning from five am`() {
        val result = buildGreetingText("", LocalTime.of(5, 0))
        assertTrue(result.endsWith(", User!"), "Should end with ', User!': $result")
        assertTrue(result in MORNING_GREETINGS.map { "$it, User!" }, "Should use morning greeting: $result")
    }

    @Test
    fun `buildGreetingText uses morning until noon`() {
        val result = buildGreetingText("", LocalTime.of(11, 59))
        assertTrue(result.endsWith(", User!"), "Should end with ', User!': $result")
        assertTrue(result in MORNING_GREETINGS.map { "$it, User!" }, "Should use morning greeting: $result")
    }

    @Test
    fun `buildGreetingText uses afternoon from noon`() {
        val result = buildGreetingText("", LocalTime.of(12, 0))
        assertTrue(result.endsWith(", User!"), "Should end with ', User!': $result")
        assertTrue(result in AFTERNOON_GREETINGS.map { "$it, User!" }, "Should use afternoon greeting: $result")
    }

    @Test
    fun `buildGreetingText uses afternoon until six pm`() {
        val result = buildGreetingText("", LocalTime.of(17, 59))
        assertTrue(result.endsWith(", User!"), "Should end with ', User!': $result")
        assertTrue(result in AFTERNOON_GREETINGS.map { "$it, User!" }, "Should use afternoon greeting: $result")
    }

    @Test
    fun `buildGreetingText uses evening from six pm`() {
        val result = buildGreetingText("", LocalTime.of(18, 0))
        assertTrue(result.endsWith(", User!"), "Should end with ', User!': $result")
        assertTrue(result in EVENING_GREETINGS.map { "$it, User!" }, "Should use evening greeting: $result")
    }

    @Test
    fun `buildGreetingText is deterministic for same time`() {
        val time = LocalTime.of(9, 30)
        val first = buildGreetingText("Alice", time)
        val second = buildGreetingText("Alice", time)
        assertEquals(first, second, "Same time should produce same greeting")
    }

    @Test
    fun `buildGreetingText varies across different times in same period`() {
        val greetings =
            (5..11).flatMap { hour ->
                (0..59 step 15).map { minute ->
                    buildGreetingText("User", LocalTime.of(hour, minute))
                }
            }.toSet()
        assertTrue(greetings.size > 1, "Should produce different greetings across morning times")
    }

    @Test
    fun `extractUserName falls back for blank or invalid identity json`() {
        assertEquals("User", extractUserName(""))
        assertEquals("User", extractUserName("{not-valid-json"))
    }

    @Test
    fun `extractUserName reads nested identity user name`() {
        assertEquals(
            "Tanmay",
            extractUserName("""{"identity":{"user_name":"Tanmay"}}"""),
        )
    }

    @Test
    fun `resolveWelcomeMessageState hides welcome when already shown today`() {
        val now = LocalDateTime.of(2026, 4, 8, 9, 30)

        val state =
            resolveWelcomeMessageState(
                identityJson = """{"identity":{"user_name":"Tanmay"}}""",
                lastShownDate = "2026-04-08",
                now = now,
            )

        assertFalse(state.shouldShowWelcome)
        assertTrue(state.greetingText.endsWith(", Tanmay!"), "Should greet Tanmay: ${state.greetingText}")
        assertEquals("2026-04-08", state.todayIsoDate)
    }

    @Test
    fun `resolveWelcomeMessageState shows welcome when last shown on an older day`() {
        val now = LocalDateTime.of(2026, 4, 8, 19, 45)

        val state =
            resolveWelcomeMessageState(
                identityJson = """{"identity":{"user_name":"Tanmay"}}""",
                lastShownDate = "2026-04-07",
                now = now,
            )

        assertTrue(state.shouldShowWelcome)
        assertTrue(state.greetingText.endsWith(", Tanmay!"), "Should greet Tanmay: ${state.greetingText}")
        assertTrue(
            state.greetingText in EVENING_GREETINGS.map { "$it, Tanmay!" },
            "Should use evening greeting: ${state.greetingText}",
        )
        assertEquals("2026-04-08", state.todayIsoDate)
    }
}
