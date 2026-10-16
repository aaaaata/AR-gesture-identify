package com.example.gestureservice

import android.graphics.Bitmap
import com.rayneo.xr.sdk.base.XRImage
import java.nio.ByteBuffer
import kotlin.math.max

/** Process-owned copy of a vendor callback frame; XRImage buffers may be reused after callback. */
internal class Yuv420Frame private constructor(
    private val width: Int,
    private val height: Int,
    val timestampNanos: Long,
    private val planes: Array<ByteArray>,
    private val rowStrides: IntArray,
    private val pixelStrides: IntArray,
) {
    fun toAnalysisBitmap(rotationDegrees: Int, maxEdge: Int): Bitmap {
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val orientedWidth = if (rotation == 90 || rotation == 270) height else width
        val orientedHeight = if (rotation == 90 || rotation == 270) width else height
        val scale = minOf(1f, maxEdge.toFloat() / max(orientedWidth, orientedHeight))
        val outputWidth = max(1, (orientedWidth * scale).toInt())
        val outputHeight = max(1, (orientedHeight * scale).toInt())
        val colors = IntArray(outputWidth * outputHeight)

        for (outputY in 0 until outputHeight) {
            val orientedY = outputY * orientedHeight / outputHeight
            for (outputX in 0 until outputWidth) {
                val orientedX = outputX * orientedWidth / outputWidth
                val (sourceX, sourceY) = when (rotation) {
                    90 -> orientedY to (height - 1 - orientedX)
                    180 -> (width - 1 - orientedX) to (height - 1 - orientedY)
                    270 -> (width - 1 - orientedY) to orientedX
                    else -> orientedX to orientedY
                }
                colors[outputY * outputWidth + outputX] = pixelAt(sourceX, sourceY)
            }
        }
        return Bitmap.createBitmap(colors, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
    }

    fun layoutSummary(): String =
        "planes=${planes.size}, row=${rowStrides.joinToString()}, pixel=${pixelStrides.joinToString()}"

    private fun pixelAt(x: Int, y: Int): Int {
        val yValue = planes[0].unsignedAt(y * rowStrides[0] + x * pixelStrides[0])
        val chromaX = x / 2
        val chromaY = y / 2
        val uValue = planes[1].unsignedAt(
            chromaY * rowStrides[1] + chromaX * pixelStrides[1],
        )
        val vValue = planes[2].unsignedAt(
            chromaY * rowStrides[2] + chromaX * pixelStrides[2],
        )

        val luminance = max(0, yValue - 16)
        val u = uValue - 128
        val v = vValue - 128
        val red = ((298 * luminance + 409 * v + 128) shr 8).coerceIn(0, 255)
        val green = ((298 * luminance - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
        val blue = ((298 * luminance + 516 * u + 128) shr 8).coerceIn(0, 255)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun ByteArray.unsignedAt(index: Int): Int =
        (getOrElse(index) { 0 }.toInt() and 0xFF)

    companion object {
        fun copyFrom(
            image: XRImage,
            timestampNanos: Long,
            callbackWidth: Int,
            callbackHeight: Int,
        ): Yuv420Frame {
            val sourcePlanes = image.planes ?: error("XRImage has no planes")
            val sourceStrides = image.strides ?: error("XRImage has no strides")
            require(sourcePlanes.size >= 3 && sourceStrides.size >= 3) {
                "Expected three YUV420 planes, got ${sourcePlanes.size}"
            }
            val width = image.width.takeIf { it > 0 } ?: callbackWidth
            val height = image.height.takeIf { it > 0 } ?: callbackHeight
            val lengths = image.lengths
            val copiedPlanes = Array(3) { index ->
                sourcePlanes[index].copyBytes(lengths?.getOrNull(index))
            }
            val rowStrides = IntArray(3) { index ->
                sourceStrides[index].takeIf { it > 0 }
                    ?: if (index == 0) width else (width + 1) / 2
            }
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            val pixelStrides = intArrayOf(
                1,
                inferChromaPixelStride(copiedPlanes[1].size, rowStrides[1], chromaWidth, chromaHeight),
                inferChromaPixelStride(copiedPlanes[2].size, rowStrides[2], chromaWidth, chromaHeight),
            )
            return Yuv420Frame(
                width = width,
                height = height,
                timestampNanos = timestampNanos,
                planes = copiedPlanes,
                rowStrides = rowStrides,
                pixelStrides = pixelStrides,
            )
        }

        private fun ByteBuffer.copyBytes(declaredLength: Int?): ByteArray {
            val length = minOf(
                capacity(),
                declaredLength?.takeIf { it > 0 } ?: limit(),
            )
            val result = ByteArray(length)
            val copy = duplicate().apply {
                position(0)
                limit(length)
            }
            copy.get(result)
            return result
        }

        private fun inferChromaPixelStride(
            length: Int,
            rowStride: Int,
            chromaWidth: Int,
            chromaHeight: Int,
        ): Int {
            if (chromaWidth <= 1 || chromaHeight <= 0) return 1
            val bytesInLastRow = length - rowStride * (chromaHeight - 1)
            return if (bytesInLastRow >= (chromaWidth - 1) * 2 + 1) 2 else 1
        }
    }
}
