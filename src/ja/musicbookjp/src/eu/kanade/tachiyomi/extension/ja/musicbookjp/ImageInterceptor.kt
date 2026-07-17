package eu.kanade.tachiyomi.extension.ja.musicbookjp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import keiyoushi.utils.parseAs
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer

class ImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val fragment = request.url.fragment

        if (fragment.isNullOrEmpty() || !response.isSuccessful) return response

        val data = fragment.parseAs<FragmentData>()
        val main = BitmapFactory.decodeStream(response.body.byteStream())
        val stem = request.url.toString().substringBeforeLast(".jpg")
        val b1 = chain.fetchBitmap(request, stem + "_b1.png")
        val b2 = chain.fetchBitmap(request, stem + "_b2.png")

        val result = unscramble(main, data.mapping, data.gridWidth, data.gridHeight, b1, b2)
        main.recycle()
        b1?.recycle()
        b2?.recycle()

        val buffer = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 90, buffer.outputStream())
        result.recycle()

        return response.newBuilder()
            .body(buffer.asResponseBody(MEDIA_TYPE, buffer.size))
            .build()
    }

    private fun Interceptor.Chain.fetchBitmap(template: Request, url: String): Bitmap? {
        val response = proceed(template.newBuilder().url(url).build())
        return response.use {
            if (it.isSuccessful) BitmapFactory.decodeStream(it.body.byteStream()) else null
        }
    }

    private fun unscramble(
        image: Bitmap,
        mapping: IntArray,
        gridWidth: Int,
        gridHeight: Int,
        b1: Bitmap?,
        b2: Bitmap?,
    ): Bitmap {
        val width = image.width
        val height = image.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val src = Rect()
        val dst = Rect()

        // eblieva mode 3
        val innerW = middleSize(width, gridWidth, 16)
        val innerH = middleSize(height, gridHeight, 16)
        val firstW = halfRound(width - innerW * (gridWidth - 2), 16)
        val firstH = halfRound(height - innerH * (gridHeight - 2), 16)

        val colX = IntArray(gridWidth)
        val colW = IntArray(gridWidth)
        for (k in 0 until gridWidth) {
            colX[k] = if (k == 0) 0 else colX[k - 1] + colW[k - 1]
            colW[k] = when {
                k == 0 -> firstW
                k < gridWidth - 1 -> innerW
                else -> width - colX[k]
            }
        }
        val rowY = IntArray(gridHeight)
        val rowH = IntArray(gridHeight)
        for (k in 0 until gridHeight) {
            rowY[k] = if (k == 0) 0 else rowY[k - 1] + rowH[k - 1]
            rowH[k] = when {
                k == 0 -> firstH
                k < gridHeight - 1 -> innerH
                else -> height - rowY[k]
            }
        }

        for (destIndex in mapping.indices) {
            val sourceIndex = mapping[destIndex]
            val dc = destIndex % gridWidth
            val dr = destIndex / gridWidth
            val sc = sourceIndex % gridWidth
            val sr = sourceIndex / gridWidth
            src.set(colX[sc], rowY[sr], colX[sc] + colW[sc], rowY[sr] + rowH[sr])
            dst.set(colX[dc], rowY[dr], colX[dc] + colW[dc], rowY[dr] + rowH[dr])
            canvas.drawBitmap(image, src, dst, null)
        }

        val g = if (b1 != null) b1.height / (2 * gridHeight - 2) else 0

        if (b1 != null) {
            for (t in 1 until gridHeight) {
                src.set(0, (2 * t - 2) * g, width, (2 * t - 2) * g + 2 * g)
                dst.set(0, rowY[t] - g, width, rowY[t] - g + 2 * g)
                canvas.drawBitmap(b1, src, dst, null)
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
                    src.set((2 * col - 2) * o, sourceY, (2 * col - 2) * o + 2 * o, sourceY + band)
                    dst.set(colX[col] - o, destY, colX[col] - o + 2 * o, destY + band)
                    canvas.drawBitmap(b2, src, dst, null)
                }
            }
        }

        return result
    }

    // eblieva lL
    private fun roundToMultiple(t: Int, i: Int): Int {
        val s = t / i
        return when {
            s == 0 -> i
            t % i > i / 2 -> (s + 1) * i
            else -> s * i
        }
    }

    // eblieva hL
    private fun halfRound(t: Int, i: Int) = roundToMultiple(t / 2, i)

    // eblieva rL
    private fun middleSize(t: Int, i: Int, s: Int): Int {
        val e = (t - s - 1) / ((i - 2) * s) * s
        val n = roundToMultiple(t * (i - 2) / i, s * (i - 2)) / (i - 2)
        return minOf(n, e)
    }

    companion object {
        private val MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}
