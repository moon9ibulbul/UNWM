package com.astral.unwm

import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object WatermarkExtractor {
    fun extract(
        base: BufferedImage,
        overlay: BufferedImage,
        offsetX: Int,
        offsetY: Int,
        window: Rectangle,
        backgroundBase: Int,
        backgroundOverlay: Int
    ): BufferedImage? {
        if (base.width == 0 || base.height == 0) return null
        val intersection = Rectangle(window)
        val baseBounds = Rectangle(0, 0, base.width, base.height)
        val intersectWithBase = intersection.intersection(baseBounds)
        if (intersectWithBase.isEmpty) {
            return null
        }
        val overlayBounds = Rectangle(
            offsetX,
            offsetY,
            overlay.width,
            overlay.height
        )
        val finalIntersection = intersectWithBase.intersection(overlayBounds)
        if (finalIntersection.isEmpty) {
            return null
        }
        val width = finalIntersection.width
        val height = finalIntersection.height
        if (width <= 0 || height <= 0) {
            return null
        }

        val overlayLeft = finalIntersection.x - offsetX
        val overlayTop = finalIntersection.y - offsetY
        if (overlayLeft < 0 || overlayTop < 0) {
            return null
        }

        // We use full image access or region access
        val basePixels = IntArray(width * height)
        base.getRGB(finalIntersection.x, finalIntersection.y, width, height, basePixels, 0, width)

        val overlayPixels = IntArray(width * height)
        overlay.getRGB(overlayLeft, overlayTop, width, height, overlayPixels, 0, width)

        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val resultPixels = IntArray(width * height)

        val bgBase = intArrayOf(
            (backgroundBase shr 16) and 0xFF,
            (backgroundBase shr 8) and 0xFF,
            backgroundBase and 0xFF
        )
        val bgOverlay = intArrayOf(
            (backgroundOverlay shr 16) and 0xFF,
            (backgroundOverlay shr 8) and 0xFF,
            backgroundOverlay and 0xFF
        )
        val bgAlphaFactor = norm(
            bgOverlay[0] - bgBase[0],
            bgOverlay[1] - bgBase[1],
            bgOverlay[2] - bgBase[2]
        ).takeIf { it > 0f } ?: return null

        for (index in basePixels.indices) {
            val baseColor = basePixels[index]
            val overlayColor = overlayPixels[index]
            val baseR = (baseColor shr 16) and 0xFF
            val baseG = (baseColor shr 8) and 0xFF
            val baseB = baseColor and 0xFF
            val overlayR = (overlayColor shr 16) and 0xFF
            val overlayG = (overlayColor shr 8) and 0xFF
            val overlayB = overlayColor and 0xFF

            val deltaNorm = norm(
                overlayR - baseR,
                overlayG - baseG,
                overlayB - baseB
            )
            val denominator = 1f - deltaNorm / bgAlphaFactor
            if (denominator <= 0f) {
                continue
            }
            val reconstructedAlpha = 1f / denominator
            if (!reconstructedAlpha.isFinite() || reconstructedAlpha <= 0f) {
                continue
            }
            val combinedR = baseR + overlayR
            val combinedG = baseG + overlayG
            val combinedB = baseB + overlayB
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
            resultPixels[index] = (alpha shl 24) or (newR shl 16) or (newG shl 8) or newB
        }

        result.setRGB(0, 0, width, height, resultPixels, 0, width)
        return result
    }

    fun contrastStretch(bitmap: BufferedImage): BufferedImage {
        val width = bitmap.width
        val height = bitmap.height
        val sourcePixels = IntArray(width * height)
        bitmap.getRGB(0, 0, width, height, sourcePixels, 0, width)

        var minVal = Int.MAX_VALUE
        var maxVal = Int.MIN_VALUE
        sourcePixels.forEach { color ->
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val value = r + g + b
            if (value < minVal) minVal = value
            if (value > maxVal) maxVal = value
        }
        val range = max(maxVal - minVal, 1)
        val ratio = (255f * 3f) / range.toFloat()

        val resultPixels = IntArray(sourcePixels.size)
        for (index in sourcePixels.indices) {
            val color = sourcePixels[index]
            val a = (color shr 24) and 0xFF
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
            resultPixels[index] = (a shl 24) or (newR shl 16) or (newG shl 8) or newB
        }

        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        result.setRGB(0, 0, width, height, resultPixels, 0, width)
        return result
    }

    private fun norm(r: Int, g: Int, b: Int): Float {
        return (abs(r) + abs(g) + abs(b)) / 3f
    }
}
