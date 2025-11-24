package com.astral.unwm.desktop

import nu.pattern.OpenCV
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import kotlin.math.max

fun initOpenCv() {
    runCatching { OpenCV.loadShared() }
        .recoverCatching { OpenCV.loadLocally() }
    runCatching { System.loadLibrary(Core.NATIVE_LIBRARY_NAME) }
}

fun BufferedImage.toMat(): Mat {
    val argbImage = if (type == BufferedImage.TYPE_4BYTE_ABGR) this else {
        val converted = BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
        val graphics = converted.createGraphics()
        graphics.drawImage(this, 0, 0, null)
        graphics.dispose()
        converted
    }
    val data = (argbImage.raster.dataBuffer as DataBufferByte).data
    val mat = Mat(height, width, CvType.CV_8UC4)
    mat.put(0, 0, data)
    return mat
}

fun Mat.toBufferedImage(): BufferedImage {
    val converted = Mat()
    Imgproc.cvtColor(this, converted, Imgproc.COLOR_BGRA2RGBA)
    val width = converted.cols()
    val height = converted.rows()
    val buffer = ByteArray(width * height * converted.channels())
    converted.get(0, 0, buffer)
    val image = BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
    image.raster.setDataElements(0, 0, width, height, buffer)
    converted.release()
    return image
}

fun Mat.resizeToMatch(targetWidth: Int, targetHeight: Int): Mat {
    val output = Mat()
    Imgproc.resize(this, output, Size(targetWidth.toDouble(), targetHeight.toDouble()))
    return output
}
