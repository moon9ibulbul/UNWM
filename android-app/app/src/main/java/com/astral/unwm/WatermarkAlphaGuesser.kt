package com.astral.unwm

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object WatermarkAlphaGuesser {
    fun guessAlpha(
        base: Bitmap,
        watermark: Bitmap,
        offsetX: Int,
        offsetY: Int,
        transparencyThreshold: Int,
        opaqueThreshold: Int
    ): Float? {
        val baseBitmap = if (base.config != Bitmap.Config.ARGB_8888) {
            base.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            base
        }
        val watermarkBitmap = if (watermark.config != Bitmap.Config.ARGB_8888) {
            watermark.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            watermark
        }

        val baseStartX = max(offsetX, 0)
        val baseStartY = max(offsetY, 0)
        val wmStartX = max(-offsetX, 0)
        val wmStartY = max(-offsetY, 0)
        val overlapWidth = min(
            baseBitmap.width - baseStartX,
            watermarkBitmap.width - wmStartX
        )
        val overlapHeight = min(
            baseBitmap.height - baseStartY,
            watermarkBitmap.height - wmStartY
        )
        if (overlapWidth <= 0 || overlapHeight <= 0) {
            return null
        }

        val transparencyClamp = transparencyThreshold.coerceIn(0, 255)
        val opaqueClamp = opaqueThreshold.coerceIn(0, 255)

        val pixelCount = overlapWidth * overlapHeight
        val basePixels = IntArray(pixelCount)
        val watermarkPixels = IntArray(pixelCount)
        baseBitmap.getPixels(
            basePixels,
            0,
            overlapWidth,
            baseStartX,
            baseStartY,
            overlapWidth,
            overlapHeight
        )
        watermarkBitmap.getPixels(
            watermarkPixels,
            0,
            overlapWidth,
            wmStartX,
            wmStartY,
            overlapWidth,
            overlapHeight
        )

        val greyWatermark = IntArray(pixelCount)
        val alphaMask = IntArray(pixelCount)
        for (index in 0 until pixelCount) {
            val wmColor = watermarkPixels[index]
            val wmAlpha = (wmColor ushr 24) and 0xFF
            alphaMask[index] = wmAlpha
            val alphaFactor = wmAlpha / 255f
            val wmR = (wmColor shr 16) and 0xFF
            val wmG = (wmColor shr 8) and 0xFF
            val wmB = wmColor and 0xFF
            val baseGrey = 0x80
            val outR = ((1 - alphaFactor) * baseGrey + alphaFactor * wmR).roundToInt().coerceIn(0, 255)
            val outG = ((1 - alphaFactor) * baseGrey + alphaFactor * wmG).roundToInt().coerceIn(0, 255)
            val outB = ((1 - alphaFactor) * baseGrey + alphaFactor * wmB).roundToInt().coerceIn(0, 255)
            greyWatermark[index] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
        }

        val watermarkEdges = computeEdges(greyWatermark, overlapWidth, overlapHeight)
        val watermarkEdgeStats = EdgeStats.from(watermarkEdges)
        if (watermarkEdgeStats.energy <= 0f) {
            return null
        }

        val resultPixels = IntArray(pixelCount)
        val alphaIndices = alphaMask.count { it > 10 }
        if (alphaIndices == 0) {
            return null
        }

        var bestAlpha: Float? = null
        var bestScore = Float.POSITIVE_INFINITY

        for (step in 50..119) {
            val alphaAdjust = step / 100f
            var heavyClipping = false
            var clippedChannels = 0
            var processedAlphaPixels = 0
            for (y in 0 until overlapHeight) {
                val row = y * overlapWidth
                for (x in 0 until overlapWidth) {
                    val index = row + x
                    val baseColor = basePixels[index]
                    val wmColor = watermarkPixels[index]
                    val wmAlpha = alphaMask[index]
                    val adjustedAlpha = wmAlpha * alphaAdjust
                    if (adjustedAlpha >= 255f) {
                        heavyClipping = true
                        break
                    }
                    if (adjustedAlpha <= transparencyClamp) {
                        resultPixels[index] = baseColor
                        continue
                    }
                    if (wmAlpha <= 10) {
                        resultPixels[index] = baseColor
                        continue
                    }
                    processedAlphaPixels++
                    val baseR = (baseColor shr 16) and 0xFF
                    val baseG = (baseColor shr 8) and 0xFF
                    val baseB = baseColor and 0xFF
                    val wmR = (wmColor shr 16) and 0xFF
                    val wmG = (wmColor shr 8) and 0xFF
                    val wmB = wmColor and 0xFF
                    val denominator = max(255 - adjustedAlpha, 1f)
                    val alphaImg = 255f / denominator
                    val alphaWm = -adjustedAlpha / denominator
                    var newR = (alphaImg * baseR + alphaWm * wmR).roundToInt().coerceIn(0, 255)
                    var newG = (alphaImg * baseG + alphaWm * wmG).roundToInt().coerceIn(0, 255)
                    var newB = (alphaImg * baseB + alphaWm * wmB).roundToInt().coerceIn(0, 255)
                    if (adjustedAlpha > opaqueClamp && x > 0) {
                        val blendFactor = (adjustedAlpha - opaqueClamp).toFloat() / max(255 - opaqueClamp, 1)
                        val leftColor = resultPixels[index - 1]
                        val leftR = (leftColor shr 16) and 0xFF
                        val leftG = (leftColor shr 8) and 0xFF
                        val leftB = leftColor and 0xFF
                        newR = (blendFactor * leftR + (1 - blendFactor) * newR).roundToInt().coerceIn(0, 255)
                        newG = (blendFactor * leftG + (1 - blendFactor) * newG).roundToInt().coerceIn(0, 255)
                        newB = (blendFactor * leftB + (1 - blendFactor) * newB).roundToInt().coerceIn(0, 255)
                    }
                    if (newR == 0 || newR == 255) clippedChannels++
                    if (newG == 0 || newG == 255) clippedChannels++
                    if (newB == 0 || newB == 255) clippedChannels++
                    resultPixels[index] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
                }
                if (heavyClipping) break
            }

            if (heavyClipping || processedAlphaPixels == 0) {
                continue
            }

            val resultEdges = computeEdges(resultPixels, overlapWidth, overlapHeight)
            val resultStats = EdgeStats.from(resultEdges)
            if (resultStats.energy <= 0f) {
                continue
            }
            val similarity = correlation(resultEdges, resultStats, watermarkEdges, watermarkEdgeStats)
            val clippingPenalty = clippedChannels.toFloat() / (processedAlphaPixels * 3f)
            val score = abs(similarity) + clippingPenalty * 0.25f
            if (score < bestScore) {
                bestScore = score
                bestAlpha = alphaAdjust
            }
        }

        return bestAlpha
    }

    private fun computeEdges(pixels: IntArray, width: Int, height: Int): FloatArray {
        val edges = FloatArray(pixels.size)
        fun luminance(color: Int): Float {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return 0.299f * r + 0.587f * g + 0.114f * b
        }
        for (y in 0 until height) {
            val yUp = max(y - 1, 0)
            val yDown = min(y + 1, height - 1)
            for (x in 0 until width) {
                val xLeft = max(x - 1, 0)
                val xRight = min(x + 1, width - 1)
                val center = luminance(pixels[y * width + x])
                val left = luminance(pixels[y * width + xLeft])
                val right = luminance(pixels[y * width + xRight])
                val up = luminance(pixels[yUp * width + x])
                val down = luminance(pixels[yDown * width + x])
                val dx = right - left
                val dy = down - up
                edges[y * width + x] = sqrt(dx * dx + dy * dy)
            }
        }
        return edges
    }

    private fun correlation(
        values: FloatArray,
        valueStats: EdgeStats,
        reference: FloatArray,
        referenceStats: EdgeStats
    ): Float {
        var numerator = 0f
        var energy = 0f
        for (i in values.indices) {
            val dv = values[i] - valueStats.mean
            val dr = reference[i] - referenceStats.mean
            numerator += dv * dr
            energy += dv * dv
        }
        val denom = sqrt(energy * referenceStats.energy)
        return if (denom <= 1e-6f) 0f else numerator / denom
    }

    private data class EdgeStats(val mean: Float, val energy: Float) {
        companion object {
            fun from(values: FloatArray): EdgeStats {
                var sum = 0f
                for (value in values) {
                    sum += value
                }
                val mean = sum / values.size.coerceAtLeast(1)
                var energy = 0f
                for (value in values) {
                    val diff = value - mean
                    energy += diff * diff
                }
                return EdgeStats(mean, energy)
            }
        }
    }
}
