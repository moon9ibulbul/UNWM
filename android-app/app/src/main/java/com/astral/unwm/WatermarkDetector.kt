package com.astral.unwm

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvException
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
        val baseGray = Mat()
        val watermarkGray = Mat()
        val baseBgr = Mat()
        val watermarkBgr = Mat()
        val alphaChannel = Mat()
        val mask = Mat()
        val nonZero = Mat()
        val baseGradient = Mat()
        val watermarkGradient = Mat()
        val baseGrayForMatch = Mat()
        val baseBgrForMatch = Mat()

        return try {
            Utils.bitmapToMat(base, baseMat)
            Utils.bitmapToMat(watermark, watermarkMat)

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
            val watermarkGrayRoi = Mat(watermarkGray, roiRect).clone()
            val watermarkMaskRoi = Mat(mask, roiRect).clone()
            val watermarkBgrRoi = Mat(watermarkBgr, roiRect).clone()

            val meanBrightness = Core.mean(baseGray).`val`[0]
            val useClahe = meanBrightness < 80.0

            if (useClahe) {
                applyClahe(watermarkGrayRoi, watermarkMaskRoi)
                val emptyMask = Mat()
                applyClahe(baseGray, emptyMask)
                emptyMask.release()
            }

            baseGray.convertTo(baseGrayForMatch, CvType.CV_32F, 1.0 / 255.0)
            baseBgr.convertTo(baseBgrForMatch, CvType.CV_32F, 1.0 / 255.0)

            val watermarkGrayFloat = Mat()
            watermarkGrayRoi.convertTo(watermarkGrayFloat, CvType.CV_32F, 1.0 / 255.0)

            computeGradientMagnitude(watermarkGrayFloat, watermarkGradient)
            normalizeToUnit(watermarkGradient)
            computeGradientMagnitude(baseGrayForMatch, baseGradient)
            normalizeToUnit(baseGradient)

            val resultCols = baseGray.cols() - watermarkGrayRoi.cols() + 1
            val resultRows = baseGray.rows() - watermarkGrayRoi.rows() + 1
            if (resultCols <= 0 || resultRows <= 0) {
                watermarkGrayRoi.release()
                watermarkMaskRoi.release()
                watermarkBgrRoi.release()
                watermarkGrayFloat.release()
                return emptyList()
            }

            val resultGray = matchWithMask(baseGrayForMatch, watermarkGrayFloat, watermarkMaskRoi)

            val colorAccumulation = Mat.zeros(resultRows, resultCols, CvType.CV_32FC1)
            for (channel in 0 until 3) {
                val baseChannel = Mat()
                val wmChannel = Mat()
                Core.extractChannel(baseBgrForMatch, baseChannel, channel)
                Core.extractChannel(watermarkBgrRoi, wmChannel, channel)
                wmChannel.convertTo(wmChannel, CvType.CV_32F, 1.0 / 255.0)
                val channelResult = matchWithMask(baseChannel, wmChannel, watermarkMaskRoi)
                Core.add(colorAccumulation, channelResult, colorAccumulation)
                baseChannel.release()
                wmChannel.release()
                channelResult.release()
            }
            Core.multiply(colorAccumulation, Scalar(1.0 / 3.0), colorAccumulation)

            val resultGradient = matchWithMask(baseGradient, watermarkGradient, watermarkMaskRoi)

            Core.normalize(resultGray, resultGray, 0.0, 1.0, Core.NORM_MINMAX)
            Core.normalize(colorAccumulation, colorAccumulation, 0.0, 1.0, Core.NORM_MINMAX)
            Core.normalize(resultGradient, resultGradient, 0.0, 1.0, Core.NORM_MINMAX)

            val baseCombined = Mat()
            Core.addWeighted(resultGray, 0.6, colorAccumulation, 0.4, 0.0, baseCombined)

            val gradientWeight = if (meanBrightness < 80.0) 0.2 else 0.15
            val combinedWeight = 1.0 - gradientWeight
            val combinedResult = Mat()
            Core.addWeighted(baseCombined, combinedWeight, resultGradient, gradientWeight, 0.0, combinedResult)
            Core.normalize(combinedResult, combinedResult, 0.0, 1.0, Core.NORM_MINMAX)

            val detections = mutableListOf<WatermarkDetection>()
            val suppressionRadiusX = watermarkGrayRoi.cols() / 2
            val suppressionRadiusY = watermarkGrayRoi.rows() / 2
            val maxResultX = (combinedResult.cols() - 1).toDouble().coerceAtLeast(0.0)
            val maxResultY = (combinedResult.rows() - 1).toDouble().coerceAtLeast(0.0)

            while (detections.size < maxResults) {
                val minMax = Core.minMaxLoc(combinedResult)
                val maxVal = minMax.maxVal
                val maxLoc = minMax.maxLoc
                if (maxVal < matchThreshold) {
                    break
                }
                detections.add(
                    WatermarkDetection(
                        offsetX = (maxLoc.x - roiRect.x).toFloat(),
                        offsetY = (maxLoc.y - roiRect.y).toFloat(),
                        score = maxVal.toFloat()
                    )
                )
                suppressRegion(
                    combinedResult,
                    maxLoc,
                    suppressionRadiusX,
                    suppressionRadiusY,
                    maxResultX,
                    maxResultY
                )
            }

            watermarkGrayRoi.release()
            watermarkMaskRoi.release()
            watermarkBgrRoi.release()
            watermarkGrayFloat.release()
            resultGray.release()
            colorAccumulation.release()
            resultGradient.release()
            baseCombined.release()
            combinedResult.release()

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
            baseGradient.release()
            watermarkGradient.release()
            baseGrayForMatch.release()
            baseBgrForMatch.release()
        }
    }

    private fun computeGradientMagnitude(gray: Mat, output: Mat) {
        val gradX = Mat()
        val gradY = Mat()
        Imgproc.Sobel(gray, gradX, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(gray, gradY, CvType.CV_32F, 0, 1, 3)
        Core.magnitude(gradX, gradY, output)
        gradX.release()
        gradY.release()
    }

    private fun suppressRegion(
        result: Mat,
        location: Point,
        radiusX: Int,
        radiusY: Int,
        maxX: Double,
        maxY: Double
    ) {
        val topLeftX = max(0.0, location.x - radiusX)
        val topLeftY = max(0.0, location.y - radiusY)
        val bottomRightX = min(maxX, location.x + radiusX)
        val bottomRightY = min(maxY, location.y + radiusY)
        Imgproc.rectangle(
            result,
            Point(topLeftX, topLeftY),
            Point(bottomRightX, bottomRightY),
            Scalar(-1.0),
            -1
        )
    }

    private fun applyClahe(gray: Mat, mask: Mat) {
        val clahe = Imgproc.createCLAHE()
        val temp = Mat()
        clahe.apply(gray, temp)
        if (!mask.empty()) {
            temp.copyTo(gray, mask)
        } else {
            temp.copyTo(gray)
        }
        temp.release()
    }

    private fun normalizeToUnit(mat: Mat) {
        Core.normalize(mat, mat, 0.0, 1.0, Core.NORM_MINMAX)
    }

    private fun matchWithMask(base: Mat, templ: Mat, mask: Mat): Mat {
        val resultCols = base.cols() - templ.cols() + 1
        val resultRows = base.rows() - templ.rows() + 1
        val result = Mat.zeros(resultRows, resultCols, CvType.CV_32F)
        try {
            Imgproc.matchTemplate(base, templ, result, Imgproc.TM_CCOEFF_NORMED, mask)
        } catch (e: CvException) {
            val maskedBase = Mat()
            val maskedTempl = Mat()
            Core.bitwise_and(base, base, maskedBase, mask)
            Core.bitwise_and(templ, templ, maskedTempl, mask)
            Imgproc.matchTemplate(maskedBase, maskedTempl, result, Imgproc.TM_CCORR_NORMED)
            maskedBase.release()
            maskedTempl.release()
        }
        return result
    }
}
