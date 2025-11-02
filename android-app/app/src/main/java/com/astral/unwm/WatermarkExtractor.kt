package com.astral.unwm

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object WatermarkExtractor {
    fun extract(
        base: Bitmap,
        overlay: Bitmap,
        offsetX: Int,
        offsetY: Int,
        window: Rect,
        backgroundBase: Int,
        backgroundOverlay: Int
    ): Bitmap? {
        if (base.width == 0 || base.height == 0) return null
        val intersection = Rect(window)
        val baseBounds = Rect(0, 0, base.width, base.height)
        if (!intersection.intersect(baseBounds)) {
            return null
        }
        val overlayBounds = Rect(
            offsetX,
            offsetY,
            offsetX + overlay.width,
            offsetY + overlay.height
        )
        if (!intersection.intersect(overlayBounds)) {
            return null
        }
        val width = intersection.width()
        val height = intersection.height()
        if (width <= 0 || height <= 0) {
            return null
        }

        val overlayLeft = intersection.left - offsetX
        val overlayTop = intersection.top - offsetY
        if (overlayLeft < 0 || overlayTop < 0) {
            return null
        }

        val basePixels = IntArray(width * height)
        base.getPixels(basePixels, 0, width, intersection.left, intersection.top, width, height)
        val overlayPixels = IntArray(width * height)
        overlay.getPixels(overlayPixels, 0, width, overlayLeft, overlayTop, width, height)

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val resultPixels = IntArray(width * height)

        val bgBase = intArrayOf(
            Color.red(backgroundBase),
            Color.green(backgroundBase),
            Color.blue(backgroundBase)
        )
        val bgOverlay = intArrayOf(
            Color.red(backgroundOverlay),
            Color.green(backgroundOverlay),
            Color.blue(backgroundOverlay)
        )
        val bgAlphaFactor = norm(
            bgOverlay[0] - bgBase[0],
            bgOverlay[1] - bgBase[1],
            bgOverlay[2] - bgBase[2]
        ).takeIf { it > 0f } ?: return null

        for (index in basePixels.indices) {
            val baseColor = basePixels[index]
            val overlayColor = overlayPixels[index]
            val deltaNorm = norm(
                Color.red(overlayColor) - Color.red(baseColor),
                Color.green(overlayColor) - Color.green(baseColor),
                Color.blue(overlayColor) - Color.blue(baseColor)
            )
            val denominator = 1f - deltaNorm / bgAlphaFactor
            if (denominator <= 0f) {
                continue
            }
            val reconstructedAlpha = 1f / denominator
            if (!reconstructedAlpha.isFinite() || reconstructedAlpha <= 0f) {
                continue
            }
            val combinedR = Color.red(baseColor) + Color.red(overlayColor)
            val combinedG = Color.green(baseColor) + Color.green(overlayColor)
            val combinedB = Color.blue(baseColor) + Color.blue(overlayColor)
            val backgroundR = bgBase[0] + bgOverlay[0]
            val backgroundG = bgBase[1] + bgOverlay[1]
            val backgroundB = bgBase[2] + bgOverlay[2]
            val newR = (((combinedR * reconstructedAlpha) + (1 - reconstructedAlpha) * backgroundR) / 2f)
                .roundToInt().coerceIn(0, 255)
            val newG = (((combinedG * reconstructedAlpha) + (1 - reconstructedAlpha) * backgroundG) / 2f)
                .roundToInt().coerceIn(0, 255)
            val newB = (((combinedB * reconstructedAlpha) + (1 - reconstructedAlpha) * backgroundB) / 2f)
                .roundToInt().coerceIn(0, 255)
            val alpha = (255f / reconstructedAlpha).roundToInt().coerceIn(0, 255)
            resultPixels[index] = Color.argb(alpha, newR, newG, newB)
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    fun contrastStretch(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val sourcePixels = IntArray(width * height)
        bitmap.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        var minVal = Int.MAX_VALUE
        var maxVal = Int.MIN_VALUE
        sourcePixels.forEach { color ->
            val value = Color.red(color) + Color.green(color) + Color.blue(color)
            if (value < minVal) minVal = value
            if (value > maxVal) maxVal = value
        }
        val range = max(maxVal - minVal, 1)
        val ratio = (255f * 3f) / range.toFloat()

        val resultPixels = IntArray(sourcePixels.size)
        for (index in sourcePixels.indices) {
            val color = sourcePixels[index]
            val a = Color.alpha(color)
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val pixelValue = r + g + b
            if (pixelValue == 0) {
                resultPixels[index] = Color.argb(a, r, g, b)
                continue
            }
            val localRatio = ((pixelValue - minVal) * ratio) / pixelValue
            val newR = (r * localRatio).roundToInt().coerceIn(0, 255)
            val newG = (g * localRatio).roundToInt().coerceIn(0, 255)
            val newB = (b * localRatio).roundToInt().coerceIn(0, 255)
            resultPixels[index] = Color.argb(a, newR, newG, newB)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun norm(r: Int, g: Int, b: Int): Float {
        return (abs(r) + abs(g) + abs(b)) / 3f
    }
}

