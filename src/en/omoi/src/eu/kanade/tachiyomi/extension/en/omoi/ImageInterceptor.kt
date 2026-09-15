package eu.kanade.tachiyomi.extension.en.omoi

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import kotlin.experimental.xor

class ImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.url.fragment != "drm" || !response.isSuccessful) return response

        val responseBody = response.body
        // https://www.omoi.com/assets/js/DecryptedImage.561cb118.js
        val decrypted = object : ForwardingSource(responseBody.source()) {
            private val cursor = Buffer.UnsafeCursor()

            override fun read(sink: Buffer, byteCount: Long): Long {
                val start = sink.size
                val read = super.read(sink, byteCount)
                if (read == -1L) return -1L

                sink.readAndWriteUnsafe(cursor).use {
                    var length = it.seek(start)
                    while (length != -1) {
                        val data = it.data!!
                        for (i in it.start until it.end) {
                            data[i] = data[i] xor KEY
                        }
                        length = it.next()
                    }
                }
                return read
            }
        }

        val body = decrypted.buffer().asResponseBody(responseBody.contentType(), responseBody.contentLength())
        return response.newBuilder()
            .body(body)
            .build()
    }

    companion object {
        private const val KEY = 174.toByte()
    }
}
