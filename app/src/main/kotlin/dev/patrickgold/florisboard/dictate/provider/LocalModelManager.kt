/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import android.content.Context
import dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads, installs and removes the on-device transcription models consumed by
 * [LocalTranscriptionProvider] (issue #104). A model is a small set of files ([LocalModelSpec]) fetched
 * over HTTPS into the app's private storage; no audio or telemetry is ever sent.
 *
 * Installs are atomic: files download into a `.tmp-<id>` staging dir, are size/checksum-verified, and
 * only then replace the real model dir. A failed or cancelled download leaves any previously installed
 * model untouched. All work runs on [Dispatchers.IO] and honours coroutine cancellation.
 */
object LocalModelManager {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // model files are large; no overall cap on the download
            .build()
    }

    /** True if [modelId] is fully installed (all required files present). */
    fun isInstalled(context: Context, modelId: String): Boolean =
        LocalCleanupModel.byIdOrNull(modelId)?.let { LocalCleanupModel.isInstalled(context, it) }
            ?: LocalTranscriptionProvider.isInstalled(context, modelId)

    /** Ids of all catalog models currently installed on disk. */
    fun installedIds(context: Context): List<String> =
        LocalModelCatalog.all.map { it.id }.filter { isInstalled(context, it) }

    /** Removes an installed model. Returns true if the directory is gone afterwards. */
    fun delete(context: Context, modelId: String): Boolean {
        val dir = LocalTranscriptionProvider.modelDir(context, modelId)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    /** On-disk size of an installed model in bytes (0 if not installed). */
    fun installedSizeBytes(context: Context, modelId: String): Long {
        val dir = LocalTranscriptionProvider.modelDir(context, modelId)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Downloads and installs [spec]. [onProgress] is invoked with `(downloadedBytes, totalBytes)` as the
     * download proceeds (frequently — throttle on the UI side). Suspends until done; throws on any
     * failure (network, HTTP, size/checksum mismatch) after cleaning up the staging dir.
     */
    suspend fun download(
        context: Context,
        spec: LocalModelSpec,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        val root = LocalTranscriptionProvider.modelsRoot(context)
        val finalDir = LocalTranscriptionProvider.modelDir(context, spec.id)
        val tmpDir = File(root, ".tmp-${spec.id}")
        tmpDir.deleteRecursively()
        check(tmpDir.mkdirs()) { "could not create staging dir ${tmpDir.absolutePath}" }
        try {
            val total = spec.totalBytes
            var completed = 0L
            spec.archive?.let { archive ->
                val packed = File(tmpDir, ".model.tar.bz2")
                downloadFile(
                    LocalModelFile(archive.url, packed.name, archive.sizeBytes, archive.sha256),
                    packed, 0L, total, onProgress,
                )
                extractArchive(packed, archive, spec.files, tmpDir)
                check(packed.delete()) { "could not remove downloaded model archive" }
                completed += archive.sizeBytes
            }
            for (f in spec.files.filter { it.destName !in spec.archive?.members.orEmpty().values }) {
                downloadFile(f, File(tmpDir, f.destName), completed, total, onProgress)
                completed += f.sizeBytes
                onProgress(completed, total)
            }
            // Swap staging dir into place atomically (same filesystem); copy-fallback if rename fails.
            finalDir.deleteRecursively()
            finalDir.parentFile?.mkdirs()
            if (!tmpDir.renameTo(finalDir)) {
                tmpDir.copyRecursively(finalDir, overwrite = true)
                tmpDir.deleteRecursively()
            }
        } catch (t: Throwable) {
            tmpDir.deleteRecursively()
            throw t
        }
    }

    /** Extract only catalogued regular files. Archive paths never become filesystem paths. */
    internal suspend fun extractArchive(
        packed: File,
        archive: LocalModelArchive,
        files: List<LocalModelFile>,
        stagingDir: File,
    ) {
        val expected = files.associateBy { it.destName }
        val found = mutableSetOf<String>()
        val root = stagingDir.canonicalFile
        packed.inputStream().buffered().use { raw ->
            TarArchiveInputStream(BZip2CompressorInputStream(raw)).use { tar ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = tar.nextTarEntry ?: break
                    val name = archive.members[entry.name] ?: continue
                    check(found.add(entry.name)) { "duplicate model archive member: ${entry.name}" }
                    val file = checkNotNull(expected[name]) { "uncatalogued archive destination: $name" }
                    check(entry.isFile && !entry.isLink && !entry.isSymbolicLink) { "not a regular file: ${entry.name}" }
                    check(entry.size == file.sizeBytes) { "archive size mismatch for $name" }
                    val dest = File(root, name).canonicalFile
                    check(dest.toPath().startsWith(root.toPath()) && dest != root) { "unsafe model destination: $name" }
                    check(dest.parentFile!!.isDirectory || dest.parentFile!!.mkdirs()) { "cannot create model directory" }
                    dest.outputStream().buffered().use { output ->
                        var written = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = tar.read(buffer)
                            if (read < 0) break
                            written += read
                            check(written <= file.sizeBytes) { "oversized archive member: $name" }
                            output.write(buffer, 0, read)
                        }
                    }
                    verifyFile(file, dest)
                }
            }
        }
        check(found == archive.members.keys) { "model archive is missing required files" }
    }

    private suspend fun verifyFile(file: LocalModelFile, dest: File) {
        check(dest.length() == file.sizeBytes) { "size mismatch for ${file.destName}" }
        file.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            dest.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)) {
                "checksum mismatch for ${file.destName}"
            }
        }
    }

    private suspend fun downloadFile(
        file: LocalModelFile,
        dest: File,
        baseCompleted: Long,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val request = Request.Builder().url(file.url).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} downloading ${file.url}" }
            val body = response.body ?: error("empty response body for ${file.url}")
            val digest = if (file.sha256 != null) MessageDigest.getInstance("SHA-256") else null
            body.byteStream().use { input ->
                dest.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var fileDone = 0L
                    while (true) {
                        coroutineContext.ensureActive() // cancellation-aware
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        fileDone += read
                        onProgress(baseCompleted + fileDone, total)
                    }
                }
            }
            check(dest.length() == file.sizeBytes) {
                "size mismatch for ${file.destName}: expected ${file.sizeBytes}, got ${dest.length()}"
            }
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(file.sha256, ignoreCase = true)) {
                    "checksum mismatch for ${file.destName}"
                }
            }
        }
    }
}
