package com.astral.unwm

import android.graphics.Bitmap
import android.graphics.Color
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val DEFAULT_MATCH_THRESHOLD = 0.9
private const val DEFAULT_ALPHA_THRESHOLD = 5.0

/**
 * Detects watermark positions using OpenCV's template matching with masking.
 */
object WatermarkDetector {
    fun detect(
        base: Bitmap,
        watermark: Bitmap,
        maxResults: Int = Int.MAX_VALUE,
        matchThreshold: Double = DEFAULT_MATCH_THRESHOLD,
        alphaThreshold: Double = DEFAULT_ALPHA_THRESHOLD
    ): List<WatermarkDetection> {
        if (base.width < watermark.width || base.height < watermark.height) {
            return emptyList()
        }

        val workingBase = ensureArgb(base)
        val workingWatermark = ensureArgb(watermark)

        val baseMat = Mat()
        val watermarkMat = Mat()
        val baseGray = Mat()
        val watermarkGray = Mat()
        val baseBgr = Mat()
        val watermarkBgr = Mat()
        val alphaChannel = Mat()
        val mask = Mat()
        val nonZero = Mat()
        val baseEdges = Mat()
        var watermarkGrayRoi = Mat()
        var watermarkMaskRoi = Mat()
        var watermarkBgrRoi = Mat()
        var watermarkAlphaRoi = Mat()
        var resultGray = Mat()
        var colorAccumulation = Mat()
        var resultEdges = Mat()
        var combinedResult = Mat()

        return try {
            Utils.bitmapToMat(workingBase, baseMat)
            Utils.bitmapToMat(workingWatermark, watermarkMat)

            Imgproc.cvtColor(baseMat, baseGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(watermarkMat, watermarkGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(baseMat, baseBgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(watermarkMat, watermarkBgr, Imgproc.COLOR_RGBA2BGR)
            Core.extractChannel(watermarkMat, alphaChannel, 3)
            Imgproc.threshold(alphaChannel, mask, alphaThreshold, 255.0, Imgproc.THRESH_BINARY)

            Core.findNonZero(mask, nonZero)
            if (nonZero.empty()) {
                return emptyList()
            }
            val roiRect: Rect = Imgproc.boundingRect(nonZero)
            watermarkGrayRoi = Mat(watermarkGray, roiRect).clone()
            watermarkMaskRoi = Mat(mask, roiRect).clone()
            watermarkBgrRoi = Mat(watermarkBgr, roiRect).clone()
            watermarkAlphaRoi = Mat(alphaChannel, roiRect).clone()
            if (watermarkMaskRoi.type() != CvType.CV_8UC1) {
                watermarkMaskRoi.convertTo(watermarkMaskRoi, CvType.CV_8UC1)
            }

            val watermarkRoiBitmap = Bitmap.createBitmap(
                workingWatermark,
                roiRect.x,
                roiRect.y,
                roiRect.width,
                roiRect.height
            )
            val watermarkRoiPixels = IntArray(roiRect.width * roiRect.height)
            watermarkRoiBitmap.getPixels(
                watermarkRoiPixels,
                0,
                roiRect.width,
                0,
                0,
                roiRect.width,
                roiRect.height
            )
            val watermarkAlphaData = DoubleArray(roiRect.width * roiRect.height)
            watermarkAlphaRoi.get(0, 0, watermarkAlphaData)
            val verificationContext = DetectionVerificationContext(
                baseBitmap = workingBase,
                roiRect = roiRect,
                watermarkPixels = watermarkRoiPixels,
                watermarkAlpha = watermarkAlphaData
            )

            val resultCols = baseGray.cols() - watermarkGrayRoi.cols() + 1
            val resultRows = baseGray.rows() - watermarkGrayRoi.rows() + 1
            if (resultCols <= 0 || resultRows <= 0) {
                return emptyList()
            }

            Imgproc.Canny(baseGray, baseEdges, 40.0, 120.0)
            val watermarkEdges = Mat()
            Imgproc.Canny(watermarkGrayRoi, watermarkEdges, 40.0, 120.0)
            Core.bitwise_and(watermarkEdges, watermarkMaskRoi, watermarkEdges)

            resultGray = Mat()
            Imgproc.matchTemplate(
                baseGray,
                watermarkGrayRoi,
                resultGray,
                Imgproc.TM_CCORR_NORMED,
                watermarkMaskRoi
            )

            colorAccumulation = Mat.zeros(resultRows, resultCols, CvType.CV_32FC1)
            for (channel in 0 until 3) {
                val baseChannel = Mat()
                val watermarkChannel = Mat()
                val channelResult = Mat()
                Core.extractChannel(baseBgr, baseChannel, channel)
                Core.extractChannel(watermarkBgrRoi, watermarkChannel, channel)
                Imgproc.matchTemplate(
                    baseChannel,
                    watermarkChannel,
                    channelResult,
                    Imgproc.TM_CCORR_NORMED,
                    watermarkMaskRoi
                )
                Core.add(colorAccumulation, channelResult, colorAccumulation)
                baseChannel.release()
                watermarkChannel.release()
                channelResult.release()
            }
            Core.multiply(colorAccumulation, Scalar(1.0 / 3.0), colorAccumulation)

            resultEdges = Mat()
            Imgproc.matchTemplate(
                baseEdges,
                watermarkEdges,
                resultEdges,
                Imgproc.TM_CCORR_NORMED
            )

            combinedResult = Mat()
            Core.addWeighted(resultGray, 0.6, colorAccumulation, 0.4, 0.0, combinedResult)
            val temp = Mat()
            Core.addWeighted(combinedResult, 0.8, resultEdges, 0.2, 0.0, temp)
            combinedResult.release()
            combinedResult = temp
            Core.normalize(combinedResult, combinedResult, 0.0, 1.0, Core.NORM_MINMAX)
            watermarkEdges.release()

            val detections = mutableListOf<WatermarkDetection>()
            val suppressionRadiusX = watermarkGrayRoi.cols() / 2
            val suppressionRadiusY = watermarkGrayRoi.rows() / 2
            val maxResultX = (combinedResult.cols() - 1).toDouble().coerceAtLeast(0.0)
            val maxResultY = (combinedResult.rows() - 1).toDouble().coerceAtLeast(0.0)

            var iterations = 0
            while (iterations < maxResults) {
                val minMax = Core.minMaxLoc(combinedResult)
                val maxVal = minMax.maxVal
                if (maxVal < matchThreshold) {
                    break
                }
                val maxLoc: Point = minMax.maxLoc
                val candidate = WatermarkDetection(
                    offsetX = (maxLoc.x - roiRect.x).toFloat(),
                    offsetY = (maxLoc.y - roiRect.y).toFloat(),
                    score = maxVal.toFloat()
                )
                val penalty = computeVerificationPenalty(candidate, verificationContext)
                val adjustedScore = (maxVal * (1.0 - penalty)).toFloat()
                if (penalty <= 0.45 && adjustedScore >= matchThreshold * 0.6f) {
                    detections.add(
                        WatermarkDetection(
                            offsetX = candidate.offsetX,
                            offsetY = candidate.offsetY,
                            score = adjustedScore
                        )
                    )
                }

                val topLeftX = max(0.0, maxLoc.x - suppressionRadiusX)
                val topLeftY = max(0.0, maxLoc.y - suppressionRadiusY)
                val bottomRightX = min(maxResultX, maxLoc.x + suppressionRadiusX)
                val bottomRightY = min(maxResultY, maxLoc.y + suppressionRadiusY)
                Imgproc.rectangle(
                    combinedResult,
                    Point(topLeftX, topLeftY),
                    Point(bottomRightX, bottomRightY),
                    Scalar(-1.0),
                    -1
                )
                iterations++
            }

            detections
        } finally {
            baseMat.release()
            watermarkMat.release()
            baseGray.release()
            watermarkGray.release()
            baseBgr.release()
            watermarkBgr.release()
            alphaChannel.release()
            mask.release()
            nonZero.release()
            watermarkGrayRoi.release()
            watermarkMaskRoi.release()
            watermarkBgrRoi.release()
            watermarkAlphaRoi.release()
            baseEdges.release()
            resultGray.release()
            colorAccumulation.release()
            resultEdges.release()
            combinedResult.release()
        }
    }

    fun refinePosition(
        base: Bitmap,
        watermark: Bitmap,
        approximateOffsetX: Float,
        approximateOffsetY: Float,
        searchScale: Float = 2f
    ): WatermarkDetection? {
        if (base.width < watermark.width || base.height < watermark.height) {
            return null
        }

        val searchWidth = (watermark.width * searchScale).roundToInt()
            .coerceAtLeast(watermark.width)
            .coerceAtMost(base.width)
        val searchHeight = (watermark.height * searchScale).roundToInt()
            .coerceAtLeast(watermark.height)
            .coerceAtMost(base.height)

        val centerX = (approximateOffsetX + watermark.width / 2f)
            .coerceIn(0f, base.width.toFloat())
        val centerY = (approximateOffsetY + watermark.height / 2f)
            .coerceIn(0f, base.height.toFloat())

        val halfSearchWidth = searchWidth / 2f
        val halfSearchHeight = searchHeight / 2f
        val tentativeLeft = floor(centerX - halfSearchWidth).toInt()
        val tentativeTop = floor(centerY - halfSearchHeight).toInt()
        val maxLeft = base.width - searchWidth
        val maxTop = base.height - searchHeight
        val left = tentativeLeft.coerceIn(0, maxLeft)
        val top = tentativeTop.coerceIn(0, maxTop)

        val croppedBase = Bitmap.createBitmap(base, left, top, searchWidth, searchHeight)
        val refined = detect(
            base = croppedBase,
            watermark = watermark,
            maxResults = 1
        ).firstOrNull() ?: return null

        return refined.copy(
            offsetX = refined.offsetX + left,
            offsetY = refined.offsetY + top
        )
    }

    private fun ensureArgb(source: Bitmap): Bitmap {
        return if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    private fun computeVerificationPenalty(
        detection: WatermarkDetection,
        context: DetectionVerificationContext
    ): Double {
        val roi = context.roiRect
        val width = roi.width
        val height = roi.height
        if (width <= 0 || height <= 0) {
            return 1.0
        }
        val baseLeft = detection.offsetX.roundToInt() + roi.x
        val baseTop = detection.offsetY.roundToInt() + roi.y
        if (
            baseLeft < 0 ||
            baseTop < 0 ||
            baseLeft + width > context.baseBitmap.width ||
            baseTop + height > context.baseBitmap.height
        ) {
            return 1.0
        }

        val basePixels = IntArray(width * height)
        context.baseBitmap.getPixels(
            basePixels,
            0,
            width,
            baseLeft,
            baseTop,
            width,
            height
        )

        var highAlphaDiffSum = 0.0
        var highAlphaCount = 0
        var mediumPenaltySum = 0.0
        var mediumAlphaCount = 0

        for (index in basePixels.indices) {
            val alpha = context.watermarkAlpha[index] / 255.0
            if (alpha <= 0.01) {
                continue
            }

            val baseColor = basePixels[index]
            val wmColor = context.watermarkPixels[index]
            val baseR = Color.red(baseColor)
            val baseG = Color.green(baseColor)
            val baseB = Color.blue(baseColor)
            val wmR = Color.red(wmColor)
            val wmG = Color.green(wmColor)
            val wmB = Color.blue(wmColor)

            if (alpha >= 0.85) {
                highAlphaDiffSum +=
                    abs(baseR - wmR) +
                        abs(baseG - wmG) +
                        abs(baseB - wmB)
                highAlphaCount += 3
            } else {
                val denom = 1.0 - alpha
                if (denom > 0.05) {
                    mediumPenaltySum += channelOverflowPenalty(baseR, wmR, alpha)
                    mediumPenaltySum += channelOverflowPenalty(baseG, wmG, alpha)
                    mediumPenaltySum += channelOverflowPenalty(baseB, wmB, alpha)
                    mediumAlphaCount += 3
                }
            }
        }

        val normalizedHighAlpha = if (highAlphaCount > 0) {
            (highAlphaDiffSum / highAlphaCount) / 255.0
        } else {
            0.0
        }
        val normalizedMediumPenalty = if (mediumAlphaCount > 0) {
            (mediumPenaltySum / mediumAlphaCount) / 255.0
        } else {
            0.0
        }

        return (normalizedHighAlpha * 0.75) + (normalizedMediumPenalty * 0.25)
    }

    private fun channelOverflowPenalty(
        baseComponent: Int,
        watermarkComponent: Int,
        alpha: Double
    ): Double {
        val denom = 1.0 - alpha
        if (denom <= 0.0) {
            return 0.0
        }
        val background = (baseComponent - alpha * watermarkComponent) / denom
        return when {
            background < -5.0 -> abs(background + 5.0)
            background > 260.0 -> abs(background - 260.0)
            else -> 0.0
        }
    }

    private data class DetectionVerificationContext(
        val baseBitmap: Bitmap,
        val roiRect: Rect,
        val watermarkPixels: IntArray,
        val watermarkAlpha: DoubleArray
    )
}
