package com.astral.unwm.desktop

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object DesktopWatermarkExtractor {
    fun extract(
        base: java.awt.image.BufferedImage,
        overlay: java.awt.image.BufferedImage,
        offsetX: Int,
        offsetY: Int,
        windowLeft: Int,
        windowTop: Int,
        windowWidth: Int,
        windowHeight: Int,
        backgroundBase: IntArray,
        backgroundOverlay: IntArray
    ): java.awt.image.BufferedImage? {
        if (base.width == 0 || base.height == 0) return null
        val baseBounds = java.awt.Rectangle(0, 0, base.width, base.height)
        val intersection = java.awt.Rectangle(windowLeft, windowTop, windowWidth, windowHeight).intersection(baseBounds)
        val overlayBounds = java.awt.Rectangle(offsetX, offsetY, overlay.width, overlay.height)
        val intersected = intersection.intersection(overlayBounds)
        if (intersected.width <= 0 || intersected.height <= 0) return null

        val overlayLeft = intersected.x - offsetX
        val overlayTop = intersected.y - offsetY
        if (overlayLeft < 0 || overlayTop < 0) return null

        val width = intersected.width
        val height = intersected.height
        val basePixels = base.copyAsArgb().toArgbArray()
        val overlayPixels = overlay.copyAsArgb().toArgbArray()
        val resultPixels = IntArray(width * height)

        val bgBase = intArrayOf(backgroundBase.getOrElse(0) { 0 }, backgroundBase.getOrElse(1) { 0 }, backgroundBase.getOrElse(2) { 0 })
        val bgOverlay = intArrayOf(backgroundOverlay.getOrElse(0) { 0 }, backgroundOverlay.getOrElse(1) { 0 }, backgroundOverlay.getOrElse(2) { 0 })
        val bgAlphaFactor = norm(bgOverlay[0] - bgBase[0], bgOverlay[1] - bgBase[1], bgOverlay[2] - bgBase[2]).takeIf { it > 0f }
            ?: return null

        for (y in 0 until height) {
            for (x in 0 until width) {
                val baseIndex = (intersected.y + y) * base.width + intersected.x + x
                val overlayIndex = (overlayTop + y) * overlay.width + overlayLeft + x
                val baseColor = basePixels[baseIndex]
                val overlayColor = overlayPixels[overlayIndex]
                val deltaNorm = norm(
                    (overlayColor shr 16 and 0xFF) - (baseColor shr 16 and 0xFF),
                    (overlayColor shr 8 and 0xFF) - (baseColor shr 8 and 0xFF),
                    (overlayColor and 0xFF) - (baseColor and 0xFF)
                )
                val denominator = 1f - deltaNorm / bgAlphaFactor
                if (denominator <= 0f) continue
                val reconstructedAlpha = 1f / denominator
                if (!reconstructedAlpha.isFinite() || reconstructedAlpha <= 0f) continue
                val combinedR = (baseColor shr 16 and 0xFF) + (overlayColor shr 16 and 0xFF)
                val combinedG = (baseColor shr 8 and 0xFF) + (overlayColor shr 8 and 0xFF)
                val combinedB = (baseColor and 0xFF) + (overlayColor and 0xFF)
                val backgroundR = bgBase[0] + bgOverlay[0]
                val backgroundG = bgBase[1] + bgOverlay[1]
                val backgroundB = bgBase[2] + bgOverlay[2]
                val newR = (((combinedR * reconstructedAlpha) + (1 - reconstructedAlpha) * backgroundR) / 2f).roundToInt().coerceIn(0, 255)
                val newG = (((combinedG * reconstructedAlpha) + (1 - reconstructedAlpha) * backgroundG) / 2f).roundToInt().coerceIn(0, 255)
                val newB = (((combinedB * reconstructedAlpha) + (1 - reconstructedAlpha) * backgroundB) / 2f).roundToInt().coerceIn(0, 255)
                val alpha = (255f / reconstructedAlpha).roundToInt().coerceIn(0, 255)
                resultPixels[y * width + x] = rgba(newR, newG, newB, alpha)
            }
        }
        return resultPixels.toBufferedImage(width, height)
    }

    fun contrastStretch(bitmap: java.awt.image.BufferedImage): java.awt.image.BufferedImage {
        val width = bitmap.width
        val height = bitmap.height
        val sourcePixels = bitmap.copyAsArgb().toArgbArray()

        var minVal = Int.MAX_VALUE
        var maxVal = Int.MIN_VALUE
        sourcePixels.forEach { color ->
            val value = (color shr 16 and 0xFF) + (color shr 8 and 0xFF) + (color and 0xFF)
            if (value < minVal) minVal = value
            if (value > maxVal) maxVal = value
        }
        val range = max(maxVal - minVal, 1)
        val ratio = (255f * 3f) / range.toFloat()

        val resultPixels = IntArray(sourcePixels.size)
        for (index in sourcePixels.indices) {
            val color = sourcePixels[index]
            val a = (color ushr 24) and 0xFF
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val pixelValue = r + g + b
            if (pixelValue == 0) {
                resultPixels[index] = color
                continue
            }
            val localRatio = ((pixelValue - minVal) * ratio) / pixelValue
            val newR = (r * localRatio).roundToInt().coerceIn(0, 255)
            val newG = (g * localRatio).roundToInt().coerceIn(0, 255)
            val newB = (b * localRatio).roundToInt().coerceIn(0, 255)
            resultPixels[index] = rgba(newR, newG, newB, a)
        }
        return resultPixels.toBufferedImage(width, height)
    }

    private fun norm(r: Int, g: Int, b: Int): Float {
        return (abs(r) + abs(g) + abs(b)) / 3f
    }
}
