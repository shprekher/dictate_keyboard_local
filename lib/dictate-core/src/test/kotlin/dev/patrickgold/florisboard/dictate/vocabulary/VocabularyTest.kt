/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.vocabulary

import java.nio.ByteBuffer
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class VocabularyTest {
    private val empty = PersonalVocabulary(emptyList())
    private val corrector = VocabularyCorrector(Lexicon { it in setOf("hello", "world", "кринж", "слово", "привет") })

    @Test fun `longest explicit personal phrase wins and replacements never cascade`() {
        val p = PersonalVocabulary.parse("OpenCode | open code; опен код\nCode | код\nEditor | OpenCode editor")
        assertEquals("OpenCode и Code, Editor", p.apply("опен код и код, OpenCode editor"))
        assertEquals("ОпенКод", PersonalVocabulary.parse("ОпенКод | опенкод").apply("опенкод"))
    }
    @Test fun `canonical casing wins while punctuation and line breaks stay untouched`() {
        val p = PersonalVocabulary.parse("iPhone\nOpenCode | open code")
        assertEquals("iPhone!\nOpenCode.", p.apply("IPHONE!\nopen code."))
        assertEquals("open\ncode", p.apply("open\ncode"))
        assertEquals("https://host/open-code name@iphone.org", p.apply("https://host/open-code name@iphone.org"))
    }
    @Test fun `invalid conflicting and meaning-changing entries fail before save`() {
        for (raw in listOf("A | b\nC | b", "A\na", "x".repeat(97), "A | не A", "five | six", "A | 10", "A || B")) {
            assertFailsWith<IllegalArgumentException>(raw) { PersonalVocabulary.parse(raw) }
        }
    }
    @Test fun `deletion means removing a line and no old value is silently imported`() {
        assertEquals("open code", PersonalVocabulary.parse("").apply("open code"))
        assertEquals(0, empty.entries.size)
    }
    @Test fun `unknowns slang names identifiers and code switching are not automatic errors`() {
        for (text in listOf("кринж кринжовый кринжовыми вайбом рофлить рофлим имбовая", "deploy задеплоили hotfix", "Бондур и Loqira", "хз ну типа ага", "вжухонатор")) {
            assertEquals(text, corrector.analyze(text, empty).text)
        }
        assertTrue(corrector.analyze("кринжовыми вайбовый рофлишь имбовая", empty).candidates.isEmpty())
    }
    @Test fun `one edit and phonetic candidates are ranked but not automatically inserted`() {
        val analysis = corrector.analyze("hellp приветт", empty)
        assertEquals("hellp приветт", analysis.text)
        assertEquals("hello", analysis.candidates.getValue("hellp").first().word)
        assertEquals("привет", analysis.candidates.getValue("приветт").first().word)
        assertEquals(VocabularyCorrector.phonetic("привет"), VocabularyCorrector.phonetic("привед"))
    }
    @Test fun `context and frequency break candidate ties without changing unknown words`() {
        val c = VocabularyCorrector(
            Lexicon { it in setOf("cat", "cot", "cut") },
            frequency = { if (it == "cot") 10000 else 0 },
        )
        val ranked = c.analyze("coot cot", empty).candidates.getValue("coot")
        assertEquals("cot", ranked.first().word)
        assertEquals(1, ranked.first().editDistance)
    }
    @Test fun `dictionary membership handles inflected forms independently`() {
        val c = VocabularyCorrector(Lexicon { it in setOf("работающими", "кошками", "developers") })
        assertTrue(c.analyze("работающими кошками developers", empty).candidates.isEmpty())
    }
    @Test fun `neighboring context ranks candidates and does not authorize substitutions`() {
        val c = VocabularyCorrector(Lexicon { it in setOf("cat", "cot", "cut") },
            contextScore = { previous, candidate, next -> if (previous == "a" && candidate == "cat" && next == "sleeps") 0.9 else 0.0 })
        val result = c.analyze("a caat sleeps", empty)
        assertEquals("cat", result.candidates.getValue("caat").first().word)
        assertEquals("a caat sleeps", result.text)
    }
    @Test fun `mapped lexicon is case insensitive and validates format`() {
        val terms = listOf("hello", "world", "кринж", "кошками").sorted().map { it.toByteArray() }
        val pool = terms.sumOf { it.size }
        val buffer = ByteBuffer.allocate(8 + (terms.size + 1) * 4 + pool)
        buffer.putInt(0x44564c31).putInt(terms.size)
        var offset = 0
        buffer.putInt(offset)
        terms.forEach { offset += it.size; buffer.putInt(offset) }
        terms.forEach { buffer.put(it) }
        buffer.flip()
        val lexicon = IndexedLexicon(buffer)
        assertTrue(lexicon.contains("HELLO")); assertTrue(lexicon.contains("Кошками")); assertFalse(lexicon.contains("hellp"))
        assertFailsWith<IllegalArgumentException> { IndexedLexicon(ByteBuffer.allocate(12)) }
    }
    @Test fun `pipeline preserves personal spellings quantities negations slang and register`() = runBlocking {
        val p = PersonalVocabulary.parse("OpenCode | опен код")
        val raw = "опен код не меняет кринжовый вайб за пять рублей"
        val expected = "OpenCode не меняет кринжовый вайб за 5 рублей"
        for (bad in listOf("OpenCode меняет атмосферу за шесть рублей", "OpenCode не меняет неприятную атмосферу за пять рублей", "Added a new phrase.")) {
            assertEquals(expected, TranscriptRefinement.refine(raw, p, corrector, true) { bad })
        }
        assertEquals("OpenCode не меняет кринжовый вайб за 5 рублей.", TranscriptRefinement.refine(raw, p, corrector, true) { "$it." })
    }
    @Test fun `failure keeps safe personal output and still normalizes numbers without AI`() = runBlocking {
        assertEquals("25", TranscriptRefinement.refine("twenty five", empty, corrector, true) { error("offline failure") })
        assertEquals("twenty five", TranscriptRefinement.refine("twenty five", empty, corrector, false) { it })
        assertFailsWith<CancellationException> { TranscriptRefinement.refine("hello", empty, corrector, true) { throw CancellationException() } }
    }
    @Test fun `personal vocabulary has priority over filler deletion and number normalization`() = runBlocking {
        val personal = PersonalVocabulary.parse("um\nOne\ntwenty five")
        assertEquals("um hello", TranscriptRefinement.refine("um hello", personal, corrector, true) { "hello" })
        assertEquals("One dollar", TranscriptRefinement.refine("one dollar", personal, corrector, true) { it })
        assertEquals("twenty five samples", TranscriptRefinement.refine("twenty five samples", personal, corrector, true) { it })
    }
    @Test fun `meaning guard rejects inserted words reordered words names numbers symbols and negations`() {
        for ((before, after) in listOf("не надо" to "надо", "do not go" to "do go", "кринжовый вайб" to "неприятное ощущение",
            "I paid 1.05" to "I paid 1.50", "minus five" to "five", "10%" to "10", "John" to "Jane", "hello world" to "world hello", "US" to "us", "iPhone" to "Iphone")) {
            assertFalse(MeaningGuard.accept(before, after), "$before -> $after")
        }
        assertTrue(MeaningGuard.accept("um hello hello", "Hello!"))
        assertFalse(MeaningGuard.accept("не не надо", "не надо"))
        assertFalse(MeaningGuard.accept("hello", "um hello"))
        assertFalse(MeaningGuard.accept("hello", "hello hello"))
    }
    @Test fun `bounded work for malformed long utterances`() = runBlocking {
        val raw = "x".repeat(100001)
        assertEquals(raw, TranscriptRefinement.refine(raw, empty, corrector, true) { error("must not run") })
    }
}
