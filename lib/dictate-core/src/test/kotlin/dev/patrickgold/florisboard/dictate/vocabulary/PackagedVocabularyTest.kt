/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.vocabulary

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class PackagedVocabularyTest {
    @Test fun `packaged full dictionaries validate and benchmark the real offline index`() = runBlocking {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main/assets/vocabulary/manifest.json").isFile }
        val assets = File(root, "app/src/main/assets/vocabulary")
        val reportDir = File(root, "lib/dictate-core/build/reports").apply { mkdirs() }
        val temporary = File.createTempFile("vocabulary-", ".lex", reportDir)
        try {
            val started = System.nanoTime()
            val digest = MessageDigest.getInstance("SHA-256")
            GZIPInputStream(File(assets, "words.lex.gz").inputStream()).use { input ->
                temporary.outputStream().use { output ->
                    val block = ByteArray(65536)
                    while (true) {
                        val n = input.read(block); if (n < 0) break
                        digest.update(block, 0, n); output.write(block, 0, n)
                    }
                }
            }
            assertTrue(File(assets, "manifest.json").readText().contains(digest.digest().joinToString("") { "%02x".format(it) }))
            val manifestBytes = Regex("\"bytes\":\\s*(\\d+)").find(File(assets, "manifest.json").readText())!!.groupValues[1].toLong()
            assertEquals(manifestBytes, temporary.length())
            val buffer = RandomAccessFile(temporary, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            val lexicon = IndexedLexicon(buffer)
            val setupMs = (System.nanoTime() - started) / 1e6
            for (word in listOf("hello", "developers", "working", "кошками", "работающими", "русского", "словарь")) {
                assertTrue(lexicon.contains(word), word)
            }
            assertFalse(lexicon.contains("qzxvnotarealword"))
            val corrector = VocabularyCorrector(lexicon)
            val personal = PersonalVocabulary.parse("OpenCode | опен код\niPhone")
            val workloads = mapOf(
                "english" to "hello world we need twenty five samples for the developers",
                "russian" to "работающими словами проверяем кринжовый вайб за пять рублей",
                "mixed" to "опен код deploy hotfix и не меняй кринжовыми вайбами twenty five percent",
                "unknown8" to "hellp worle приветт словоо микросервиссы нефилологизма скриптованный задеплоенный",
                "long" to ("опен код не меняет кринжовый вайб за пять рублей. ".repeat(100)),
            )
            val results = workloads.map { (name, text) ->
                repeat(20) { TranscriptRefinement.refine(text, personal, corrector, true) { it } }
                val samples = (1..100).map {
                    val start = System.nanoTime()
                    TranscriptRefinement.refine(text, personal, corrector, true) { it }
                    (System.nanoTime() - start) / 1e6
                }.sorted()
                "\"$name\":{\"chars\":${text.length},\"p50_ms\":${samples[49]},\"p95_ms\":${samples[94]},\"max_ms\":${samples.last()}}"
            }
            File(reportDir, "vocabulary-benchmark.json").writeText(
                "{\"platform\":\"${System.getProperty("os.name")} ${System.getProperty("os.arch")} host JVM\",\"setup_ms\":$setupMs,\"index_bytes\":${temporary.length()},\"qwen_inference\":false,\"samples_per_workload\":100,\"workloads\":{${results.joinToString(",")}}}\n",
            )
        } finally { temporary.delete() }
    }
}
