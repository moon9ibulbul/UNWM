package com.astral.unwm

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

private const val DEFAULT_MATCH_THRESHOLD = 0.9
private const val DEFAULT_ALPHA_THRESHOLD = 5.0
private const val MAX_DETECTIONS_PER_IMAGE = 10

/**
 * Detects watermark positions using OpenCV's template matching with masking.
 */
object WatermarkDetector {
    fun detect(
        base: Bitmap,
        watermark: Bitmap,
        maxResults: Int = MAX_DETECTIONS_PER_IMAGE,
        matchThreshold: Double = DEFAULT_MATCH_THRESHOLD,
        alphaThreshold: Double = DEFAULT_ALPHA_THRESHOLD
    ): List<WatermarkDetection> {
        if (base.width < watermark.width || base.height < watermark.height) {
            return emptyList()
        }

        val baseMat = Mat()
        val watermarkMat = Mat()

        return try {
            Utils.bitmapToMat(base, baseMat)
            Utils.bitmapToMat(watermark, watermarkMat)

            var detections = detectOnMats(
                baseMat,
                watermarkMat,
                maxResults,
                matchThreshold,
                alphaThreshold
            )

            if (detections.isEmpty()) {
                val baseInverted = Mat()
                val watermarkInverted = Mat()
                try {
                    invertColors(baseMat, baseInverted)
                    invertColors(watermarkMat, watermarkInverted)
                    detections = detectOnMats(
                        baseInverted,
                        watermarkInverted,
                        maxResults,
                        matchThreshold,
                        alphaThreshold
                    )
                } finally {
                    baseInverted.release()
                    watermarkInverted.release()
                }
            }
            detections
        } finally {
            baseMat.release()
            watermarkMat.release()
        }
    }

    private fun detectOnMats(
        baseMat: Mat,
        watermarkMat: Mat,
        maxResults: Int,
        matchThreshold: Double,
        alphaThreshold: Double
    ): List<WatermarkDetection> {
        val baseGray = Mat()
        val watermarkGray = Mat()
        val baseBgr = Mat()
        val watermarkBgr = Mat()
        val alphaChannel = Mat()
        val mask = Mat()
        val nonZero = Mat()
        val baseEdges = Mat()
        val watermarkEdges = Mat()
        var watermarkGrayRoi = Mat()
        var watermarkMaskRoi = Mat()
        var watermarkBgrRoi = Mat()
        var resultGray = Mat()
        var colorAccumulation = Mat()
        var resultEdges = Mat()
        var combinedResult = Mat()

        return try {
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

            val resultCols = baseGray.cols() - watermarkGrayRoi.cols() + 1
            val resultRows = baseGray.rows() - watermarkGrayRoi.rows() + 1
            if (resultCols <= 0 || resultRows <= 0) {
                return emptyList()
            }

            Imgproc.Canny(baseGray, baseEdges, 40.0, 120.0)
            Imgproc.Canny(watermarkGrayRoi, watermarkEdges, 40.0, 120.0)
            Core.bitwise_and(watermarkEdges, watermarkMaskRoi, watermarkEdges)

            resultGray = Mat()
            Imgproc.matchTemplate(
                baseGray,
                watermarkGrayRoi,
                resultGray,
                Imgproc.TM_CCOEFF_NORMED,
                watermarkMaskRoi
            )
            // Patch NaNs in resultGray (safeguard)
            try { Core.patchNaNs(resultGray, 0.0) } catch (e: Exception) { }

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
                    Imgproc.TM_CCOEFF_NORMED,
                    watermarkMaskRoi
                )
                Core.add(colorAccumulation, channelResult, colorAccumulation)
                baseChannel.release()
                watermarkChannel.release()
                channelResult.release()
            }
            Core.multiply(colorAccumulation, Scalar(1.0 / 3.0), colorAccumulation)
            // Patch NaNs in colorAccumulation (safeguard)
            try { Core.patchNaNs(colorAccumulation, 0.0) } catch (e: Exception) { }

            resultEdges = Mat()
            // Edges are binary-ish (0 or 255), so CCORR is appropriate and efficient
            Imgproc.matchTemplate(
                baseEdges,
                watermarkEdges,
                resultEdges,
                Imgproc.TM_CCORR_NORMED
            )
            // Patch NaNs in resultEdges (safeguard)
            try { Core.patchNaNs(resultEdges, 0.0) } catch (e: Exception) { }

            combinedResult = Mat()
            // CCOEFF can be negative (mismatch). We only care about positive correlation.
            // But negative values combined might lower the score, which is good.
            Core.addWeighted(resultGray, 0.6, colorAccumulation, 0.4, 0.0, combinedResult)

            // For edges, we use CCORR which is [0, 1].
            // For others, CCOEFF is [-1, 1].

            val temp = Mat()
            Core.addWeighted(combinedResult, 0.8, resultEdges, 0.2, 0.0, temp)
            combinedResult.release()
            combinedResult = temp

            val detections = mutableListOf<WatermarkDetection>()
            val suppressionRadiusX = watermarkGrayRoi.cols() / 2
            val suppressionRadiusY = watermarkGrayRoi.rows() / 2
            val maxResultX = (combinedResult.cols() - 1).toDouble().coerceAtLeast(0.0)
            val maxResultY = (combinedResult.rows() - 1).toDouble().coerceAtLeast(0.0)

            var iterations = 0
            while (iterations < maxResults) {
                val minMax = Core.minMaxLoc(combinedResult)
                val maxVal = minMax.maxVal

                if (java.lang.Double.isNaN(maxVal) || maxVal < matchThreshold) {
                    break
                }
                val maxLoc: Point = minMax.maxLoc
                detections.add(
                    WatermarkDetection(
                        offsetX = (maxLoc.x - roiRect.x).toFloat(),
                        offsetY = (maxLoc.y - roiRect.y).toFloat(),
                        score = maxVal.toFloat()
                    )
                )

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
            baseGray.release()
            watermarkGray.release()
            baseBgr.release()
            watermarkBgr.release()
            alphaChannel.release()
            mask.release()
            nonZero.release()
            watermarkEdges.release()
            watermarkGrayRoi.release()
            watermarkMaskRoi.release()
            watermarkBgrRoi.release()
            baseEdges.release()
            resultGray.release()
            colorAccumulation.release()
            resultEdges.release()
            combinedResult.release()
        }
    }

    private fun invertColors(src: Mat, dst: Mat) {
        Core.bitwise_not(src, dst)
        if (src.channels() == 4) {
            val alpha = Mat()
            Core.extractChannel(src, alpha, 3)
            Core.insertChannel(alpha, dst, 3)
            alpha.release()
        }
    }
}
