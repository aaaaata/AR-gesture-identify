package com.example.gestureservice

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ABI-independent recognizer for validating the SDK/IPC/app chain on an x86_64 XR emulator.
 * It targets an upright bare hand against a plain, contrasting background. A production model
 * can replace this class without changing the service or SDK contract.
 */
internal object DemoImageGestureRecognizer {
    fun recognize(source: Bitmap): AnalysisResult {
        val bitmap = source.scaledForAnalysis(MAX_ANALYSIS_EDGE)
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val background = estimateBackground(pixels, width, height)
            val rawMask = BooleanArray(pixels.size) { index ->
                isLikelyHandPixel(pixels[index], background)
            }
            val component = largestComponent(majorityFilter(rawMask, width, height), width, height)
                ?: return AnalysisResult("None", 0f, 0)
            classify(component, width, height)
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    private fun classify(component: Component, width: Int, height: Int): AnalysisResult {
        val boxWidth = component.maxX - component.minX + 1
        val boxHeight = component.maxY - component.minY + 1
        val imageCoverage = component.size.toFloat() / (width * height)
        if (imageCoverage < MIN_COMPONENT_COVERAGE) return AnalysisResult("None", 0f, 0)

        val upperEnd = component.minY + (boxHeight * 0.58f).toInt()
        val branchSamples = mutableListOf<Int>()
        for (y in component.minY..min(upperEnd, component.maxY)) {
            branchSamples += countRuns(
                component.mask,
                width,
                component.minX,
                component.maxX,
                y,
            )
        }
        val stableBranches = branchSamples
            .sorted()
            .let { samples -> samples[(samples.lastIndex * BRANCH_PERCENTILE).toInt()] }

        val aspectRatio = boxHeight.toFloat() / boxWidth
        val occupancy = component.size.toFloat() / (boxWidth * boxHeight)
        return when {
            stableBranches >= 4 -> AnalysisResult(
                "Open_Palm",
                (0.62f + 0.07f * min(stableBranches, 5)).coerceAtMost(0.94f),
                min(stableBranches, 5),
            )
            stableBranches in 2..3 && aspectRatio > 1.05f ->
                AnalysisResult("Victory", 0.78f, stableBranches)
            stableBranches == 1 && aspectRatio > 1.45f && occupancy < 0.56f ->
                AnalysisResult("Pointing_Up", 0.71f, 1)
            stableBranches <= 2 && occupancy > 0.52f && aspectRatio < 1.35f ->
                AnalysisResult("Closed_Fist", 0.72f, 0)
            else -> AnalysisResult("None", 0.35f, stableBranches)
        }
    }

    private fun countRuns(
        mask: BooleanArray,
        width: Int,
        startX: Int,
        endX: Int,
        y: Int,
    ): Int {
        val minimumRun = max(2, (endX - startX + 1) / 35)
        val minimumGap = max(3, (endX - startX + 1) / 32)
        var count = 0
        var runLength = 0
        var gapLength = 0
        for (x in startX..endX) {
            if (mask[y * width + x]) {
                runLength += gapLength + 1
                gapLength = 0
            } else if (runLength > 0) {
                gapLength++
                if (gapLength >= minimumGap) {
                    if (runLength >= minimumRun) count++
                    runLength = 0
                    gapLength = 0
                }
            }
        }
        if (runLength >= minimumRun) count++
        return count
    }

    private fun largestComponent(mask: BooleanArray, width: Int, height: Int): Component? {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var best: Component? = null

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            val indices = IntArray(mask.size)
            var size = 0
            var minX = width
            var maxX = 0
            var minY = height
            var maxY = 0

            while (head < tail) {
                val index = queue[head++]
                indices[size++] = index
                val x = index % width
                val y = index / width
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)

                fun enqueue(candidate: Int) {
                    if (candidate in mask.indices && mask[candidate] && !visited[candidate]) {
                        visited[candidate] = true
                        queue[tail++] = candidate
                    }
                }
                if (x > 0) enqueue(index - 1)
                if (x + 1 < width) enqueue(index + 1)
                if (y > 0) enqueue(index - width)
                if (y + 1 < height) enqueue(index + width)
            }

            if (best == null || size > best.size) {
                val componentMask = BooleanArray(mask.size)
                for (i in 0 until size) componentMask[indices[i]] = true
                best = Component(componentMask, size, minX, maxX, minY, maxY)
            }
        }
        return best
    }

    private fun majorityFilter(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val filtered = BooleanArray(mask.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var neighbors = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (mask[(y + dy) * width + x + dx]) neighbors++
                    }
                }
                filtered[y * width + x] = neighbors >= 5
            }
        }
        return filtered
    }

    private fun estimateBackground(pixels: IntArray, width: Int, height: Int): Rgb {
        val sampleEdge = max(2, min(width, height) / 12)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        fun sample(originX: Int, originY: Int) {
            for (y in originY until originY + sampleEdge) {
                for (x in originX until originX + sampleEdge) {
                    val color = pixels[y * width + x]
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                    count++
                }
            }
        }
        sample(0, 0)
        sample(width - sampleEdge, 0)
        sample(0, height - sampleEdge)
        sample(width - sampleEdge, height - sampleEdge)
        return Rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun isLikelyHandPixel(color: Int, background: Rgb): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val maxChannel = max(red, max(green, blue))
        val minChannel = min(red, min(green, blue))
        val rgbSkin = red > 70 && red > green * 0.92f && red > blue * 1.08f &&
            maxChannel - minChannel > 18
        val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
        val cb = 128f - 0.168736f * red - 0.331264f * green + 0.5f * blue
        val cr = 128f + 0.5f * red - 0.418688f * green - 0.081312f * blue
        val yCbCrSkin = luminance > 35f && cb in 72f..135f && cr in 128f..182f
        val distance = sqrt(
            ((red - background.red) * (red - background.red) +
                (green - background.green) * (green - background.green) +
                (blue - background.blue) * (blue - background.blue)).toFloat(),
        )
        return (rgbSkin || yCbCrSkin) && distance > BACKGROUND_DISTANCE
    }

    private fun Bitmap.scaledForAnalysis(maxEdge: Int): Bitmap {
        val largest = max(width, height)
        if (largest <= maxEdge) return this
        val scale = maxEdge.toFloat() / largest
        return Bitmap.createScaledBitmap(
            this,
            max(1, (width * scale).toInt()),
            max(1, (height * scale).toInt()),
            true,
        )
    }

    data class AnalysisResult(
        val label: String,
        val confidence: Float,
        val featureCount: Int,
    )

    private data class Rgb(val red: Int, val green: Int, val blue: Int)

    private data class Component(
        val mask: BooleanArray,
        val size: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
    )

    private const val MAX_ANALYSIS_EDGE = 192
    private const val BACKGROUND_DISTANCE = 42f
    private const val MIN_COMPONENT_COVERAGE = 0.025f
    private const val BRANCH_PERCENTILE = 0.88f
}
