package com.astral.unwm

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

private const val DEFAULT_MATCH_THRESHOLD = 0.6
private const val DEFAULT_MAX_RESULTS = 5
private const val DEFAULT_ALPHA_THRESHOLD = 5.0

/**
 * Detects watermark positions using OpenCV's template matching with masking.
 */
object WatermarkDetector {
    fun detect(
        base: Bitmap,
        watermark: Bitmap,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        matchThreshold: Double = DEFAULT_MATCH_THRESHOLD,
        alphaThreshold: Double = DEFAULT_ALPHA_THRESHOLD
    ): List<WatermarkDetection> {
        if (base.width < watermark.width || base.height < watermark.height) {
            return emptyList()
        }

        val baseMat = Mat()
        val watermarkMat = Mat()
        val baseGray = Mat()
        val watermarkGray = Mat()
        val alphaChannel = Mat()
        val mask = Mat()
        val result = Mat()

        return try {
            Utils.bitmapToMat(base, baseMat)
            Utils.bitmapToMat(watermark, watermarkMat)

            Imgproc.cvtColor(baseMat, baseGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(watermarkMat, watermarkGray, Imgproc.COLOR_RGBA2GRAY)
            Core.extractChannel(watermarkMat, alphaChannel, 3)
            Imgproc.threshold(alphaChannel, mask, alphaThreshold, 255.0, Imgproc.THRESH_BINARY)

            val resultCols = baseGray.cols() - watermarkGray.cols() + 1
            val resultRows = baseGray.rows() - watermarkGray.rows() + 1
            if (resultCols <= 0 || resultRows <= 0) {
                return emptyList()
            }

            result.create(resultRows, resultCols, CvType.CV_32FC1)
            Imgproc.matchTemplate(baseGray, watermarkGray, result, Imgproc.TM_CCOEFF_NORMED, mask)

            val detections = mutableListOf<WatermarkDetection>()
            val suppressionRadiusX = watermarkGray.cols() / 2
            val suppressionRadiusY = watermarkGray.rows() / 2
            val maxResultX = (result.cols() - 1).toDouble().coerceAtLeast(0.0)
            val maxResultY = (result.rows() - 1).toDouble().coerceAtLeast(0.0)

            repeat(maxResults) {
                val minMax = Core.minMaxLoc(result)
                val maxVal = minMax.maxVal
                if (maxVal < matchThreshold) {
                    return@repeat
                }
                val maxLoc: Point = minMax.maxLoc
                detections.add(
                    WatermarkDetection(
                        offsetX = maxLoc.x.toFloat(),
                        offsetY = maxLoc.y.toFloat(),
                        score = maxVal.toFloat()
                    )
                )

                val topLeftX = max(0.0, maxLoc.x - suppressionRadiusX)
                val topLeftY = max(0.0, maxLoc.y - suppressionRadiusY)
                val bottomRightX = min(maxResultX, maxLoc.x + suppressionRadiusX)
                val bottomRightY = min(maxResultY, maxLoc.y + suppressionRadiusY)
                Imgproc.rectangle(
                    result,
                    Point(topLeftX, topLeftY),
                    Point(bottomRightX, bottomRightY),
                    Scalar(-1.0),
                    -1
                )
            }

            detections
        } finally {
            baseMat.release()
            watermarkMat.release()
            baseGray.release()
            watermarkGray.release()
            alphaChannel.release()
            mask.release()
            result.release()
        }
    }
}
