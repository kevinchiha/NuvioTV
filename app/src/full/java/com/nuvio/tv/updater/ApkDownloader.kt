package com.nuvio.tv.updater

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun download(
        url: String,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Result<File> {
        return runCatching {
            destinationFile.parentFile?.mkdirs()

            // Stream into a .part temp, then atomically rename, so a partial/concurrent write is
            // never visible at the final path (and the worker's prune skips .part files).
            val partFile = File(destinationFile.parentFile, destinationFile.name + ".part")
            if (partFile.exists()) partFile.delete()

            val request = Request.Builder()
                .url(url)
                .build()

            // KevBox: APKs are 80–150 MB. The shared client has a 30s read timeout (fine for API
            // calls); relax read/write/call timeouts to unlimited for the large streamed download
            // so a slow family TV link doesn't abort it. connectTimeout stays inherited (fail fast
            // on a dead host).
            val downloadClient = okHttpClient.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download failed: HTTP ${response.code}")
                }

                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        output.flush()
                    }
                }
            }

            if (destinationFile.exists()) destinationFile.delete()
            if (!partFile.renameTo(destinationFile)) {
                // Cross-device or rename refusal: fall back to copy+delete.
                partFile.copyTo(destinationFile, overwrite = true)
                partFile.delete()
            }

            destinationFile
        }
    }
}
