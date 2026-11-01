/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.cleanup

import android.content.Context
import dev.patrickgold.florisboard.dictate.vocabulary.IndexedLexicon
import dev.patrickgold.florisboard.dictate.vocabulary.VocabularyCorrector
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import dev.patrickgold.florisboard.ime.nlp.latin.GlideDictionaryManager
import dev.patrickgold.florisboard.ime.nlp.latin.NgramIndex

/** One immutable mapped index, shared by requests. Must be called on Dispatchers.IO.
 * Content-addressed install, verified before atomic rename. Never downloads at runtime.
 */
internal object VocabularyResources {
    @Volatile private var cached: VocabularyCorrector? = null

    @Synchronized fun get(context: Context): VocabularyCorrector {
        cached?.let { return it }
        val manifest = context.assets.open("vocabulary/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val sha = manifest.getString("sha256")
        val size = manifest.getLong("bytes")
        val directory = File(context.noBackupFilesDir, "vocabulary").apply { check(isDirectory || mkdirs()) }
        val destination = File(directory, "$sha.lex")
        // Content-addressed name plus this marker: after a verified install, a cold start costs a
        // stat instead of re-hashing 41 MB on the request path.
        val verified = File(directory, "$sha.ok")
        fun hashed(file: File): Boolean {
            if (!file.isFile || file.length() != size) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val block = ByteArray(65536)
                while (true) { val read = stream.read(block); if (read < 0) break; digest.update(block, 0, read) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == sha
        }
        val installed = verified.isFile && destination.isFile && destination.length() == size
        if (!installed && !hashed(destination)) {
            val temporary = File.createTempFile("vocabulary-", ".tmp", directory)
            try {
                // aapt2 expands a `.gz` asset and drops the suffix, so the APK holds
                // `vocabulary/words.lex`; a build that keeps the archive is handled too.
                val raw = runCatching { context.assets.open("vocabulary/words.lex") }
                    .getOrElse { GZIPInputStream(context.assets.open("vocabulary/words.lex.gz")) }
                raw.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val block = ByteArray(65536)
                        var total = 0L
                        while (true) {
                            val read = input.read(block)
                            if (read < 0) break
                            total += read
                            check(total <= size) { "Vocabulary size mismatch" }
                            output.write(block, 0, read)
                        }
                        check(total == size) { "Vocabulary size mismatch" }
                    }
                }
                check(hashed(temporary)) { "Vocabulary checksum mismatch" }
                check(temporary.renameTo(destination)) { "Cannot install vocabulary index" }
                verified.writeText(sha)
            } finally { temporary.delete() }
        }
        val mapped = RandomAccessFile(destination, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, size) }
        // Reuse the existing packaged typing frequencies; no extra corpus download or inference.
        val frequencies = context.assets.open("ime/dict/en.json").bufferedReader().use { JSONObject(it.readText()) }
        val englishContext = context.assets.open("ime/dict/en_bigrams.txt").bufferedReader().use { NgramIndex.parse(it.readText()) }
        // Reuse already installed Russian typing data when present; never trigger downloads.
        val russianFrequencies = runCatching {
            if (GlideDictionaryManager.isInstalled(context, "ru")) {
                JSONObject(GlideDictionaryManager.dictFile(context, "ru").readText())
            } else null
        }.getOrNull()
        val russianContext = runCatching {
            if (GlideDictionaryManager.bigramInstalled(context, "ru")) {
                NgramIndex.parse(GlideDictionaryManager.bigramFile(context, "ru").readText())
            } else null
        }.getOrNull()
        return VocabularyCorrector(
            IndexedLexicon(mapped),
            contextScore = { previous, word, next ->
                val table = if (word.any { it in 'а'..'я' || it == 'ё' }) russianContext else englishContext
                val left = if (previous != null) table?.countOf("$previous $word") ?: 0L else 0L
                val right = if (next != null) table?.countOf("$word $next") ?: 0L else 0L
                kotlin.math.ln(1.0 + left + right) * 0.025
            },
            frequency = { maxOf(frequencies.optInt(it, 0), russianFrequencies?.optInt(it, 0) ?: 0) },
        ).also { cached = it }
    }
}
