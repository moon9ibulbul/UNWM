package com.astral.unwm

import android.graphics.Bitmap
import kotlin.math.coerceAtLeast
import kotlin.math.roundToInt

object WatermarkRemover {
    /**
     * Port of the unwatermarking routine used in the original HTML tool
     * (see resources/watermark.js in the repository).
     * The core formula is: result = alphaImg * image + alphaWm * watermark,
     * where alphaImg = 255 / (255 - alpha) and alphaWm = -alpha / (255 - alpha).
     */
    fun removeWatermark(
        base: Bitmap,
        watermark: Bitmap,
        offsetX: Int,
        offsetY: Int,
        alphaAdjust: Float,
        transparencyThreshold: Int,
        opaqueThreshold: Int
    ): Bitmap {
        val safeAlphaAdjust = alphaAdjust.coerceAtLeast(0f)
        val baseBitmap = base.copy(Bitmap.Config.ARGB_8888, true)
        val watermarkBitmap = if (watermark.config != Bitmap.Config.ARGB_8888) {
            watermark.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            watermark
        }

        val basePixels = IntArray(baseBitmap.width * baseBitmap.height)
        baseBitmap.getPixels(basePixels, 0, baseBitmap.width, 0, 0, baseBitmap.width, baseBitmap.height)
        val resultPixels = basePixels.copyOf()

        val watermarkPixels = IntArray(watermarkBitmap.width * watermarkBitmap.height)
        watermarkBitmap.getPixels(
            watermarkPixels,
            0,
            watermarkBitmap.width,
            0,
            0,
            watermarkBitmap.width,
            watermarkBitmap.height
        )

        val baseStartX = offsetX.coerceAtLeast(0)
        val baseStartY = offsetY.coerceAtLeast(0)
        val wmStartX = (-offsetX).coerceAtLeast(0)
        val wmStartY = (-offsetY).coerceAtLeast(0)
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
                val denominator = (255 - adjustedAlpha).coerceAtLeast(1)
                val alphaImg = 255f / denominator
                val alphaWm = -adjustedAlpha / denominator.toFloat()
                var newR = (alphaImg * baseR + alphaWm * wmR).roundToInt().coerceIn(0, 255)
                var newG = (alphaImg * baseG + alphaWm * wmG).roundToInt().coerceIn(0, 255)
                var newB = (alphaImg * baseB + alphaWm * wmB).roundToInt().coerceIn(0, 255)
                if (adjustedAlpha > opaqueClamp && x > 0) {
                    val blendFactor = (adjustedAlpha - opaqueClamp)
                        .toFloat() / (255 - opaqueClamp).coerceAtLeast(1)
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

        baseBitmap.setPixels(resultPixels, 0, baseBitmap.width, 0, 0, baseBitmap.width, baseBitmap.height)
        return baseBitmap
    }
}
