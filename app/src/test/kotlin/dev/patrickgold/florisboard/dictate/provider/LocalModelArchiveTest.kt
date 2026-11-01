/* Copyright (C) 2026. Licensed under the Apache License, Version 2.0. */
package dev.patrickgold.florisboard.dictate.provider

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LocalModelArchiveTest {
    private val content = "test model".toByteArray()
    private val file = LocalModelFile(
        "https://example.org/model.tar.bz2", "tokenizer/vocab.json", content.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
    )

    private fun archive(vararg members: Pair<String, String>) =
        LocalModelArchive(file.url, 1, "0".repeat(64), members.toMap())

    private fun packed(dir: File, vararg paths: String): File = File(dir, "test.tar.bz2").also { packed ->
        TarArchiveOutputStream(BZip2CompressorOutputStream(packed.outputStream())).use { tar ->
            for (path in paths) {
                tar.putArchiveEntry(TarArchiveEntry(path).apply { size = content.size.toLong() })
                tar.write(content)
                tar.closeArchiveEntry()
            }
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("model-archive-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test
    fun `only selected members are installed with nested destinations`() = withDirectory { dir ->
        runBlocking {
            LocalModelManager.extractArchive(
                packed(dir, "pkg/vocab.json", "../../escape.txt"),
                archive("pkg/vocab.json" to file.destName), listOf(file), dir,
            )
        }
        assertEquals(String(content), File(dir, file.destName).readText())
        assertFalse(File(dir, "escape.txt").exists())
    }

    @Test
    fun `missing members and duplicate members are rejected`() = withDirectory { dir ->
        for (paths in listOf(arrayOf("other"), arrayOf("pkg/vocab.json", "pkg/vocab.json"))) {
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    LocalModelManager.extractArchive(
                        packed(dir, *paths), archive("pkg/vocab.json" to file.destName), listOf(file), dir,
                    )
                }
            }
        }
    }

    @Test
    fun `wrong sizes and hashes are rejected`() = withDirectory { dir ->
        for (bad in listOf(file.copy(sizeBytes = 1), file.copy(sha256 = "0".repeat(64)))) {
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    LocalModelManager.extractArchive(
                        packed(dir, "pkg/vocab.json"), archive("pkg/vocab.json" to file.destName), listOf(bad), dir,
                    )
                }
            }
        }
    }

    @Test
    fun `catalog destinations cannot escape staging directory`() = withDirectory { dir ->
        val bad = file.copy(destName = "../outside-model.json")
        assertFailsWith<IllegalStateException> {
            runBlocking {
                LocalModelManager.extractArchive(
                    packed(dir, "pkg/vocab.json"), archive("pkg/vocab.json" to bad.destName), listOf(bad), dir,
                )
            }
        }
    }

    @Test
    fun `selected symlinks are rejected`() = withDirectory { dir ->
        val packed = File(dir, "link.tar.bz2")
        TarArchiveOutputStream(BZip2CompressorOutputStream(packed.outputStream())).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("pkg/vocab.json", TarConstants.LF_SYMLINK).apply {
                linkName = "../../outside-model.json"
            })
            tar.closeArchiveEntry()
        }
        assertFailsWith<IllegalStateException> {
            runBlocking {
                LocalModelManager.extractArchive(
                    packed, archive("pkg/vocab.json" to file.destName), listOf(file), dir,
                )
            }
        }
    }
}
