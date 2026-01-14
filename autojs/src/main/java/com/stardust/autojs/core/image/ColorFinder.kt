@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.stardust.autojs.core.image

import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import com.stardust.autojs.core.opencv.Mat
import com.stardust.autojs.core.opencv.MatOfPoint
import com.stardust.autojs.core.opencv.OpenCVHelper
import com.stardust.util.ScreenMetrics
import org.opencv.core.Core
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ColorFinder.kt (optimized scan-based, no sorting)
 *
 * - Keeps all original public APIs from ColorFinder.java (used elsewhere).
 * - JS replacement APIs:
 *    - cmpColorEx(colorGroup, similarity)
 *    - findMultiColor(...)
 *    - findMultiColorEx(...)
 *
 * MultiColor:
 * - OpenCV inRange -> mask bytes
 * - Scan mask bytes in given direction (NO findNonZero list, NO sorting)
 * - Final check uses WeightedRGBDistanceDetector (rgb+), without ImageWrapper.pixel()
 *
 * Direction (scan order):
 *  1: L->R, T->B
 *  2: R->L, T->B
 *  3: L->R, B->T
 *  4: R->L, B->T
 */
@RequiresApi(api = Build.VERSION_CODES.KITKAT)
class ColorFinder(private val mScreenMetrics: ScreenMetrics) {

    private val AUTO_ANCHOR_TRIGGER_COUNT = 10_000
    private val AUTO_ANCHOR_MAX_ALT = 2

    // How many positive colors get hard masks (negatives always get hard masks)
    private val HARD_MASK_POSITIVE_TOP_K = 3

    /**
     * Cross-call detector LRU: reduce allocations across many cmp/find calls.
     * Key = (color << 8) | diff
     */
    private val detectorCacheLru = object : LinkedHashMap<Long, ColorDetector>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ColorDetector>?): Boolean = size > 256
    }

    private fun detectorRgbPlus(color: Int, diff: Int): ColorDetector {
        val key = (color.toLong() shl 8) or (diff.toLong() and 0xFF)
        synchronized(detectorCacheLru) {
            detectorCacheLru[key]?.let { return it }
            val d = ColorDetector.WeightedRGBDistanceDetector(color, diff)
            detectorCacheLru[key] = d
            return d
        }
    }

    /* =========================================================================================
     * Original public APIs (kept)
     * ========================================================================================= */

    fun findColorEquals(imageWrapper: ImageWrapper, color: Int): Point? =
        findColorEquals(imageWrapper, color, null)

    fun findColorEquals(imageWrapper: ImageWrapper, color: Int, region: Rect?): Point? =
        findColor(imageWrapper, color, 0, region)

    fun findColor(imageWrapper: ImageWrapper, color: Int, threshold: Int): Point? =
        findColor(imageWrapper, color, threshold, null)

    fun findColor(image: ImageWrapper, color: Int, threshold: Int, rect: Rect?): Point? {
        val matOfPoint = findColorInner(image, color, threshold, rect) ?: return null
        val point = matOfPoint.toArray()[0]

        if (rect != null) {
            // Preserve legacy behavior: scaleX used for y too (as original Java)
            point.x = mScreenMetrics.scaleX((point.x + rect.x).toInt()).toDouble()
            point.y = mScreenMetrics.scaleX((point.y + rect.y).toInt()).toDouble()
        }

        OpenCVHelper.release(matOfPoint)
        return point
    }

    fun findAllPointsForColor(image: ImageWrapper, color: Int, threshold: Int, rect: Rect?): Array<Point> {
        val matOfPoint = findColorInner(image, color, threshold, rect) ?: return emptyArray()
        val points = matOfPoint.toArray()
        OpenCVHelper.release(matOfPoint)

        if (rect != null) {
            // Preserve legacy behavior: scaleX used for y too
            for (i in points.indices) {
                points[i].x = mScreenMetrics.scaleX((points[i].x + rect.x).toInt()).toDouble()
                points[i].y = mScreenMetrics.scaleX((points[i].y + rect.y).toInt()).toDouble()
            }
        }
        return points
    }

    fun findMultiColors(image: ImageWrapper, firstColor: Int, threshold: Int, rect: Rect?, points: IntArray): Point? {
        val firstPoints = findAllPointsForColor(image, firstColor, threshold, rect)
        for (p in firstPoints) {
            if (checksPath(image, p, threshold, rect, points)) return p
        }
        return null
    }

    private fun checksPath(image: ImageWrapper, startingPoint: Point, threshold: Int, rect: Rect?, points: IntArray): Boolean {
        var i = 0
        while (i < points.size) {
            val x = points[i]
            val y = points[i + 1]
            val color = points[i + 2]
            val detector: ColorDetector = ColorDetector.DifferenceDetector(color, threshold)

            val px = x + startingPoint.x.toInt()
            val py = y + startingPoint.y.toInt()

            if (px >= image.getWidth() || py >= image.getHeight() || px < 0 || py < 0) return false

            val c = image.pixel(px, py) // legacy path (kept)
            if (!detector.detectsColor(Color.red(c), Color.green(c), Color.blue(c))) return false

            i += 3
        }
        return true
    }

    private fun findColorInner(image: ImageWrapper, color: Int, threshold: Int, rect: Rect?): MatOfPoint? {
        val bi = Mat()

        val lowerBound = Scalar(
            (Color.red(color) - threshold).toDouble(),
            (Color.green(color) - threshold).toDouble(),
            (Color.blue(color) - threshold).toDouble(),
            255.0
        )
        val upperBound = Scalar(
            (Color.red(color) + threshold).toDouble(),
            (Color.green(color) + threshold).toDouble(),
            (Color.blue(color) + threshold).toDouble(),
            255.0
        )

        if (rect != null) {
            val m = Mat(image.getMat(), rect)
            Core.inRange(m, lowerBound, upperBound, bi)
            OpenCVHelper.release(m)
        } else {
            Core.inRange(image.getMat(), lowerBound, upperBound, bi)
        }

        val nonZeroPos = Mat()
        Core.findNonZero(bi, nonZeroPos)

        val result =
            if (nonZeroPos.rows() == 0 || nonZeroPos.cols() == 0) null
            else OpenCVHelper.newMatOfPoint(nonZeroPos)

        OpenCVHelper.release(bi)
        OpenCVHelper.release(nonZeroPos)
        return result
    }

    /* =========================================================================================
     * New APIs (JS replacements)
     * ========================================================================================= */

    /**
    * CmpColorEx(对比颜色组, 相似度)
    *
    */
    fun cmpColorEx(
        image: ImageWrapper,
        colorGroup: String,
        similarity: Double
    ): Boolean {
        if (colorGroup.isBlank()) return false

        val imgW = image.getWidth()
        val imgH = image.getHeight()
        val rgba = image.getRgbaBytes()
        val diffSoft = similarityToDiff(similarity)

        // per-call detector cache
        val localDet = HashMap<Int, ColorDetector>(8)

        val parts = colorGroup.split(',')
        for (raw in parts) {
            val t = raw.trim()
            if (t.isEmpty()) continue

            val seg = t.split('|')
            if (seg.size < 3) return false

            val x = seg[0].trim().toIntOrNull() ?: return false
            val y = seg[1].trim().toIntOrNull() ?: return false
            if (x !in 0 until imgW || y !in 0 until imgH) return false

            val color = parseBBGGRR(seg[2].trim())
            val negative = seg.size > 3

            val det = localDet.getOrPut(color) { detectorRgbPlus(color, diffSoft) }

            val r = readR(rgba, imgW, x, y)
            val g = readG(rgba, imgW, x, y)
            val b = readB(rgba, imgW, x, y)
            val matched = det.detectsColor(r, g, b)

            if (negative) {
                if (matched) return false
            } else {
                if (!matched) return false
            }
        }

        return true
    }

    /**
    * CmpColorExW(x, y, c1, c2, similarity)
    */
    fun cmpColorExW(
        image: ImageWrapper,
        x: Int,
        y: Int,
        c1: String,
        c2: String,
        similarity: Double
    ): Boolean {
        if (c2.isBlank()) return false

        val imgW = image.getWidth()
        val imgH = image.getHeight()
        if (x !in 0 until imgW || y !in 0 until imgH) return false

        val rgba = image.getRgbaBytes()
        val diffSoft = similarityToDiff(similarity)

        val localDet = HashMap<Int, ColorDetector>(8)

        // optional base check
        if (c1.isNotBlank()) {
            val baseColor = parseBBGGRR(c1)
            val det = localDet.getOrPut(baseColor) { detectorRgbPlus(baseColor, diffSoft) }
            val r = readR(rgba, imgW, x, y)
            val g = readG(rgba, imgW, x, y)
            val b = readB(rgba, imgW, x, y)
            if (!det.detectsColor(r, g, b)) return false
        }

        val parts = c2.split(',')
        for (raw in parts) {
            val t = raw.trim()
            if (t.isEmpty()) continue

            val seg = t.split('|')
            if (seg.size < 3) return false

            val dx = seg[0].trim().toIntOrNull() ?: return false
            val dy = seg[1].trim().toIntOrNull() ?: return false
            val px = x + dx
            val py = y + dy
            if (px !in 0 until imgW || py !in 0 until imgH) return false

            val color = parseBBGGRR(seg[2].trim())
            val negative = seg.size > 3

            val det = localDet.getOrPut(color) { detectorRgbPlus(color, diffSoft) }

            val r = readR(rgba, imgW, px, py)
            val g = readG(rgba, imgW, px, py)
            val b = readB(rgba, imgW, px, py)
            val matched = det.detectsColor(r, g, b)

            if (negative) {
                if (matched) return false
            } else {
                if (!matched) return false
            }
        }

        return true
    }

    fun findMultiColor(
        image: ImageWrapper,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        firstColorBBGGRR: String,
        offsetGroup: String,
        direction: Int,
        similarity: Double
    ): Point? {
        val firstColor = parseBBGGRR(firstColorBBGGRR)
        val offsets = parseOffsetGroup(offsetGroup) ?: return null
        return findMultiColorInternal(image, x1, y1, x2, y2, firstColor, offsets, direction, similarity)
    }

    fun findMultiColorEx(
        image: ImageWrapper,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        c2: String,
        direction: Int,
        similarity: Double
    ): Point? {
        val all = parseAbsoluteGroup(c2) ?: return null
        if (all.isEmpty()) return null
        val anchor = all[0]
        val firstColor = anchor.color

        val offsets = ArrayList<OffsetColor>(all.size - 1)
        for (i in 1 until all.size) {
            val p = all[i]
            offsets.add(
                OffsetColor(
                    dx = p.x - anchor.x,
                    dy = p.y - anchor.y,
                    color = p.color,
                    negative = p.negative
                )
            )
        }
        return findMultiColorInternal(image, x1, y1, x2, y2, firstColor, offsets, direction, similarity)
    }

    /* =========================================================================================
     * Core (returns FIRST COLOR absolute position in image) - SCAN MODE (no sorting)
     * ========================================================================================= */

    private fun findMultiColorInternal(
        image: ImageWrapper,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        firstColor: Int,
        offsetsInput: List<OffsetColor>,
        direction: Int,
        similarity: Double
    ): Point? {
        val maxX = image.getWidth()
        val maxY = image.getHeight()

        val firstRegion = buildSafeRegion(x1, y1, x2, y2, maxX, maxY) ?: return null
        if (firstRegion.width <= 0 || firstRegion.height <= 0) return null

        val diffSoft = similarityToDiff(similarity)
        val diffHard = (diffSoft * 0.70).roundToInt().coerceAtLeast(0)

        // Count candidates for firstColor in original region (cheap)
        val firstMask = buildInRangeMask(image, firstRegion, firstColor, diffHard)
        val firstCount = try { Core.countNonZero(firstMask) } finally { OpenCVHelper.release(firstMask) }

        val plan = if (firstCount > AUTO_ANCHOR_TRIGGER_COUNT) {
            chooseBestAnchorPlanTriggered(image, firstRegion, firstColor, offsetsInput, diffHard, firstCount)
        } else {
            AnchorPlan(
                anchorColor = firstColor,
                anchorDx = 0,
                anchorDy = 0,
                anchorRegion = firstRegion,
                offsetsForAnchor = offsetsInput
            )
        }

        return matchWithPlanScanReturningFirstAbs(
            image = image,
            anchorRegion = plan.anchorRegion,
            anchorColor = plan.anchorColor,
            anchorDx = plan.anchorDx,
            anchorDy = plan.anchorDy,
            offsets = plan.offsetsForAnchor,
            direction = direction,
            diffHard = diffHard,
            diffSoft = diffSoft
        )
    }

    private fun chooseBestAnchorPlanTriggered(
        image: ImageWrapper,
        firstRegion: Rect,
        firstColor: Int,
        offsetsInput: List<OffsetColor>,
        diffHard: Int,
        firstCountBaseline: Int
    ): AnchorPlan {
        val positiveOffsets = offsetsInput.filter { !it.negative }

        val altAnchors = positiveOffsets
            .sortedByDescending { abs(it.dx) + abs(it.dy) }
            .distinctBy { it.color to (it.dx to it.dy) }
            .take(AUTO_ANCHOR_MAX_ALT)

        var best = AnchorPlan(
            anchorColor = firstColor,
            anchorDx = 0,
            anchorDy = 0,
            anchorRegion = firstRegion,
            offsetsForAnchor = offsetsInput
        )
        var bestCount = firstCountBaseline // ✅ no duplicate baseline scan

        for (alt in altAnchors) {
            val shifted = shiftRegion(firstRegion, alt.dx, alt.dy, image.getWidth(), image.getHeight()) ?: continue

            val m = buildInRangeMask(image, shifted, alt.color, diffHard)
            val c = try { Core.countNonZero(m) } finally { OpenCVHelper.release(m) }

            if (c < bestCount) {
                bestCount = c
                val rebased = rebaseOffsetsFromFirstToAnchor(offsetsInput, alt.dx, alt.dy)
                val withFirst = ensureFirstColorAsOffset(rebased, firstColor, -alt.dx, -alt.dy)

                best = AnchorPlan(
                    anchorColor = alt.color,
                    anchorDx = alt.dx,
                    anchorDy = alt.dy,
                    anchorRegion = shifted,
                    offsetsForAnchor = withFirst
                )
            }
        }
        return best
    }

    private fun matchWithPlanScanReturningFirstAbs(
        image: ImageWrapper,
        anchorRegion: Rect,
        anchorColor: Int,
        anchorDx: Int,
        anchorDy: Int,
        offsets: List<OffsetColor>,
        direction: Int,
        diffHard: Int,
        diffSoft: Int
    ): Point? {
        val roiW = anchorRegion.width
        val roiH = anchorRegion.height

        // ✅ Use full RGBA (cached by ImageWrapper in your case)
        val imgW = image.getWidth()
        val imgH = image.getHeight()
        val fullRgba = image.getRgbaBytes()

        // local detector cache (avoid repeated synchronized LRU access in hot path)
        val localDet = HashMap<Int, ColorDetector>(16)

        // Split offsets once (avoid filter allocations each check)
        val negatives = ArrayList<OffsetColor>(4)
        val positives = ArrayList<OffsetColor>(8)
        for (o in offsets) {
            if (o.negative) negatives.add(o) else positives.add(o)
        }

        // Choose which colors get hard masks:
        // - all negative colors
        // - top K positive offsets by |dx|+|dy|
        val hardColors = LinkedHashSet<Int>()
        for (n in negatives) hardColors.add(n.color)

        if (positives.isNotEmpty() && HARD_MASK_POSITIVE_TOP_K > 0) {
            positives
                .asSequence()
                .sortedByDescending { abs(it.dx) + abs(it.dy) }
                .map { it.color }
                .distinct()
                .take(HARD_MASK_POSITIVE_TOP_K)
                .forEach { hardColors.add(it) }
        }

        // anchor mask -> bytes (ROI size)
        val anchorMask = buildInRangeMask(image, anchorRegion, anchorColor, diffHard)
        val anchorBytes = ByteArrayPool.obtain(roiW * roiH)
        try {
            anchorMask.get(0, 0, anchorBytes)
        } finally {
            OpenCVHelper.release(anchorMask)
        }

        // hard masks -> bytes (ROI size, only selected colors)
        val maskBytesCache = HashMap<Int, ByteArray>(hardColors.size)
        try {
            for (c in hardColors) {
                val m = buildInRangeMask(image, anchorRegion, c, diffHard)
                val b = ByteArrayPool.obtain(roiW * roiH)
                try {
                    m.get(0, 0, b)
                    maskBytesCache[c] = b
                } finally {
                    OpenCVHelper.release(m)
                }
            }

            // Determine scan order by direction (your existing mapping)
            val sp = scanParams(roiW, roiH, direction)

            var y = sp.yStart
            while (y != sp.yEnd) {
                var x = sp.xStart
                while (x != sp.xEnd) {
                    val idx = y * roiW + x
                    if ((anchorBytes[idx].toInt() and 0xFF) != 0) {
                        if (checkOffsetsAt(
                                baseX = x,
                                baseY = y,
                                roiW = roiW,
                                roiH = roiH,
                                regionX = anchorRegion.x,
                                regionY = anchorRegion.y,
                                imgW = imgW,
                                imgH = imgH,
                                fullRgba = fullRgba,
                                negatives = negatives,
                                positives = positives,
                                hardMaskBytes = maskBytesCache,
                                localDet = localDet,
                                diffSoft = diffSoft
                            )
                        ) {
                            val anchorAbsX = anchorRegion.x + x
                            val anchorAbsY = anchorRegion.y + y
                            val firstAbsX = anchorAbsX - anchorDx
                            val firstAbsY = anchorAbsY - anchorDy
                            if (firstAbsX in 0 until imgW && firstAbsY in 0 until imgH) {
                                return Point(firstAbsX.toDouble(), firstAbsY.toDouble())
                            }
                        }
                    }
                    x += sp.xStep
                }
                y += sp.yStep
            }

            return null
        } finally {
            // recycle big buffers
            ByteArrayPool.recycle(anchorBytes)
            for (b in maskBytesCache.values) {
                ByteArrayPool.recycle(b)
            }
            maskBytesCache.clear()
        }
    }


    /**
     * Direction mapping:
     *  0: L->R, T->B
     *  1: R->L, T->B
     *  2: L->R, B->T
     *  3: R->L, B->T
     *  others: treated as 0
     */
    private data class ScanParams(
        val xStart: Int,
        val xEnd: Int,
        val xStep: Int,
        val yStart: Int,
        val yEnd: Int,
        val yStep: Int
    )

    private fun scanParams(w: Int, h: Int, direction: Int): ScanParams {
        return when (direction) {
            2 -> ScanParams(
                xStart = w - 1, xEnd = -1, xStep = -1,
                yStart = 0, yEnd = h, yStep = 1
            )
            3 -> ScanParams(
                xStart = 0, xEnd = w, xStep = 1,
                yStart = h - 1, yEnd = -1, yStep = -1
            )
            4 -> ScanParams(
                xStart = w - 1, xEnd = -1, xStep = -1,
                yStart = h - 1, yEnd = -1, yStep = -1
            )
            else -> ScanParams(
                xStart = 0, xEnd = w, xStep = 1,
                yStart = 0, yEnd = h, yStep = 1
            )
        }
    }

    private fun checkOffsetsAt(
        baseX: Int,
        baseY: Int,
        roiW: Int,
        roiH: Int,
        regionX: Int,
        regionY: Int,
        imgW: Int,
        imgH: Int,
        fullRgba: ByteArray,
        negatives: List<OffsetColor>,
        positives: List<OffsetColor>,
        hardMaskBytes: Map<Int, ByteArray>,
        localDet: HashMap<Int, ColorDetector>,
        diffSoft: Int
    ): Boolean {

        fun inRoi(rx: Int, ry: Int): Boolean = (rx in 0 until roiW) && (ry in 0 until roiH)

        // negatives first
        for (o in negatives) {
            val rx = baseX + o.dx
            val ry = baseY + o.dy

            val ax = regionX + rx
            val ay = regionY + ry
            if (ax !in 0 until imgW || ay !in 0 until imgH) return false

            val mask = hardMaskBytes[o.color]
            if (mask != null && inRoi(rx, ry)) {
                val idx = ry * roiW + rx
                if ((mask[idx].toInt() and 0xFF) != 0) return false
            } else {
                val det = localDet.getOrPut(o.color) { detectorRgbPlus(o.color, diffSoft) }
                val r = readR(fullRgba, imgW, ax, ay)
                val g = readG(fullRgba, imgW, ax, ay)
                val b = readB(fullRgba, imgW, ax, ay)
                if (det.detectsColor(r, g, b)) return false
            }
        }

        // positives
        for (o in positives) {
            val rx = baseX + o.dx
            val ry = baseY + o.dy

            val ax = regionX + rx
            val ay = regionY + ry
            if (ax !in 0 until imgW || ay !in 0 until imgH) return false

            val mask = hardMaskBytes[o.color]
            if (mask != null && inRoi(rx, ry)) {
                val idx = ry * roiW + rx
                if ((mask[idx].toInt() and 0xFF) == 0) return false
                // mask 过了仍建议继续做 soft 校验（你现在就是这么做的）
            }

            val det = localDet.getOrPut(o.color) { detectorRgbPlus(o.color, diffSoft) }
            val r = readR(fullRgba, imgW, ax, ay)
            val g = readG(fullRgba, imgW, ax, ay)
            val b = readB(fullRgba, imgW, ax, ay)
            if (!det.detectsColor(r, g, b)) return false
        }

        return true
    }

    private fun buildInRangeMask(image: ImageWrapper, region: Rect, color: Int, threshold: Int): Mat {
        val bi = Mat()
        val lower = Scalar(
            (Color.red(color) - threshold).toDouble(),
            (Color.green(color) - threshold).toDouble(),
            (Color.blue(color) - threshold).toDouble(),
            255.0
        )
        val upper = Scalar(
            (Color.red(color) + threshold).toDouble(),
            (Color.green(color) + threshold).toDouble(),
            (Color.blue(color) + threshold).toDouble(),
            255.0
        )
        val roi = Mat(image.getMat(), region)
        try {
            Core.inRange(roi, lower, upper, bi)
        } finally {
            OpenCVHelper.release(roi)
        }
        return bi
    }

    private data class AnchorPlan(
        val anchorColor: Int,
        val anchorDx: Int,
        val anchorDy: Int,
        val anchorRegion: Rect,
        val offsetsForAnchor: List<OffsetColor>
    )

    private fun shiftRegion(region: Rect, dx: Int, dy: Int, maxW: Int, maxH: Int): Rect? {
        var x = region.x + dx
        var y = region.y + dy
        var w = region.width
        var h = region.height

        if (x < 0) { w += x; x = 0 }
        if (y < 0) { h += y; y = 0 }
        if (x + w > maxW) w = maxW - x
        if (y + h > maxH) h = maxH - y

        if (w <= 0 || h <= 0) return null
        return Rect(x, y, w, h)
    }

    private fun rebaseOffsetsFromFirstToAnchor(offsets: List<OffsetColor>, anchorDx: Int, anchorDy: Int): List<OffsetColor> {
        val out = ArrayList<OffsetColor>(offsets.size)
        for (o in offsets) {
            out.add(
                OffsetColor(
                    dx = o.dx - anchorDx,
                    dy = o.dy - anchorDy,
                    color = o.color,
                    negative = o.negative
                )
            )
        }
        return out
    }

    private fun ensureFirstColorAsOffset(offsets: List<OffsetColor>, firstColor: Int, dx: Int, dy: Int): List<OffsetColor> {
        val need = OffsetColor(dx = dx, dy = dy, color = firstColor, negative = false)
        val exists = offsets.any { it.dx == need.dx && it.dy == need.dy && it.color == need.color && it.negative == need.negative }
        if (exists) return offsets
        return offsets + need
    }

    /* =========================================================================================
     * Parsing & utilities
     * ========================================================================================= */

    data class AbsPoint(val x: Int, val y: Int, val color: Int, val negative: Boolean)
    data class OffsetColor(val dx: Int, val dy: Int, val color: Int, val negative: Boolean)

    private fun parseOffsetGroup(group: String): List<OffsetColor>? {
        val parts = group.split(",")
        val out = ArrayList<OffsetColor>(parts.size)
        for (raw in parts) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val seg = t.split("|")
            if (seg.size < 3) return null
            val dx = seg[0].trim().toIntOrNull() ?: return null
            val dy = seg[1].trim().toIntOrNull() ?: return null
            val color = parseBBGGRR(seg[2].trim())
            val negative = seg.size > 3
            out.add(OffsetColor(dx, dy, color, negative))
        }
        return out
    }

    private fun parseAbsoluteGroup(group: String): List<AbsPoint>? {
        val parts = group.split(",")
        val out = ArrayList<AbsPoint>(parts.size)
        for (raw in parts) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val seg = t.split("|")
            if (seg.size < 3) return null
            val x = seg[0].trim().toIntOrNull() ?: return null
            val y = seg[1].trim().toIntOrNull() ?: return null
            val color = parseBBGGRR(seg[2].trim())
            val negative = seg.size > 3
            out.add(AbsPoint(x, y, color, negative))
        }
        return out
    }

    private fun parseBBGGRR(hex: String): Int {
        val s = hex.trim().removePrefix("0x").removePrefix("#")
        val v = s.toLong(16)
        val b = (v shr 16) and 0xFF
        val g = (v shr 8) and 0xFF
        val r = v and 0xFF
        return Color.rgb(r.toInt(), g.toInt(), b.toInt())
    }

    private fun similarityToDiff(similarity: Double): Int {
        val s = similarity.coerceIn(0.0, 1.0)
        return (255.0 * (1.0 - s)).roundToInt().coerceIn(0, 255)
    }

    private fun buildSafeRegion(x1: Int, y1: Int, x2: Int, y2: Int, maxX: Int, maxY: Int): Rect? {
        var left = x1
        var top = y1
        var right = x2
        var bottom = y2

        if (left < 0) left = 0
        if (top < 0) top = 0
        if (right >= maxX) right = maxX - 1
        if (bottom >= maxY) bottom = maxY - 1

        if (left >= right || top >= bottom) return null

        val w = right - left + 1
        val h = bottom - top + 1
        return Rect(left, top, w, h)
    }

    // === Fast RGB reads (no object allocation) ===
    private inline fun rgbaIndex(width: Int, x: Int, y: Int): Int = (y * width + x) * 4
    private inline fun readR(rgba: ByteArray, width: Int, x: Int, y: Int): Int =
        rgba[rgbaIndex(width, x, y)].toInt() and 0xFF
    private inline fun readG(rgba: ByteArray, width: Int, x: Int, y: Int): Int =
        rgba[rgbaIndex(width, x, y) + 1].toInt() and 0xFF
    private inline fun readB(rgba: ByteArray, width: Int, x: Int, y: Int): Int =
        rgba[rgbaIndex(width, x, y) + 2].toInt() and 0xFF
}
