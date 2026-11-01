/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.cleanup

import kotlin.test.Test
import kotlin.test.assertEquals

class EnglishHesitationFilterTest {
    @Test
    fun `nonmatching input is byte-for-byte unchanged including paragraph breaks`() {
        for (input in listOf("  First  paragraph.\r\n\r\n\tSecond paragraph.  ", "Первый абзац.\n\nВторой абзац.")) {
            assertEquals(input, LocalCleanupText.withoutHesitations(input))
        }
    }

    @Test
    fun `quoted sounds and acronyms retain their literal meaning`() {
        for (input in listOf(
            "Use the UM model and the UH campus.",
            "He said \"uh\", not \"ah\".",
            "He said \"I uh think so\".",
            "He said 'I don't uh think so'.",
            "He said ‘I don’t uh think so’.",
            "He said «I uh think so».",
            "He said „I uh think so“.",
            "An unfinished quote: \"I uh think",
            "He said \"I \\\"uh\\\" think so\".",
            "Keep `I uh think` verbatim.",
        )) {
            assertEquals(input, LocalCleanupText.withoutHesitations(input), input)
        }
        assertEquals("He said \"uh\".", LocalCleanupText.withoutHesitations("Um, He said \"uh\"."))
        assertEquals("He said \"uh\", I agree.", LocalCleanupText.withoutHesitations("He said \"uh\", um, I agree."))
    }

    @Test
    fun `compounds accented words identifiers and addresses are preserved`() {
        for (input in listOf(
            "Uh-huh, um-hum, uh‑huh, uh–huh, uh—huh, uh−huh.",
            "Umbrella, human, uh_thing, um2, uh́, um’s.",
            "Visit uh.com and example.um or https://example.com/um.",
            "Use @uh or uh@example.com and folder/um or C:\\um.",
        )) {
            assertEquals(input, LocalCleanupText.withoutHesitations(input), input)
        }
    }

    @Test
    fun `known non-English and mixed-script input is not filtered`() {
        for (language in listOf("ru", "kk", "de", "fr-FR")) {
            val input = "Um, five.\n\nUh, six."
            assertEquals(input, LocalCleanupText.withoutHesitations(input, language))
        }
        assertEquals("Сказал um слово.", LocalCleanupText.withoutHesitations("Сказал um слово.", "detect"))
        assertEquals("Hello, uh, Анна.", LocalCleanupText.withoutHesitations("Hello, uh, Анна.", "en"))
        assertEquals("I agree.", LocalCleanupText.withoutHesitations("Uh, I agree.", "EN-us"))
    }

    @Test
    fun `possible names and measurements are not treated as sounds`() {
        for (input in listOf("Ask Um about the meeting.", "Um Smith joined yesterday.", "Keep a gap of 20 um.", "Use um 20.")) {
            assertEquals(input, LocalCleanupText.withoutHesitations(input), input)
        }
        assertEquals("Keep a gap of 20 um.", LocalCleanupText.withoutHesitations("Uh, Keep a gap of 20 um."))
    }

    @Test
    fun `only whitespace adjacent to a removed sound is repaired`() {
        assertEquals("I think we should meet.", LocalCleanupText.withoutHesitations("Uh, I, uh, think we should meet."))
        assertEquals("I think.", LocalCleanupText.withoutHesitations("I, uh, um, think."))
        assertEquals("I think.", LocalCleanupText.withoutHesitations("I uh think."))
        assertEquals("I.", LocalCleanupText.withoutHesitations("I uh."))
        assertEquals("We met. It rained.", LocalCleanupText.withoutHesitations("We met. Uh. It rained."))
        assertEquals("First.\n\nSecond.", LocalCleanupText.withoutHesitations("First uh.\n\nUm, Second."))
        assertEquals("  I  think.\n\n\tI agree.  ", LocalCleanupText.withoutHesitations("  Uh, I  think.\n\n\tUm, I agree.  "))
    }

    @Test
    fun `filtering is idempotent`() {
        for (input in listOf("Uh, I, uh, think.", "I, uh, um, think.", "\"I uh think\" um I agree.", "uh um")) {
            val once = LocalCleanupText.withoutHesitations(input)
            assertEquals(once, LocalCleanupText.withoutHesitations(once))
        }
    }
}
