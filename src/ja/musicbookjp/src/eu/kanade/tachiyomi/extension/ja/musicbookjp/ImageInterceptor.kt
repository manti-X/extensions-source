package eu.kanade.tachiyomi.extension.ja.musicbookjp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import keiyoushi.network.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer

class ImageInterceptor(private val client: () -> OkHttpClient) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fragment = request.url.fragment

        if (fragment.isNullOrEmpty()) return chain.proceed(request)

        val stem = request.url.toString().substringBeforeLast(".jpg")
        return runBlocking {
            supervisorScope {
                val b1Deferred = async(Dispatchers.IO) { fetchBitmap(request, "${stem}_b1.png") }
                val b2Deferred = async(Dispatchers.IO) { fetchBitmap(request, "${stem}_b2.png") }

                val response = chain.proceed(request)
                if (!response.isSuccessful) {
                    coroutineContext.cancelChildren()
                    return@supervisorScope response
                }

                val bitmap = BitmapFactory.decodeStream(response.body.byteStream())
                val b1 = b1Deferred.await()
                val b2 = b2Deferred.await()
                val result = unscramble(bitmap, Scramble(fragment), b1, b2)

                bitmap.recycle()
                b1?.recycle()
                b2?.recycle()
                val buffer = Buffer()
                result.compress(Bitmap.CompressFormat.JPEG, 90, buffer.outputStream())
                result.recycle()
                val body = buffer.asResponseBody(MEDIA_TYPE, buffer.size)

                response.newBuilder()
                    .body(body)
                    .build()
            }
        }
    }

    private suspend fun fetchBitmap(template: Request, url: String): Bitmap? = client().get(url, template.headers, ensureSuccess = false).use {
        if (it.isSuccessful) BitmapFactory.decodeStream(it.body.byteStream()) else null
    }

    private class Scramble(coordinates: String) {
        val gridWidth: Int
        val gridHeight: Int
        val mapping: IntArray

        init {
            val nums = coordinates.split(",").map(String::toInt)
            val seq = IntArray(nums.size)
            for (i in nums.indices) {
                seq[i] = if (i < 2) nums[i] else nums[i] + seq[i - 1] - seq[i - 2]
            }

            gridWidth = seq[seq.lastIndex - 1]
            gridHeight = seq[seq.lastIndex]
            mapping = IntArray(gridWidth * gridHeight)
            var t = 1
            for (k in mapping.indices) {
                if (t >= mapping.size) t = 0
                mapping[seq[k] - 1] = t
                t += 2
            }
        }
    }

    private fun unscramble(
        image: Bitmap,
        scramble: Scramble,
        b1: Bitmap?,
        b2: Bitmap?,
    ): Bitmap {
        val width = image.width
        val height = image.height
        val gridWidth = scramble.gridWidth
        val gridHeight = scramble.gridHeight

        // eblieva mode 3
        val colX = IntArray(gridWidth)
        val colW = IntArray(gridWidth)
        cellBounds(width, gridWidth, colX, colW)
        val rowY = IntArray(gridHeight)
        val rowH = IntArray(gridHeight)
        cellBounds(height, gridHeight, rowY, rowH)

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val srcRect = Rect()
        val dstRect = Rect()

        for ((destIndex, sourceIndex) in scramble.mapping.withIndex()) {
            val dc = destIndex % gridWidth
            val dr = destIndex / gridWidth
            val sc = sourceIndex % gridWidth
            val sr = sourceIndex / gridWidth
            srcRect.set(colX[sc], rowY[sr], colX[sc] + colW[sc], rowY[sr] + rowH[sr])
            dstRect.set(colX[dc], rowY[dr], colX[dc] + colW[dc], rowY[dr] + rowH[dr])
            canvas.drawBitmap(image, srcRect, dstRect, null)
        }

        // b1/b2 hold the horizontal/vertical seams between cells
        val g = if (b1 != null) b1.height / (2 * gridHeight - 2) else 0
        if (b1 != null) {
            for (row in 1 until gridHeight) {
                srcRect.set(0, (2 * row - 2) * g, width, 2 * row * g)
                dstRect.set(0, rowY[row] - g, width, rowY[row] + g)
                canvas.drawBitmap(b1, srcRect, dstRect, null)
            }
        }

        if (b2 != null) {
            val o = b2.width / (2 * gridWidth - 2)
            var sourceY = 0
            var band = 0
            for (row in 0 until gridHeight) {
                sourceY += band
                val destY = if (row == 0) 0 else rowY[row] + g
                band = rowH[row] - (if (row == 0 || row == gridHeight - 1) 1 else 2) * g
                for (col in 1 until gridWidth) {
                    srcRect.set((2 * col - 2) * o, sourceY, 2 * col * o, sourceY + band)
                    dstRect.set(colX[col] - o, destY, colX[col] + o, destY + band)
                    canvas.drawBitmap(b2, srcRect, dstRect, null)
                }
            }
        }

        return result
    }

    private fun cellBounds(size: Int, count: Int, offsets: IntArray, lengths: IntArray) {
        val inner = innerCellSize(size, count)
        val first = roundToMultiple((size - inner * (count - 2)) / 2, CELL_UNIT)
        for (k in 0 until count) {
            offsets[k] = if (k == 0) 0 else offsets[k - 1] + lengths[k - 1]
            lengths[k] = when (k) {
                0 -> first
                count - 1 -> size - offsets[k]
                else -> inner
            }
        }
    }

    // eblieva lL
    private fun roundToMultiple(value: Int, multiple: Int): Int {
        val quotient = value / multiple
        return when {
            quotient == 0 -> multiple
            value % multiple > multiple / 2 -> (quotient + 1) * multiple
            else -> quotient * multiple
        }
    }

    // eblieva rL
    private fun innerCellSize(size: Int, count: Int): Int {
        val inner = count - 2
        val maxSize = (size - CELL_UNIT - 1) / (inner * CELL_UNIT) * CELL_UNIT
        val rounded = roundToMultiple(size * inner / count, CELL_UNIT * inner) / inner
        return minOf(rounded, maxSize)
    }

    companion object {
        private const val CELL_UNIT = 16
        private val MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}
