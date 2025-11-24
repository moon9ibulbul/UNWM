package com.astral.unwm.desktop

import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.roundToInt

object DesktopWatermarkRemover {
    fun removeWatermark(
        base: BufferedImage,
        watermark: BufferedImage,
        offsetX: Int,
        offsetY: Int,
        alphaAdjust: Float,
        transparencyThreshold: Int,
        opaqueThreshold: Int
    ): BufferedImage {
        val safeAlphaAdjust = max(alphaAdjust, 0f)
        val basePixels = base.copyAsArgb().toArgbArray()
        val watermarkPixels = watermark.copyAsArgb().toArgbArray()

        val baseWidth = base.width
        val baseHeight = base.height
        val wmWidth = watermark.width
        val wmHeight = watermark.height

        val baseStartX = max(offsetX, 0)
        val baseStartY = max(offsetY, 0)
        val wmStartX = max(-offsetX, 0)
        val wmStartY = max(-offsetY, 0)
        val overlapWidth = minOf(baseWidth - baseStartX, wmWidth - wmStartX)
        val overlapHeight = minOf(baseHeight - baseStartY, wmHeight - wmStartY)
        if (overlapWidth <= 0 || overlapHeight <= 0) {
            return base.copyAsArgb()
        }

        val transparencyClamp = transparencyThreshold.coerceIn(0, 255)
        val opaqueClamp = opaqueThreshold.coerceIn(0, 255)
        val resultPixels = basePixels.copyOf()

        for (y in 0 until overlapHeight) {
            val baseRow = (baseStartY + y) * baseWidth
            val wmRow = (wmStartY + y) * wmWidth
            for (x in 0 until overlapWidth) {
                val baseIndex = baseRow + baseStartX + x
                val wmIndex = wmRow + wmStartX + x
                val wmColor = watermarkPixels[wmIndex]
                val wmAlpha = (wmColor ushr 24) and 0xFF
                val adjustedAlpha = (wmAlpha * safeAlphaAdjust).coerceIn(0f, 254f).roundToInt()
                if (adjustedAlpha <= transparencyClamp) continue
                val baseColor = basePixels[baseIndex]
                val baseR = (baseColor shr 16) and 0xFF
                val baseG = (baseColor shr 8) and 0xFF
                val baseB = baseColor and 0xFF
                val wmR = (wmColor shr 16) and 0xFF
                val wmG = (wmColor shr 8) and 0xFF
                val wmB = wmColor and 0xFF
                val denominator = max(255 - adjustedAlpha, 1)
                val alphaImg = 255f / denominator
                val alphaWm = -adjustedAlpha / denominator.toFloat()
                var newR = (alphaImg * baseR + alphaWm * wmR).roundToInt().coerceIn(0, 255)
                var newG = (alphaImg * baseG + alphaWm * wmG).roundToInt().coerceIn(0, 255)
                var newB = (alphaImg * baseB + alphaWm * wmB).roundToInt().coerceIn(0, 255)
                if (adjustedAlpha > opaqueClamp && x > 0) {
                    val blendFactor = (adjustedAlpha - opaqueClamp).toFloat() / max(255 - opaqueClamp, 1)
                    val leftColor = resultPixels[baseIndex - 1]
                    val leftR = (leftColor shr 16) and 0xFF
                    val leftG = (leftColor shr 8) and 0xFF
                    val leftB = leftColor and 0xFF
                    newR = (blendFactor * leftR + (1 - blendFactor) * newR).roundToInt().coerceIn(0, 255)
                    newG = (blendFactor * leftG + (1 - blendFactor) * newG).roundToInt().coerceIn(0, 255)
                    newB = (blendFactor * leftB + (1 - blendFactor) * newB).roundToInt().coerceIn(0, 255)
                }
                resultPixels[baseIndex] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
            }
        }
        return resultPixels.toBufferedImage(baseWidth, baseHeight)
    }
}
