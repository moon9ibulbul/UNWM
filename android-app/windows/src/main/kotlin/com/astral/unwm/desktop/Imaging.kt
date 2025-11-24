package com.astral.unwm.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

fun loadImage(file: File): BufferedImage? = runCatching { ImageIO.read(file) }.getOrNull()

fun BufferedImage.toImageBitmap(): ImageBitmap {
    val buffer = ByteArrayOutputStream()
    ImageIO.write(this, "png", buffer)
    val skiaImage = Image.makeFromEncoded(buffer.toByteArray())
    return skiaImage.asImageBitmap()
}

fun BufferedImage.toArgbArray(): IntArray {
    val argbImage = if (type == BufferedImage.TYPE_INT_ARGB) this else {
        val converted = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = converted.createGraphics()
        graphics.drawImage(this, 0, 0, null)
        graphics.dispose()
        converted
    }
    val pixels = IntArray(argbImage.width * argbImage.height)
    argbImage.getRGB(0, 0, argbImage.width, argbImage.height, pixels, 0, argbImage.width)
    return pixels
}

fun IntArray.toBufferedImage(width: Int, height: Int): BufferedImage {
    val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    output.setRGB(0, 0, width, height, this, 0, width)
    return output
}

fun BufferedImage.copyAsArgb(): BufferedImage = toArgbArray().toBufferedImage(width, height)

fun clampColor(value: Int): Int = value.coerceIn(0, 255)

fun rgba(red: Int, green: Int, blue: Int, alpha: Int): Int {
    return (alpha and 0xFF shl 24) or (red and 0xFF shl 16) or (green and 0xFF shl 8) or (blue and 0xFF)
}
