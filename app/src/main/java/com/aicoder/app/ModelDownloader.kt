package com.aicoder.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles the one-time download of the on-device model into app-private
 * storage. After this runs once, the app never needs the network again.
 */
object ModelDownloader {

    const val MODEL_FILENAME = "qwen2.5-1.5b-instruct-q8.task"
    const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

    fun modelFile(context: Context): File =
        File(context.filesDir, MODEL_FILENAME)

    fun isDownloaded(context: Context): Boolean =
        modelFile(context).exists() && modelFile(context).length() > 0

    fun download(context: Context): Flow<Int> = flow {
        val dest = modelFile(context)
        val tmp = File(context.filesDir, "$MODEL_FILENAME.part")

        val url = URL(MODEL_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connect()

        val total = conn.contentLengthLong
        var downloaded = 0L

        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var read: Int
                var lastEmitted = -1
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total > 0) {
                        val pct = ((downloaded * 100) / total).toInt()
                        if (pct != lastEmitted) {
                            lastEmitted = pct
                            emit(pct)
                        }
                    }
                }
            }
        }

        tmp.renameTo(dest)
        emit(100)
    }.flowOn(Dispatchers.IO)
}
