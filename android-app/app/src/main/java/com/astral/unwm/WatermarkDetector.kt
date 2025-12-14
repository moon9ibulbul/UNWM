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
import kotlin.math.roundToInt

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
        val brightnessMap = Mat()
        val watermarkGradient = Mat()
        var watermarkGrayRoi = Mat()
        var watermarkMaskRoi = Mat()
        var watermarkBgrRoi = Mat()

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
            watermarkGrayRoi = Mat(watermarkGray, roiRect).clone()
            watermarkMaskRoi = Mat(mask, roiRect).clone()
            watermarkBgrRoi = Mat(watermarkBgr, roiRect).clone()

            computeGradientMagnitude(watermarkGrayRoi, watermarkGradient)

            // Build a soft brightness map to decide when to switch into the dark-focused pipeline.
            Imgproc.blur(baseGray, brightnessMap, org.opencv.core.Size(15.0, 15.0))

            // Gradient magnitude helps on dark backgrounds where color/gray contrast is weak.
            computeGradientMagnitude(baseGray, baseGradient)

            val detections = mutableListOf<WatermarkDetection>()
            val scales = doubleArrayOf(0.85, 0.95, 1.0, 1.05, 1.15)
            val gridRows = 4
            val gridCols = 4
            val topCandidatesPerScale = 8

            for (scale in scales) {
                val scaledGray = Mat()
                val scaledBgr = Mat()
                val scaledMask = Mat()
                val scaledGradient = Mat()

                try {
                    resizeWithMask(watermarkGrayRoi, watermarkMaskRoi, scaledGray, scaledMask, scale)
                    resizeWithMask(watermarkBgrRoi, watermarkMaskRoi, scaledBgr, Mat(), scale)
                    resizeWithMask(watermarkGradient, watermarkMaskRoi, scaledGradient, Mat(), scale)

                    val resultCols = baseGray.cols() - scaledGray.cols() + 1
                    val resultRows = baseGray.rows() - scaledGray.rows() + 1
                    if (resultCols <= 0 || resultRows <= 0) {
                        continue
                    }

                    val grayZeroMean = zeroMeanMasked(scaledGray, scaledMask)
                    val baseBlurred = Mat()
                    Imgproc.blur(baseGray, baseBlurred, org.opencv.core.Size(scaledGray.cols().toDouble(), scaledGray.rows().toDouble()))
                    val baseZeroMean = Mat()
                    Core.subtract(baseGray, baseBlurred, baseZeroMean)
                    baseZeroMean.convertTo(baseZeroMean, CvType.CV_32F, 1.0 / 255.0)
                    baseBlurred.release()

                    val resultGray = Mat()
                    Imgproc.matchTemplate(baseZeroMean, grayZeroMean, resultGray, Imgproc.TM_CCORR_NORMED, scaledMask)

                    val colorAccumulation = Mat.zeros(resultRows, resultCols, CvType.CV_32FC1)
                    for (channel in 0 until 3) {
                        val baseChannel = Mat()
                        val wmChannel = Mat()
                        val channelResult = Mat()
                        Core.extractChannel(baseBgr, baseChannel, channel)
                        baseChannel.convertTo(baseChannel, CvType.CV_32F, 1.0 / 255.0)
                        Core.extractChannel(scaledBgr, wmChannel, channel)
                        val wmZeroMean = zeroMeanMasked(wmChannel, scaledMask)
                        Imgproc.matchTemplate(baseChannel, wmZeroMean, channelResult, Imgproc.TM_CCORR_NORMED, scaledMask)
                        Core.add(colorAccumulation, channelResult, colorAccumulation)
                        baseChannel.release()
                        wmChannel.release()
                        channelResult.release()
                        wmZeroMean.release()
                    }
                    Core.multiply(colorAccumulation, Scalar(1.0 / 3.0), colorAccumulation)

                    val watermarkGrad = zeroMeanMasked(scaledGradient, scaledMask)
                    val resultGradient = Mat()
                    Imgproc.matchTemplate(baseGradient, watermarkGrad, resultGradient, Imgproc.TM_CCORR_NORMED, scaledMask)

                    val combinedForSearch = Mat()
                    Core.addWeighted(resultGray, 0.6, colorAccumulation, 0.4, 0.0, combinedForSearch)
                    Core.addWeighted(combinedForSearch, 0.7, resultGradient, 0.3, 0.0, combinedForSearch)

                    val candidates = coarseCandidates(combinedForSearch, gridRows, gridCols, topCandidatesPerScale)
                    val suppressionRadiusX = scaledGray.cols() / 2
                    val suppressionRadiusY = scaledGray.rows() / 2
                    val maxResultX = (combinedForSearch.cols() - 1).toDouble().coerceAtLeast(0.0)
                    val maxResultY = (combinedForSearch.rows() - 1).toDouble().coerceAtLeast(0.0)

                    val perCandidateResults = mutableListOf<WatermarkDetection>()
                    for (candidate in candidates) {
                        val refineRect = refineWindow(candidate, combinedForSearch.size(), scaledGray.size())
                        val subMat = Mat(combinedForSearch, refineRect)
                        val minMax = Core.minMaxLoc(subMat)
                        val location = Point(minMax.maxLoc.x + refineRect.x, minMax.maxLoc.y + refineRect.y)
                        val roiBrightness = localBrightness(brightnessMap, location, scaledGray.size())
                        val weights = if (roiBrightness < 80.0) WeightConfig.DARK else WeightConfig.BRIGHT
                        val score = combinedScore(
                            resultGray, colorAccumulation, resultGradient, location,
                            weights
                        )
                        if (score >= matchThreshold) {
                            perCandidateResults.add(
                                WatermarkDetection(
                                    offsetX = (location.x - roiRect.x).toFloat(),
                                    offsetY = (location.y - roiRect.y).toFloat(),
                                    score = score.toFloat()
                                )
                            )
                            suppressRegion(
                                combinedForSearch,
                                location,
                                suppressionRadiusX,
                                suppressionRadiusY,
                                maxResultX,
                                maxResultY
                            )
                        }
                        subMat.release()
                    }

                    perCandidateResults.sortByDescending { it.score }
                    for (d in perCandidateResults) {
                        if (detections.size >= maxResults) break
                        detections.add(d)
                    }

                    combinedForSearch.release()
                    grayZeroMean.release()
                    baseZeroMean.release()
                    resultGray.release()
                    colorAccumulation.release()
                    watermarkGrad.release()
                    resultGradient.release()
                } finally {
                    scaledGray.release()
                    scaledBgr.release()
                    scaledMask.release()
                    scaledGradient.release()
                }
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
            baseGradient.release()
            brightnessMap.release()
            watermarkGrayRoi.release()
            watermarkMaskRoi.release()
            watermarkBgrRoi.release()
            watermarkGradient.release()
        }
    }

    private fun computeGradientMagnitude(gray: Mat): Mat {
        val gradX = Mat()
        val gradY = Mat()
        val grad = Mat()
        Imgproc.Sobel(gray, gradX, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(gray, gradY, CvType.CV_32F, 0, 1, 3)
        Core.magnitude(gradX, gradY, grad)
        gradX.release()
        gradY.release()
        return grad
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

    private fun zeroMeanMasked(mat: Mat, mask: Mat): Mat {
        val floatMat = Mat()
        mat.convertTo(floatMat, CvType.CV_32F)
        val meanVal = Core.mean(floatMat, mask).`val`[0]
        Core.subtract(floatMat, Scalar(meanVal), floatMat, mask)
        Core.multiply(floatMat, Scalar(1.0 / 255.0), floatMat)
        return floatMat
    }

    private fun resizeWithMask(source: Mat, mask: Mat, out: Mat, outMask: Mat, scale: Double) {
        val size = org.opencv.core.Size(
            (source.cols() * scale).roundToInt().toDouble().coerceAtLeast(1.0),
            (source.rows() * scale).roundToInt().toDouble().coerceAtLeast(1.0)
        )
        Imgproc.resize(source, out, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
        if (!outMask.empty() || mask.rows() > 0) {
            Imgproc.resize(mask, outMask, size, 0.0, 0.0, Imgproc.INTER_NEAREST)
        }
    }

    private fun coarseCandidates(result: Mat, rows: Int, cols: Int, topN: Int): List<Point> {
        val candidates = mutableListOf<Pair<Double, Point>>()
        val cellWidth = result.cols() / cols
        val cellHeight = result.rows() / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x0 = c * cellWidth
                val y0 = r * cellHeight
                val x1 = if (c == cols - 1) result.cols() else (c + 1) * cellWidth
                val y1 = if (r == rows - 1) result.rows() else (r + 1) * cellHeight
                val sub = Mat(result, Rect(x0, y0, x1 - x0, y1 - y0))
                val minMax = Core.minMaxLoc(sub)
                candidates.add(minMax.maxVal to Point(minMax.maxLoc.x + x0, minMax.maxLoc.y + y0))
                sub.release()
            }
        }
        return candidates.sortedByDescending { it.first }.take(topN).map { it.second }
    }

    private fun refineWindow(candidate: Point, resultSize: org.opencv.core.Size, templSize: org.opencv.core.Size): Rect {
        val halfW = (templSize.width / 2).roundToInt()
        val halfH = (templSize.height / 2).roundToInt()
        val x0 = max(0.0, candidate.x - halfW).roundToInt()
        val y0 = max(0.0, candidate.y - halfH).roundToInt()
        val x1 = min(resultSize.width - 1, candidate.x + halfW).roundToInt()
        val y1 = min(resultSize.height - 1, candidate.y + halfH).roundToInt()
        return Rect(x0, y0, max(1, x1 - x0 + 1), max(1, y1 - y0 + 1))
    }

    private fun localBrightness(map: Mat, location: Point, templSize: org.opencv.core.Size): Double {
        val x0 = max(0.0, location.x - templSize.width / 4).roundToInt()
        val y0 = max(0.0, location.y - templSize.height / 4).roundToInt()
        val x1 = min(map.cols() - 1.0, location.x + templSize.width / 4).roundToInt()
        val y1 = min(map.rows() - 1.0, location.y + templSize.height / 4).roundToInt()
        val roi = Mat(map, Rect(x0, y0, max(1, x1 - x0 + 1), max(1, y1 - y0 + 1)))
        val mean = Core.mean(roi).`val`[0]
        roi.release()
        return mean
    }

    private enum class WeightConfig(val gray: Double, val color: Double, val gradient: Double) {
        BRIGHT(0.6, 0.4, 0.2),
        DARK(0.4, 0.2, 0.6)
    }

    private fun combinedScore(
        grayResult: Mat,
        colorResult: Mat,
        gradientResult: Mat,
        location: Point,
        weights: WeightConfig
    ): Double {
        val x = location.x.roundToInt()
        val y = location.y.roundToInt()
        val grayVal = grayResult.get(y, x)?.get(0) ?: 0.0
        val colorVal = colorResult.get(y, x)?.get(0) ?: 0.0
        val gradVal = gradientResult.get(y, x)?.get(0) ?: 0.0
        return grayVal * weights.gray + colorVal * weights.color + gradVal * weights.gradient
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
}
