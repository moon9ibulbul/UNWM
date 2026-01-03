package com.astral.unwm

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

object ImageUtils {
    fun toBufferedImage(width: Int, height: Int, pixels: IntArray): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val raster = image.raster
        val dataBuffer = raster.dataBuffer as DataBufferInt
        System.arraycopy(pixels, 0, dataBuffer.data, 0, pixels.size)
        return image
    }

    fun getPixels(image: BufferedImage): IntArray {
        // Ensure image is INT_ARGB for direct array access optimization if possible
        val convertedImg = if (image.type != BufferedImage.TYPE_INT_ARGB) {
            val newImg = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            val g = newImg.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
            newImg
        } else {
            image
        }
        val dataBuffer = convertedImg.raster.dataBuffer as DataBufferInt
        return dataBuffer.data
    }
}
