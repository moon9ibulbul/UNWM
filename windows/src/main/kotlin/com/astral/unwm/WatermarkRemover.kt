package com.astral.unwm

import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object WatermarkRemover {
    /**
     * Port of the unwatermarking routine used in the original HTML tool
     * (see resources/watermark.js in the repository).
     * The core formula is: result = alphaImg * image + alphaWm * watermark,
     * where alphaImg = 255 / (255 - alpha) and alphaWm = -alpha / (255 - alpha).
     */
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

        // Clone base image
        val baseBitmap = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
        val g = baseBitmap.createGraphics()
        g.drawImage(base, 0, 0, null)
        g.dispose()

        val watermarkBitmap = if (watermark.type != BufferedImage.TYPE_INT_ARGB) {
            val newImg = BufferedImage(watermark.width, watermark.height, BufferedImage.TYPE_INT_ARGB)
            val gw = newImg.createGraphics()
            gw.drawImage(watermark, 0, 0, null)
            gw.dispose()
            newImg
        } else {
            watermark
        }

        val basePixels = ImageUtils.getPixels(baseBitmap)
        val resultPixels = basePixels // Direct access, modification in place is fine as we copied base

        // Need a copy of original base pixels if we were reading and writing to same array with dependencies,
        // but here we are blending base with watermark.
        // Actually, the loop modifies resultPixels which is basePixels.
        // Is it safe? Yes, we process pixel by pixel.
        // Wait, the blending logic for "opaqueClamp" uses `resultPixels[baseIndex - 1]`.
        // So we are using the *new* value of the left pixel.
        // This is sequential dependency, so modifying in place is actually DESIRED if the original code did so?
        // Let's check original code:
        // val resultPixels = basePixels.copyOf()
        // ...
        // resultPixels[baseIndex] = ...
        // val leftColor = resultPixels[baseIndex - 1]
        // So yes, it uses the MODIFIED left pixel.

        val watermarkPixels = ImageUtils.getPixels(watermarkBitmap)

        val baseStartX = max(offsetX, 0)
        val baseStartY = max(offsetY, 0)
        val wmStartX = max(-offsetX, 0)
        val wmStartY = max(-offsetY, 0)
        val overlapWidth = minOf(
            baseBitmap.width - baseStartX,
            watermarkBitmap.width - wmStartX
        )
        val overlapHeight = minOf(
            baseBitmap.height - baseStartY,
            watermarkBitmap.height - wmStartY
        )
        if (overlapWidth <= 0 || overlapHeight <= 0) {
            return baseBitmap
        }

        val transparencyClamp = transparencyThreshold.coerceIn(0, 255)
        val opaqueClamp = opaqueThreshold.coerceIn(0, 255)

        for (y in 0 until overlapHeight) {
            val baseRow = (baseStartY + y) * baseBitmap.width
            val wmRow = (wmStartY + y) * watermarkBitmap.width
            for (x in 0 until overlapWidth) {
                val baseIndex = baseRow + baseStartX + x
                val wmIndex = wmRow + wmStartX + x
                val wmColor = watermarkPixels[wmIndex]
                val wmAlpha = ((wmColor ushr 24) and 0xFF)
                val adjustedAlpha = (wmAlpha * safeAlphaAdjust)
                    .coerceIn(0f, 254f)
                    .roundToInt()
                if (adjustedAlpha <= transparencyClamp) {
                    continue
                }
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
                    val blendFactor = (adjustedAlpha - opaqueClamp)
                        .toFloat() / max(255 - opaqueClamp, 1)
                    val leftColor = resultPixels[baseIndex - 1]
                    val leftR = (leftColor shr 16) and 0xFF
                    val leftG = (leftColor shr 8) and 0xFF
                    val leftB = leftColor and 0xFF
                    newR = (blendFactor * leftR + (1 - blendFactor) * newR)
                        .roundToInt().coerceIn(0, 255)
                    newG = (blendFactor * leftG + (1 - blendFactor) * newG)
                        .roundToInt().coerceIn(0, 255)
                    newB = (blendFactor * leftB + (1 - blendFactor) * newB)
                        .roundToInt().coerceIn(0, 255)
                }
                resultPixels[baseIndex] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
            }
        }

        // Pixels are already updated in the raster's data buffer (if we used the direct buffer access trick)
        // If we didn't (e.g. copied in ImageUtils), we need to set them back.
        // My ImageUtils.getPixels returns direct reference to data buffer array.
        // So modifying 'resultPixels' modifies 'baseBitmap' content directly.

        return baseBitmap
    }
}
