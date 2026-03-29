package com.astral.unwm

import java.awt.image.BufferedImage
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

private const val DEFAULT_MATCH_THRESHOLD = 0.75  // Diturunkan dari 0.9 — TM_CCOEFF_NORMED lebih strict
private const val DEFAULT_ALPHA_THRESHOLD = 5.0
private const val MAX_DETECTIONS_PER_IMAGE = 10

/**
 * Detects watermark positions using OpenCV's template matching with masking.
 */
object WatermarkDetector {
    init {
        // Ensure OpenCV is loaded. In a desktop app using openpnp/opencv, this is usually automatic or needs Loader.load()
        // We will call Loader.load() in main, but good to have here if accessed statically.
        // For now, assume Main loads it.
    }

    fun detect(
        base: BufferedImage,
        watermark: BufferedImage,
        maxResults: Int = MAX_DETECTIONS_PER_IMAGE,
        matchThreshold: Double = DEFAULT_MATCH_THRESHOLD,
        alphaThreshold: Double = DEFAULT_ALPHA_THRESHOLD
    ): List<WatermarkDetection> {
        if (base.width < watermark.width || base.height < watermark.height) {
            return emptyList()
        }

        val baseMat = OpenCVUtils.bufferedImageToMat(base)
        val watermarkMat = OpenCVUtils.bufferedImageToMat(watermark)

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
        var resultGray = Mat()
        var colorAccumulation = Mat()
        var resultEdges = Mat()
        var combinedResult = Mat()

        return try {
            Imgproc.cvtColor(baseMat, baseGray, Imgproc.COLOR_BGRA2GRAY)
            Imgproc.cvtColor(watermarkMat, watermarkGray, Imgproc.COLOR_BGRA2GRAY)
            Imgproc.cvtColor(baseMat, baseBgr, Imgproc.COLOR_BGRA2BGR)
            Imgproc.cvtColor(watermarkMat, watermarkBgr, Imgproc.COLOR_BGRA2BGR)

            // Alpha is channel 3 in BGRA
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
            val watermarkEdges = Mat()
            Imgproc.Canny(watermarkGrayRoi, watermarkEdges, 40.0, 120.0)
            Core.bitwise_and(watermarkEdges, watermarkMaskRoi, watermarkEdges)

            // FIX: Ganti TM_CCORR_NORMED → TM_CCOEFF_NORMED
            // TM_CCOEFF_NORMED mengukur korelasi terhadap mean (deviasi),
            // sehingga tidak bergantung pada brightness absolut background.
            // Watermark putih di area gelap pun bisa terdeteksi.
            resultGray = Mat()
            Imgproc.matchTemplate(
                baseGray,
                watermarkGrayRoi,
                resultGray,
                Imgproc.TM_CCOEFF_NORMED,  // ← FIX
                watermarkMaskRoi
            )

            colorAccumulation = Mat.zeros(resultRows, resultCols, CvType.CV_32FC1)
            for (channel in 0 until 3) {
                val baseChannel = Mat()
                val watermarkChannel = Mat()
                val channelResult = Mat()
                Core.extractChannel(baseBgr, baseChannel, channel)
                Core.extractChannel(watermarkBgrRoi, watermarkChannel, channel)
                // FIX: Sama, ganti ke TM_CCOEFF_NORMED untuk per-channel color matching
                Imgproc.matchTemplate(
                    baseChannel,
                    watermarkChannel,
                    channelResult,
                    Imgproc.TM_CCOEFF_NORMED,  // ← FIX
                    watermarkMaskRoi
                )
                Core.add(colorAccumulation, channelResult, colorAccumulation)
                baseChannel.release()
                watermarkChannel.release()
                channelResult.release()
            }
            Core.multiply(colorAccumulation, Scalar(1.0 / 3.0), colorAccumulation)

            // Edge matching tetap pakai TM_CCORR_NORMED — edge sudah binary (0/255),
            // tidak terpengaruh brightness background, jadi aman dibiarkan.
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
            // normalize() memastikan nilai negatif dari TM_CCOEFF_NORMED di-clamp ke range [0,1]
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
            alphaChannel.release()
            mask.release()
            nonZero.release()
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
}
