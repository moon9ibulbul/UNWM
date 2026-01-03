package com.astral.unwm

import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

object OpenCVUtils {
    /**
     * Converts a BufferedImage to an OpenCV Mat in BGRA format (CV_8UC4).
     */
    fun bufferedImageToMat(image: BufferedImage): Mat {
        // Ensure image is formatted in a way we can extract bytes easily.
        // We want BGRA output for OpenCV compatibility.
        // TYPE_INT_ARGB: int is 0xAARRGGBB.
        // In Little Endian (x86), memory is [B, G, R, A].
        // So if we get the int array and interpret it as bytes, it should be B, G, R, A (which matches OpenCV BGRA).

        val standardizedImage = if (image.type != BufferedImage.TYPE_INT_ARGB) {
            val newImg = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            val g = newImg.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
            newImg
        } else {
            image
        }

        val dataBuffer = standardizedImage.raster.dataBuffer as DataBufferInt
        val intData = dataBuffer.data

        // OpenCV Java 'put' methods usually take byte arrays or float arrays.
        // We can't easily cast int[] to byte[] without copy.
        // But wait, Mat(rows, cols, CvType.CV_8UC4) expects bytes.
        // If we put int[], we need a Mat of CV_32SC1? No, we want channels.
        // It's better to manually copy ints to a byte array in BGRA order.

        // However, TYPE_4BYTE_ABGR has byte order [A, B, G, R].
        // If we use that:
        // [0]=A, [1]=B, [2]=G, [3]=R.
        // OpenCV BGRA expects: [0]=B, [1]=G, [2]=R, [3]=A.
        // So ABGR is basically a 1-byte rotation of BGRA.

        // Let's stick to explicitly creating a BGRA byte array to be safe and cross-platform.
        val width = standardizedImage.width
        val height = standardizedImage.height
        val mat = Mat(height, width, CvType.CV_8UC4)
        val byteData = ByteArray(width * height * 4)

        for (i in intData.indices) {
            val pixel = intData[i]
            // pixel is ARGB (0xAARRGGBB)
            val a = (pixel shr 24) and 0xFF
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val byteIdx = i * 4
            byteData[byteIdx] = b.toByte()
            byteData[byteIdx + 1] = g.toByte()
            byteData[byteIdx + 2] = r.toByte()
            byteData[byteIdx + 3] = a.toByte()
        }

        mat.put(0, 0, byteData)
        return mat
    }
}
