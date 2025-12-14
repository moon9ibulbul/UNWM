package com.astral.unwm

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.CLAHE
import kotlin.math.max
import kotlin.math.min

private const val DEFAULT_MATCH_THRESHOLD = 0.9
private const val DEFAULT_ALPHA_THRESHOLD = 5.0
private const val MAX_DETECTIONS_PER_IMAGE = 10
private const val COARSE_GRID = 3
private const val TOP_CANDIDATES = 5
private const val SCALE_START = 0.85
private const val SCALE_END = 1.20
private const val SCALE_STEP = 0.05

/**
 * Detects watermark positions using OpenCV's template matching with masking.
 *
 * The detector performs a coarse grid search followed by localized refinement
 * with multi-scale, masked template matching across texture, gradient, and
 * high-pass domains. Local brightness guides preprocessing so dark backgrounds
 * receive targeted enhancement without relying on global brightness.
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
        val alphaChannel = Mat()
        val mask = Mat()
        val nonZero = Mat()
        var watermarkGrayRoi = Mat()
        var watermarkMaskRoi = Mat()

        return try {
            Utils.bitmapToMat(base, baseMat)
            Utils.bitmapToMat(watermark, watermarkMat)

            Imgproc.cvtColor(baseMat, baseGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(watermarkMat, watermarkGray, Imgproc.COLOR_RGBA2GRAY)

            Core.extractChannel(watermarkMat, alphaChannel, 3)
            Imgproc.threshold(alphaChannel, mask, alphaThreshold, 255.0, Imgproc.THRESH_BINARY)
            if (Core.countNonZero(mask) == 0) {
                Imgproc.threshold(watermarkGray, mask, 1.0, 255.0, Imgproc.THRESH_BINARY)
            }

            Core.findNonZero(mask, nonZero)
            if (nonZero.empty()) {
                return emptyList()
            }
            val roiRect: Rect = Imgproc.boundingRect(nonZero)
            watermarkGrayRoi = Mat(watermarkGray, roiRect).clone()
            watermarkMaskRoi = Mat(mask, roiRect).clone()

            val resultCols = baseGray.cols() - watermarkGrayRoi.cols() + 1
            val resultRows = baseGray.rows() - watermarkGrayRoi.rows() + 1
            if (resultCols <= 0 || resultRows <= 0) {
                return emptyList()
            }

            val candidates = coarseCandidates(baseGray, watermarkGrayRoi, watermarkMaskRoi)
            if (candidates.isEmpty()) {
                return emptyList()
            }

            val detections = refineCandidates(
                baseGray,
                watermarkGrayRoi,
                watermarkMaskRoi,
                roiRect,
                candidates,
                matchThreshold,
                maxResults
            )

            detections
        } finally {
            baseMat.release()
            watermarkMat.release()
            baseGray.release()
            watermarkGray.release()
            alphaChannel.release()
            mask.release()
            nonZero.release()
            watermarkGrayRoi.release()
            watermarkMaskRoi.release()
        }
    }

    private data class Candidate(val rect: Rect, val score: Double, val brightness: Double)
    private data class MatchResult(val score: Double, val location: Point)

    private fun sanitizeScore(score: Double, fallback: Double = Double.NEGATIVE_INFINITY): Double {
        return if (score.isFinite()) score else fallback
    }

    private fun coarseCandidates(baseGray: Mat, tplGray: Mat, tplMask: Mat): List<Candidate> {
        val gridWidth = baseGray.cols() / COARSE_GRID
        val gridHeight = baseGray.rows() / COARSE_GRID
        val candidates = mutableListOf<Candidate>()
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))

        for (row in 0 until COARSE_GRID) {
            for (col in 0 until COARSE_GRID) {
                val x = col * gridWidth
                val y = row * gridHeight
                val width = if (col == COARSE_GRID - 1) baseGray.cols() - x else gridWidth
                val height = if (row == COARSE_GRID - 1) baseGray.rows() - y else gridHeight
                if (width < tplGray.cols() || height < tplGray.rows()) {
                    continue
                }
                val roi = Rect(x, y, width, height)
                val roiMat = Mat(baseGray, roi)
                val brightness = Core.mean(roiMat).`val`[0]

                val preprocessed = Mat()
                roiMat.copyTo(preprocessed)
                if (brightness < 60.0) {
                    clahe.apply(roiMat, preprocessed)
                } else if (brightness > 190.0) {
                    Core.bitwise_not(roiMat, preprocessed)
                }

                val tempResult = Mat()
                Imgproc.matchTemplate(
                    preprocessed,
                    tplGray,
                    tempResult,
                    Imgproc.TM_CCORR_NORMED,
                    tplMask
                )
                val maxVal = Core.minMaxLoc(tempResult).maxVal
                candidates.add(Candidate(roi, maxVal, brightness))
                preprocessed.release()
                tempResult.release()
                roiMat.release()
            }
        }

        return candidates.sortedByDescending { it.score }.take(TOP_CANDIDATES)
    }

    private fun refineCandidates(
        baseGray: Mat,
        tplGray: Mat,
        tplMask: Mat,
        tplOffset: Rect,
        candidates: List<Candidate>,
        matchThreshold: Double,
        maxResults: Int
    ): List<WatermarkDetection> {
        val detections = mutableListOf<WatermarkDetection>()
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val scaleSteps = generateSequence(SCALE_START) { it + SCALE_STEP }
            .takeWhile { it <= SCALE_END + 1e-6 }
            .toList()

        for (candidate in candidates) {
            val expandedRect = expandRect(candidate.rect, tplGray.size(), baseGray)
            if (!isValidRect(expandedRect, baseGray)) continue

            val roiGray = Mat(baseGray, expandedRect)
            val roiBrightness = Core.mean(roiGray).`val`[0]

            val roiGrayProcessed = Mat()
            roiGray.copyTo(roiGrayProcessed)
            if (roiBrightness < 70.0) {
                clahe.apply(roiGray, roiGrayProcessed)
            }

            val normalizedRoi = zeroMeanNormalize(roiGrayProcessed)
            val invertedRoi = invertAndBalance(roiGrayProcessed, clahe)

            val roiHighPass = highPass(normalizedRoi)
            val roiGradient = gradientMagnitude(normalizedRoi)

            var bestTextureScore = Double.NEGATIVE_INFINITY
            var bestCombinedScore = Double.NEGATIVE_INFINITY
            var secondCombinedScore = Double.NEGATIVE_INFINITY
            var bestLocation: Point? = null

            for (scale in scaleSteps) {
                val scaledTplGray = Mat()
                val scaledTplMask = Mat()

                resizeTemplate(tplGray, scale, scaledTplGray)
                resizeTemplate(tplMask, scale, scaledTplMask, Imgproc.INTER_NEAREST)
                if (roiGrayProcessed.cols() < scaledTplGray.cols() || roiGrayProcessed.rows() < scaledTplGray.rows()) {
                    scaledTplGray.release()
                    scaledTplMask.release()
                    continue
                }

                val normalizedTpl = zeroMeanNormalize(scaledTplGray)
                val invertedTplRaw = invertMat(scaledTplGray)
                val invertedTpl = zeroMeanNormalize(invertedTplRaw)

                val scaledTplHighPass = highPass(normalizedTpl)
                val scaledTplGradient = gradientMagnitude(normalizedTpl)

                val textureMatch = matchScoreWithLocation(
                    normalizedRoi,
                    normalizedTpl,
                    scaledTplMask,
                    Imgproc.TM_CCORR_NORMED
                )
                val invertedMatch = matchScoreWithLocation(
                    invertedRoi,
                    invertedTpl,
                    scaledTplMask,
                    Imgproc.TM_CCORR_NORMED
                )

                val textureScore = sanitizeScore(textureMatch.score, 0.0)
                val invertedScore = sanitizeScore(invertedMatch.score, 0.0)
                val (dominantScore, dominantLocation) = if (textureScore >= invertedScore) {
                    textureScore to textureMatch.location
                } else {
                    invertedScore to invertedMatch.location
                }

                val highPassScore = matchScore(
                    roiHighPass,
                    scaledTplHighPass,
                    scaledTplMask,
                    Imgproc.TM_CCORR_NORMED
                )

                val gradientScore = matchScore(
                    roiGradient,
                    scaledTplGradient,
                    scaledTplMask,
                    Imgproc.TM_CCORR_NORMED
                )

                val combinedScore = dominantScore * 0.45 + gradientScore * 0.35 + highPassScore * 0.20

                val currentLocation = dominantLocation

                if (dominantScore > bestTextureScore || (bestLocation == null && currentLocation != null)) {
                    bestTextureScore = dominantScore
                    bestLocation = currentLocation
                }

                if (combinedScore > bestCombinedScore) {
                    secondCombinedScore = bestCombinedScore
                    bestCombinedScore = combinedScore
                    if (currentLocation != null) {
                        bestLocation = currentLocation
                    }
                } else if (combinedScore > secondCombinedScore) {
                    secondCombinedScore = combinedScore
                }

                scaledTplGray.release()
                scaledTplMask.release()
                normalizedTpl.release()
                invertedTpl.release()
                scaledTplHighPass.release()
                scaledTplGradient.release()
                invertedTplRaw.release()
            }

            val safeBestTexture = sanitizeScore(bestTextureScore, 0.0)
            val safeBestCombined = when {
                bestCombinedScore.isFinite() -> bestCombinedScore
                safeBestTexture.isFinite() -> safeBestTexture
                else -> 0.0
            }
            val safeSecondCombined = sanitizeScore(secondCombinedScore)
            val confidenceGap = safeBestCombined - safeSecondCombined
            if (bestLocation != null) {
                val accepted = (safeBestTexture >= matchThreshold || safeBestCombined >= matchThreshold) &&
                    confidenceGap >= 0.02

                if (accepted) {
                    val absoluteX = bestLocation!!.x + expandedRect.x - tplOffset.x
                    val absoluteY = bestLocation!!.y + expandedRect.y - tplOffset.y
                    detections.add(
                        WatermarkDetection(
                            offsetX = absoluteX.toFloat(),
                            offsetY = absoluteY.toFloat(),
                            score = safeBestCombined.toFloat()
                        )
                    )

                    if (bestTextureScore >= matchThreshold && detections.size >= maxResults) {
                        break
                    }
                }
            }

            roiGrayProcessed.release()
            normalizedRoi.release()
            invertedRoi.release()
            roiHighPass.release()
            roiGradient.release()
            roiGray.release()
        }

        return detections.sortedByDescending { it.score }.take(maxResults)
    }

    private fun invertMat(src: Mat): Mat {
        val inverted = Mat()
        Core.bitwise_not(src, inverted)
        return inverted
    }

    private fun invertAndBalance(src: Mat, clahe: CLAHE): Mat {
        val inverted = invertMat(src)
        val balanced = Mat()
        clahe.apply(inverted, balanced)
        inverted.release()
        return balanced
    }

    private fun zeroMeanNormalize(src: Mat): Mat {
        val floatMat = Mat()
        src.convertTo(floatMat, CvType.CV_32F)
        val mean = Core.mean(floatMat).`val`[0]
        Core.subtract(floatMat, Scalar(mean), floatMat)
        val normalized = Mat()
        Core.normalize(floatMat, normalized, 0.0, 255.0, Core.NORM_MINMAX)
        val result = Mat()
        normalized.convertTo(result, CvType.CV_8U)
        floatMat.release()
        normalized.release()
        return result
    }

    private fun highPass(src: Mat): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 3.0)
        val highPass = Mat()
        Core.addWeighted(src, 1.5, blurred, -0.5, 0.0, highPass)
        blurred.release()
        return highPass
    }

    private fun gradientMagnitude(src: Mat): Mat {
        val gradX = Mat()
        val gradY = Mat()
        Imgproc.Sobel(src, gradX, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(src, gradY, CvType.CV_32F, 0, 1, 3)
        val magnitude = Mat()
        Core.magnitude(gradX, gradY, magnitude)
        gradX.release()
        gradY.release()
        return magnitude
    }

    private fun resizeTemplate(template: Mat, scale: Double, dst: Mat, interpolation: Int = Imgproc.INTER_LINEAR) {
        Imgproc.resize(
            template,
            dst,
            Size(template.cols() * scale, template.rows() * scale),
            0.0,
            0.0,
            interpolation
        )
    }

    private fun matchScore(
        image: Mat,
        template: Mat,
        mask: Mat,
        method: Int
    ): Double {
        val result = Mat()
        Imgproc.matchTemplate(image, template, result, method, mask)
        val maxVal = Core.minMaxLoc(result).maxVal
        result.release()
        return sanitizeScore(maxVal, 0.0)
    }

    private fun matchScoreWithLocation(
        image: Mat,
        template: Mat,
        mask: Mat,
        method: Int
    ): MatchResult {
        val result = Mat()
        Imgproc.matchTemplate(image, template, result, method, mask)
        val minMaxLoc = Core.minMaxLoc(result)
        result.release()
        return MatchResult(sanitizeScore(minMaxLoc.maxVal, 0.0), minMaxLoc.maxLoc)
    }

    private fun expandRect(rect: Rect, tplSize: Size, image: Mat): Rect {
        val expandX = (tplSize.width * 0.5).toInt()
        val expandY = (tplSize.height * 0.5).toInt()
        val x = max(0, rect.x - expandX)
        val y = max(0, rect.y - expandY)
        val maxWidth = image.cols() - x
        val maxHeight = image.rows() - y
        val width = min(rect.width + expandX * 2, maxWidth)
        val height = min(rect.height + expandY * 2, maxHeight)
        return Rect(x, y, width, height)
    }

    private fun isValidRect(rect: Rect, image: Mat): Boolean {
        return rect.x >= 0 && rect.y >= 0 &&
            rect.x + rect.width <= image.cols() &&
            rect.y + rect.height <= image.rows()
    }
}
