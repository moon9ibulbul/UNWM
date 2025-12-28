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
 * Includes strategies for both light and dark backgrounds by checking inverted images.
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

        // --- Mats for Normal Pass ---
        val baseMat = Mat()
        val watermarkMat = Mat()
        val baseGray = Mat()
        val watermarkGray = Mat()
        val baseBgr = Mat()
        val watermarkBgr = Mat()

        // --- Mats for Inverted Pass ---
        val baseGrayInv = Mat()
        val watermarkGrayInv = Mat()
        val baseBgrInv = Mat()
        val watermarkBgrInv = Mat()

        // --- Common ---
        val alphaChannel = Mat()
        val mask = Mat()
        val nonZero = Mat()

        // --- ROIs ---
        var watermarkGrayRoi = Mat()
        var watermarkMaskRoi = Mat()
        var watermarkBgrRoi = Mat()
        var watermarkGrayRoiInv = Mat()
        var watermarkBgrRoiInv = Mat()

        // --- Results ---
        var resultGray = Mat()
        var resultGrayInv = Mat()
        var resultGrayMax = Mat()

        var colorAccumulation = Mat()
        var colorAccumulationInv = Mat()
        var colorAccumulationMax = Mat()

        val baseEdges = Mat()
        val watermarkEdges = Mat()
        var resultEdges = Mat()

        var combinedResult = Mat()

        return try {
            Utils.bitmapToMat(base, baseMat)
            Utils.bitmapToMat(watermark, watermarkMat)

            // 1. Prepare Normal Images
            Imgproc.cvtColor(baseMat, baseGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(watermarkMat, watermarkGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(baseMat, baseBgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(watermarkMat, watermarkBgr, Imgproc.COLOR_RGBA2BGR)

            // 2. Prepare Mask
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

            // 3. Prepare Inverted Images (for dark background handling)
            Core.bitwise_not(baseGray, baseGrayInv)
            Core.bitwise_not(watermarkGrayRoi, watermarkGrayRoiInv)
            Core.bitwise_not(baseBgr, baseBgrInv)
            Core.bitwise_not(watermarkBgrRoi, watermarkBgrRoiInv)

            // 4. Edges (Reverted thresholds to avoid noise issues)
            Imgproc.Canny(baseGray, baseEdges, 40.0, 120.0)
            Imgproc.Canny(watermarkGrayRoi, watermarkEdges, 40.0, 120.0)
            Core.bitwise_and(watermarkEdges, watermarkMaskRoi, watermarkEdges)

            // 5. Match Gray (Normal)
            resultGray = Mat()
            Imgproc.matchTemplate(
                baseGray,
                watermarkGrayRoi,
                resultGray,
                Imgproc.TM_CCORR_NORMED,
                watermarkMaskRoi
            )
            cleanResult(resultGray)

            // 6. Match Gray (Inverted)
            resultGrayInv = Mat()
            Imgproc.matchTemplate(
                baseGrayInv,
                watermarkGrayRoiInv,
                resultGrayInv,
                Imgproc.TM_CCORR_NORMED,
                watermarkMaskRoi
            )
            cleanResult(resultGrayInv)

            // 7. Combine Gray (Max)
            resultGrayMax = Mat()
            Core.max(resultGray, resultGrayInv, resultGrayMax)

            // 8. Match Color (Normal)
            colorAccumulation = Mat.zeros(resultRows, resultCols, CvType.CV_32FC1)
            processColorChannels(baseBgr, watermarkBgrRoi, watermarkMaskRoi, colorAccumulation)

            // 9. Match Color (Inverted)
            colorAccumulationInv = Mat.zeros(resultRows, resultCols, CvType.CV_32FC1)
            processColorChannels(baseBgrInv, watermarkBgrRoiInv, watermarkMaskRoi, colorAccumulationInv)

            // 10. Combine Color (Max)
            colorAccumulationMax = Mat()
            Core.max(colorAccumulation, colorAccumulationInv, colorAccumulationMax)

            // 11. Match Edges
            resultEdges = Mat()
            Imgproc.matchTemplate(
                baseEdges,
                watermarkEdges,
                resultEdges,
                Imgproc.TM_CCORR_NORMED
            )
            cleanResult(resultEdges)

            // 12. Final Combination
            combinedResult = Mat()
            // Weight: Gray(Max) 60% + Color(Max) 40%
            Core.addWeighted(resultGrayMax, 0.6, colorAccumulationMax, 0.4, 0.0, combinedResult)

            // Weight: (Gray+Color) 80% + Edges 20%
            val temp = Mat()
            Core.addWeighted(combinedResult, 0.8, resultEdges, 0.2, 0.0, temp)
            combinedResult.release()
            combinedResult = temp
            Core.normalize(combinedResult, combinedResult, 0.0, 1.0, Core.NORM_MINMAX)

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
            baseMat.release()
            watermarkMat.release()
            baseGray.release()
            watermarkGray.release()
            baseBgr.release()
            watermarkBgr.release()

            baseGrayInv.release()
            watermarkGrayInv.release()
            baseBgrInv.release()
            watermarkBgrInv.release()

            alphaChannel.release()
            mask.release()
            nonZero.release()
            watermarkGrayRoi.release()
            watermarkMaskRoi.release()
            watermarkBgrRoi.release()
            watermarkGrayRoiInv.release()
            watermarkBgrRoiInv.release()

            baseEdges.release()
            watermarkEdges.release()

            resultGray.release()
            resultGrayInv.release()
            resultGrayMax.release()

            colorAccumulation.release()
            colorAccumulationInv.release()
            colorAccumulationMax.release()

            resultEdges.release()
            combinedResult.release()
        }
    }

    private fun processColorChannels(base: Mat, wmRoi: Mat, mask: Mat, accum: Mat) {
        for (channel in 0 until 3) {
            val baseChannel = Mat()
            val watermarkChannel = Mat()
            val channelResult = Mat()
            Core.extractChannel(base, baseChannel, channel)
            Core.extractChannel(wmRoi, watermarkChannel, channel)
            Imgproc.matchTemplate(
                baseChannel,
                watermarkChannel,
                channelResult,
                Imgproc.TM_CCORR_NORMED,
                mask
            )
            cleanResult(channelResult)
            Core.add(accum, channelResult, accum)
            baseChannel.release()
            watermarkChannel.release()
            channelResult.release()
        }
        Core.multiply(accum, Scalar(1.0 / 3.0), accum)
    }

    private fun cleanResult(mat: Mat) {
        val mask = Mat()
        // Checks for NaNs. NaN != NaN is True for CMP_NE.
        // So this mask will be 255 where NaN exists.
        Core.compare(mat, mat, mask, Core.CMP_NE)
        mat.setTo(Scalar(0.0), mask)
        mask.release()
    }
}
