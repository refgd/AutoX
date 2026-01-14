package com.refgd.autojs.core.image

import android.graphics.Bitmap
import android.util.Base64
import com.stardust.autojs.core.image.ImageWrapper
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.util.ArrayList
import java.util.Arrays
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class TinyOcrEngine internal constructor(
    labels: Array<String>,
    hashes: Array<String>
) {
    // ----------------------------
    // Config
    // ----------------------------

    private val dxCandidates = intArrayOf(-1, 0, 1)

    // tiny blob thresholds for scaleHeight=13
    private val tinyMaxW = 4
    private val tinyMaxH = 6
    private val tinyMaxArea = 14
    private val tinyMergePadX = 1
    private val tinyMergePadY = 8

    // ----------------------------
    // Reusable row buffer (instance-scoped)
    // Engine 被丢弃后 buffer 也会被 GC 回收，无需手动卸载
    // ----------------------------

    private var rowBuf: ByteArray = ByteArray(0)

    private fun ensureRowBuf(size: Int): ByteArray {
        if (rowBuf.size < size) rowBuf = ByteArray(size)
        return rowBuf
    }

    // ----------------------------
    // Internal structures
    // ----------------------------

    /**
     * Row bits are left->right in LSB order:
     * bit 0 == x=0 (leftmost), bit (len-1) == rightmost.
     */
    private class BitGlyph(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val lenBits: Int,
        val rows: LongArray
    )

    private class BitTemplate(
        val label: String,
        val lenBits: Int,
        val rows: LongArray
    )

    // ----------------------------
    // 64-char dict for debug hash encode/decode (Kotlin-native)
    // ----------------------------

    private val dict: CharArray = charArrayOf(
        'A','B','C','D','E','F','G',
        'H','I','J','K','L','M','N',
        'O','P','Q','R','S','T',
        'U','V','W','X','Y','Z',
        'a','b','c','d','e','f','g',
        'h','i','j','k','l','m','n',
        'o','p','q','r','s','t',
        'u','v','w','x','y','z',
        '0','1','2','3','4','5','6','7','8','9',
        '+','%'
    )

    private val dictRev: IntArray = IntArray(128) { -1 }.also { rev ->
        for (i in dict.indices) rev[dict[i].code] = i
    }

    // ----------------------------
    // Templates: decode ONCE into bitmasks
    // ----------------------------

    private val templates: List<BitTemplate> = run {
        require(labels.size == hashes.size) { "labels.size != hashes.size" }
        if (labels.isEmpty()) return@run emptyList()

        val out = ArrayList<BitTemplate>(labels.size)
        for (i in labels.indices) {
            val (lenBits, rowsBits) = decodeHashToBitRows(hashes[i])
            out.add(BitTemplate(labels[i], lenBits, rowsBits))
        }
        out
    }

    // Light index by (h,wCharCount) to reduce scanning
    private val templateIndex: Map<Long, List<BitTemplate>> = run {
        if (templates.isEmpty()) return@run emptyMap()
        val m = LinkedHashMap<Long, MutableList<BitTemplate>>(templates.size * 2)
        for (t in templates) {
            val h = t.rows.size
            val wChars = ((t.lenBits + 5) / 6)
            m.getOrPut(dimsKey(h, wChars)) { ArrayList(8) }.add(t)
        }
        m
    }

    // Exact match index: (h,lenBits,rowsHash) -> templates
    private data class ExactKey(val h: Int, val lenBits: Int, val rowsHash: Int)

    private val exactBitsIndex: Map<ExactKey, List<BitTemplate>> = run {
        if (templates.isEmpty()) return@run emptyMap()
        val m = LinkedHashMap<ExactKey, MutableList<BitTemplate>>(templates.size * 2)
        for (t in templates) {
            val key = ExactKey(t.rows.size, t.lenBits, Arrays.hashCode(t.rows))
            m.getOrPut(key) { ArrayList(1) }.add(t)
        }
        m
    }

    private fun findExactTemplate(g: BitGlyph): BitTemplate? {
        if (templates.isEmpty()) return null
        val key = ExactKey(g.rows.size, g.lenBits, Arrays.hashCode(g.rows))
        val list = exactBitsIndex[key] ?: return null
        for (t in list) {
            if (t.lenBits == g.lenBits && t.rows.size == g.rows.size && Arrays.equals(t.rows, g.rows)) return t
        }
        return null
    }

    // ----------------------------
    // Rhino-friendly result builders
    // ----------------------------

    private fun glyphMap(
        x: Int, y: Int, w: Int, h: Int,
        hash: String,
        label: String?,
        bestLabel: String?,
        sim: Double?,
        pngBase64: String?
    ): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(9)
        m["x"] = x
        m["y"] = y
        m["w"] = w
        m["h"] = h
        m["hash"] = hash
        m["label"] = label
        m["bestLabel"] = bestLabel
        m["sim"] = sim
        m["pngBase64"] = pngBase64
        return m
    }

    private fun resultMap(text: String?, glyphs: List<Map<String, Any?>>): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(2)
        m["text"] = text
        m["glyphs"] = glyphs
        return m
    }

    // ----------------------------
    // Public API
    // ----------------------------

    @JvmOverloads
    fun text(
        img: ImageWrapper,
        range: Rect,
        colorRgbaInt: Int,
        minWidth: Int = 3,
        minHeight: Int = 8,
        scaleHeight: Int = 13,
        maxWidth: Int = 9,
        tolerance: Int = 64,
        revert: Boolean = false,
        autoRotate: Boolean = false,
        autoRotateMaxAbsDeg: Double = 15.0,
        autoRotateSampleStep: Int = 2,
        autoRotateMinPoints: Int = 80,
        matchFactor: Double = 0.8,
        debugHash: Boolean = false,
        debugPngBase64: Boolean = false
    ): Map<String, Any?> {
        val glyphs = extractGlyphsLine(
            img, range, colorRgbaInt,
            minWidth, minHeight, scaleHeight, maxWidth,
            tolerance, revert,
            autoRotate, autoRotateMaxAbsDeg, autoRotateSampleStep, autoRotateMinPoints
        )
        if (glyphs.isEmpty()) return resultMap(null, emptyList())

        // allow empty templates
        if (templates.isEmpty()) {
            val outGlyphs = ArrayList<Map<String, Any?>>(glyphs.size)
            for (g in glyphs) {
                val h = if (debugHash) encodeBitGlyphToHash(g) else ""
                val pngB64 = if (debugPngBase64) bitGlyphToPngBase64(g) else null
                outGlyphs.add(glyphMap(g.x, g.y, g.w, g.h, h, null, null, null, pngB64))
            }
            return resultMap(null, outGlyphs)
        }

        val outGlyphs = ArrayList<Map<String, Any?>>(glyphs.size)
        val sb = StringBuilder(glyphs.size * 2)
        var allMatched = true

        val targetLenBits = targetLenBitsFor(maxWidth)
        for (g in glyphs) {
            val pngB64 = if (debugPngBase64) bitGlyphToPngBase64(g) else null

            // exact bits hit first
            val exact = findExactTemplate(g)
            if (exact != null) {
                sb.append(exact.label)
                val hashStr = if (debugHash) encodeBitGlyphToHash(g) else ""
                outGlyphs.add(glyphMap(g.x, g.y, g.w, g.h, hashStr, exact.label, exact.label, 0.0, pngB64))
                continue
            }

            val candidates = candidatesForGlyph(g, targetLenBits)
            val tpl = candidates.firstOrNull { isMatchBits(it, g, matchFactor, targetLenBits) }

            if (tpl == null) {
                allMatched = false
                val h = if (debugHash) encodeBitGlyphToHash(g) else ""
                outGlyphs.add(glyphMap(g.x, g.y, g.w, g.h, h, null, null, null, pngB64))
            } else {
                sb.append(tpl.label)
                val hashStr = if (debugHash) encodeBitGlyphToHash(g) else ""
                outGlyphs.add(glyphMap(g.x, g.y, g.w, g.h, hashStr, tpl.label, tpl.label, null, pngB64))
            }
        }

        return resultMap(if (allMatched) sb.toString() else null, outGlyphs)
    }

    @JvmOverloads
    fun textW(
        img: ImageWrapper,
        range: Rect,
        colorRgbaInt: Int,
        minWidth: Int = 3,
        minHeight: Int = 8,
        scaleHeight: Int = 13,
        maxWidth: Int = 9,
        tolerance: Int = 64,
        revert: Boolean = false,
        autoRotate: Boolean = false,
        autoRotateMaxAbsDeg: Double = 15.0,
        autoRotateSampleStep: Int = 2,
        autoRotateMinPoints: Int = 80,
        simThreshold: Double = 0.8,
        debugHash: Boolean = false,
        debugPngBase64: Boolean = false
    ): Map<String, Any?> {
        val glyphs = extractGlyphsLine(
            img, range, colorRgbaInt,
            minWidth, minHeight, scaleHeight, maxWidth,
            tolerance, revert,
            autoRotate, autoRotateMaxAbsDeg, autoRotateSampleStep, autoRotateMinPoints
        )
        if (glyphs.isEmpty()) return resultMap(null, emptyList())

        // allow empty templates
        if (templates.isEmpty()) {
            val outGlyphs = ArrayList<Map<String, Any?>>(glyphs.size)
            for (g in glyphs) {
                val h = if (debugHash) encodeBitGlyphToHash(g) else ""
                val pngB64 = if (debugPngBase64) bitGlyphToPngBase64(g) else null
                outGlyphs.add(glyphMap(g.x, g.y, g.w, g.h, h, null, null, null, pngB64))
            }
            return resultMap(null, outGlyphs)
        }

        val outGlyphs = ArrayList<Map<String, Any?>>(glyphs.size)
        val sb = StringBuilder(glyphs.size * 2)
        var allMatched = true

        val targetLenBits = targetLenBitsFor(maxWidth)
        for (g in glyphs) {
            val hashStr = if (debugHash) encodeBitGlyphToHash(g) else ""
            val pngB64 = if (debugPngBase64) bitGlyphToPngBase64(g) else null

            // exact bits hit first
            val exact = findExactTemplate(g)
            if (exact != null) {
                sb.append(exact.label)
                outGlyphs.add(glyphMap(g.x, g.y, g.w, g.h, hashStr, exact.label, exact.label, 0.0, pngB64))
                continue
            }

            val candidates = candidatesForGlyph(g, targetLenBits)
            var bestLabel: String? = null
            var bestSim = 1.0

            for (tpl in candidates) {
                val sim = simBits(tpl, g, bestSim, targetLenBits)
                if (sim < bestSim) {
                    bestSim = sim
                    bestLabel = tpl.label
                }
            }

            if (bestLabel == null || bestSim >= simThreshold) {
                allMatched = false
                outGlyphs.add(glyphMap(g.x, g.y, g.w, g.h, hashStr, null, bestLabel, bestSim, pngB64))
            } else {
                sb.append(bestLabel)
                outGlyphs.add(glyphMap(g.x, g.y, g.w, g.h, hashStr, bestLabel, bestLabel, bestSim, pngB64))
            }
        }

        return resultMap(if (allMatched) sb.toString() else null, outGlyphs)
    }

    // ----------------------------
    // Matching helpers
    // ----------------------------

    private fun targetLenBitsFor(maxWidth: Int): Int {
        val soft = if (maxWidth > 0) maxWidth + 2 else 12
        return soft.coerceIn(6, 60)
    }

    private fun maskBits(len: Int): Long {
        if (len <= 0) return 0L
        if (len >= 63) return -1L
        return (1L shl len) - 1L
    }

    private fun centerFitBits(bits: Long, fromLen: Int, toLen: Int): Long {
        val m = maskBits(toLen)
        return when {
            fromLen == toLen -> bits and m
            fromLen < toLen -> {
                val padLeft = (toLen - fromLen) / 2
                (bits shl padLeft) and m
            }
            else -> {
                val dropLeft = (fromLen - toLen) / 2
                (bits ushr dropLeft) and m
            }
        }
    }

    private fun rowDistWithShift(tplBits: Long, glyphBits: Long, lenBits: Int, dx: Int): Int {
        val m = maskBits(lenBits)
        val shifted = when {
            dx == 0 -> glyphBits
            dx > 0 -> (glyphBits shl dx) and m
            else -> (glyphBits ushr (-dx)) and m
        }
        return java.lang.Long.bitCount((tplBits xor shifted) and m)
    }

    private fun dimsKey(h: Int, wChars: Int): Long =
        (h.toLong() shl 32) or (wChars.toLong() and 0xffffffffL)

    private fun candidatesForGlyph(g: BitGlyph, targetLenBits: Int): List<BitTemplate> {
        if (templates.isEmpty()) return emptyList()

        val h = g.rows.size
        val wChars = ((min(g.lenBits, targetLenBits) + 5) / 6)
        if (h <= 0 || wChars <= 0) return templates

        val out = ArrayList<BitTemplate>(32)
        for (dh in -1..1) for (dw in -1..1) {
            val hh = h + dh
            val ww = wChars + dw
            if (hh <= 0 || ww <= 0) continue
            val list = templateIndex[dimsKey(hh, ww)]
            if (list != null) out.addAll(list)
        }
        return if (out.isNotEmpty()) out else templates
    }

    private fun isMatchBits(tpl: BitTemplate, g: BitGlyph, matchFactor: Double, targetLenBits: Int): Boolean {
        val maxH = max(tpl.rows.size, g.rows.size)
        val maxBits = targetLenBits * maxH
        val limit = (maxBits * matchFactor).toInt()

        var dist = 0
        for (y in 0 until maxH) {
            val tb = if (y < tpl.rows.size) centerFitBits(tpl.rows[y], tpl.lenBits, targetLenBits) else 0L
            val gb0 = if (y < g.rows.size) centerFitBits(g.rows[y], g.lenBits, targetLenBits) else 0L

            var best = Int.MAX_VALUE
            for (dx in dxCandidates) {
                val d = rowDistWithShift(tb, gb0, targetLenBits, dx)
                if (d < best) best = d
            }

            dist += best
            if (dist > limit) return false
        }
        return true
    }

    private fun simBits(tpl: BitTemplate, g: BitGlyph, bestSoFar: Double, targetLenBits: Int): Double {
        val maxH = max(tpl.rows.size, g.rows.size)
        val denom = (targetLenBits * maxH).toDouble()
        val bestDistLimit = (bestSoFar * denom).toInt()

        var dist = 0
        for (y in 0 until maxH) {
            val tb = if (y < tpl.rows.size) centerFitBits(tpl.rows[y], tpl.lenBits, targetLenBits) else 0L
            val gb0 = if (y < g.rows.size) centerFitBits(g.rows[y], g.lenBits, targetLenBits) else 0L

            var best = Int.MAX_VALUE
            for (dx in dxCandidates) {
                val d = rowDistWithShift(tb, gb0, targetLenBits, dx)
                if (d < best) best = d
            }

            dist += best
            if (dist >= bestDistLimit) return dist / denom
        }

        return dist / denom
    }

    // ----------------------------
    // Decode hash -> (lenBits, rowsBits) with manual parsing
    // Supports same encoding as encodeBitGlyphToHash()
    // ----------------------------

    private fun decodeHashToBitRows(hash: String): Pair<Int, LongArray> {
        if (hash.isEmpty()) return 0 to LongArray(0)

        var bars = 0
        var firstRowChars = 0
        var inFirstRow = true

        for (i in hash.indices) {
            val c = hash[i]
            if (c == '|') {
                bars++
                inFirstRow = false
            } else if (inFirstRow) {
                firstRowChars++
            }
        }

        val rowsCount = bars + 1
        val lenBits = firstRowChars * 6
        val rows = LongArray(rowsCount)

        var rowIdx = 0
        var bits = 0L
        var pos = 0

        for (i in hash.indices) {
            val c = hash[i]
            if (c == '|') {
                // flush row
                if (rowIdx >= rowsCount) {
                    throw IllegalArgumentException("Malformed hash (too many '|'): $hash")
                }
                rows[rowIdx] = bits
                rowIdx++
                bits = 0L
                pos = 0
                continue
            }

            val v = if (c.code < 128) dictRev[c.code] else -1
            require(v in 0..63) { "Invalid hash char '$c' in: $hash" }

            for (k in 0 until 6) {
                if (((v shr k) and 1) != 0) bits = bits or (1L shl pos)
                pos++
            }
        }

        // last row
        if (rowIdx != rowsCount - 1) {
            // 说明 '|' 数和解析出来的行数不一致（尾部 '|' 或其它异常）
            // 这里给个明确错误，方便你定位数据
            // 如果你希望容错，也可以改成：rows[rowIdx] = bits; return ...
            throw IllegalArgumentException("Malformed hash (row count mismatch): $hash")
        }
        rows[rowIdx] = bits
        return lenBits to rows
    }

    // ----------------------------
    // Debug hash encode from bits (Kotlin-native)
    // ----------------------------

    private fun encodeBitGlyphToHash(g: BitGlyph): String {
        val lenBits = g.lenBits.coerceAtLeast(1)
        val groups = ((lenBits + 5) / 6)
        val sb = StringBuilder(g.rows.size * (groups + 1))

        val m = maskBits(lenBits)
        for (y in g.rows.indices) {
            if (y != 0) sb.append('|')

            val rowBits = g.rows[y] and m
            for (gi in 0 until groups) {
                var v = 0
                val base = gi * 6
                for (k in 0 until 6) {
                    val bitIdx = base + k
                    val b = if (bitIdx < lenBits && ((rowBits ushr bitIdx) and 1L) != 0L) 1 else 0
                    v = v or (b shl k)
                }
                sb.append(dict[v and 63])
            }
        }
        return sb.toString()
    }

    // ----------------------------
    // Debug PNG Base64 from bit glyph
    // ----------------------------

    private fun bitGlyphToPngBase64(g: BitGlyph): String {
        val w = g.lenBits.coerceAtLeast(1)
        val h = g.rows.size.coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w)

        val mask = maskBits(w)
        for (y in 0 until h) {
            val rowBits = g.rows[y] and mask
            for (x in 0 until w) {
                val on = ((rowBits ushr x) and 1L) != 0L
                pixels[x] = if (on) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
            bmp.setPixels(pixels, 0, w, 0, y, w, 1)
        }

        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bmp.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    // ----------------------------
    // Segmentation: line bbox + projection
    // with CC-based tiny-blob extraction/merging
    // ----------------------------

    private data class Affine6(
        val a: Double, val b: Double, val c: Double,
        val d: Double, val e: Double, val f: Double
    )

    private fun readAffine6(m: Mat): Affine6 {
        val v = DoubleArray(6)
        m.get(0, 0, v)
        return Affine6(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    private fun invertAffine6(m: Affine6): Affine6? {
        val det = m.a * m.e - m.b * m.d
        if (kotlin.math.abs(det) < 1e-12) return null
        val invDet = 1.0 / det
        val ia = m.e * invDet
        val ib = -m.b * invDet
        val id = -m.d * invDet
        val ie = m.a * invDet
        val ic = -(ia * m.c + ib * m.f)
        val iff = -(id * m.c + ie * m.f)
        return Affine6(ia, ib, ic, id, ie, iff)
    }

    private fun transformPoint(mat: Affine6, x: Double, y: Double): Point {
        val nx = mat.a * x + mat.b * y + mat.c
        val ny = mat.d * x + mat.e * y + mat.f
        return Point(nx, ny)
    }

    private fun transformRectBBox(mat: Affine6, r: Rect): Rect {
        val x0 = r.x.toDouble()
        val y0 = r.y.toDouble()
        val x1 = (r.x + r.width).toDouble()
        val y1 = (r.y + r.height).toDouble()

        val p1 = transformPoint(mat, x0, y0)
        val p2 = transformPoint(mat, x1, y0)
        val p3 = transformPoint(mat, x0, y1)
        val p4 = transformPoint(mat, x1, y1)

        val minX = floor(min(min(p1.x, p2.x), min(p3.x, p4.x))).toInt()
        val minY = floor(min(min(p1.y, p2.y), min(p3.y, p4.y))).toInt()
        val maxX = ceil(max(max(p1.x, p2.x), max(p3.x, p4.x))).toInt()
        val maxY = ceil(max(max(p1.y, p2.y), max(p3.y, p4.y))).toInt()

        val w = (maxX - minX).coerceAtLeast(0)
        val h = (maxY - minY).coerceAtLeast(0)
        return Rect(minX, minY, w, h)
    }

    private fun extractGlyphsLine(
        img: ImageWrapper,
        range: Rect,
        colorRgbaInt: Int,
        minWidth: Int,
        minHeight: Int,
        scaleHeight: Int,
        maxWidth: Int,
        tolerance: Int,
        revert: Boolean,
        autoRotate: Boolean,
        autoRotateMaxAbsDeg: Double,
        autoRotateSampleStep: Int,
        autoRotateMinPoints: Int
    ): List<BitGlyph> {
        val src: Mat = img.getMat() // DO NOT release

        val safe = rectSafe(range, src.cols(), src.rows())
        if (safe.width <= 0 || safe.height <= 0) return emptyList()

        val baseX = safe.x
        val baseY = safe.y

        var work: Mat? = null
        var mask: Mat? = null
        var rotMat: Mat? = null
        var invMat6: Affine6? = null

        try {
            work = Mat(src, safe)
            mask = thresholdInRangeRgba(work, colorRgbaInt, tolerance, revert)

            if (autoRotate) {
                val angle = estimateSkewAngleMoments(mask, autoRotateSampleStep, autoRotateMinPoints)
                val a = abs(angle)
                if (a in 0.3..autoRotateMaxAbsDeg) {
                    val center = Point(work.cols() / 2.0, work.rows() / 2.0)
                    rotMat = Imgproc.getRotationMatrix2D(center, -angle, 1.0)

                    val rotated = Mat()
                    Imgproc.warpAffine(work, rotated, rotMat, work.size())
                    work.release()
                    work = rotated

                    mask.release()
                    mask = thresholdInRangeRgba(work, colorRgbaInt, tolerance, revert)

                    invMat6 = invertAffine6(readAffine6(rotMat))
                }
            }

            val line = findForegroundBBox(mask) ?: return emptyList()
            if (line.height < minHeight || line.width <= 0) return emptyList()

            val lineMask = Mat(mask, line)
            val scaled = Mat()
            try {
                val newW = max(1, (line.width * scaleHeight) / max(1, line.height))
                Imgproc.resize(
                    lineMask, scaled,
                    Size(newW.toDouble(), scaleHeight.toDouble()),
                    0.0, 0.0,
                    Imgproc.INTER_NEAREST
                )

                val tinyClusters = findTinyClusters(scaled)

                val segMask = if (tinyClusters.isEmpty()) scaled else scaled.clone()
                try {
                    if (tinyClusters.isNotEmpty()) eraseClusters(segMask, tinyClusters)

                    val colSums = columnSums(segMask)
                    val maxCol = colSums.maxOrNull() ?: 0
                    val colThreshold = max(1, (maxCol * 0.20).toInt())
                    val segments = splitByProjection(colSums, colThreshold, maxGap = 2)

                    val glyphs = ArrayList<BitGlyph>(segments.size + tinyClusters.size)

                    val sx = line.width.toDouble() / newW.toDouble()
                    val sy = line.height.toDouble() / scaleHeight.toDouble()

                    // main glyphs
                    for (seg in segments) {
                        val segW = seg.last - seg.first + 1
                        if (segW < minWidth) continue

                        val bitRows = extractBitRowsFromMask(segMask, seg.first, 0, segW, scaleHeight)

                        val x0 = floor(seg.first * sx).toInt()
                        val x1 = ceil((seg.last + 1) * sx).toInt()
                        val w0 = (x1 - x0).coerceAtLeast(1)

                        val glyphRectInRot = Rect(line.x + x0, line.y, w0, line.height)
                        val glyphRectInRoi = if (invMat6 != null) transformRectBBox(invMat6!!, glyphRectInRot) else glyphRectInRot

                        val clamped = rectSafe(glyphRectInRoi, safe.width, safe.height)
                        if (clamped.width <= 0 || clamped.height <= 0) continue
                        val absRect = Rect(baseX + clamped.x, baseY + clamped.y, clamped.width, clamped.height)

                        glyphs.add(
                            BitGlyph(
                                absRect.x, absRect.y, absRect.width, absRect.height,
                                segW.coerceAtMost(60),
                                bitRows
                            )
                        )
                    }

                    // punctuation clusters (do not allocate sub-mat)
                    if (tinyClusters.isNotEmpty()) {
                        for (cr in tinyClusters) {
                            val bw = cr.width.coerceAtLeast(1)
                            val bh = cr.height.coerceAtLeast(1)

                            val bitRows = extractBitRowsFromMask(scaled, cr.x, cr.y, bw, bh)

                            val rx0 = floor(cr.x * sx).toInt()
                            val rx1 = ceil((cr.x + cr.width) * sx).toInt()
                            val ry0 = floor(cr.y * sy).toInt()
                            val ry1 = ceil((cr.y + cr.height) * sy).toInt()

                            val rw = (rx1 - rx0).coerceAtLeast(1)
                            val rh = (ry1 - ry0).coerceAtLeast(1)

                            val glyphRectInRot = Rect(line.x + rx0, line.y + ry0, rw, rh)
                            val glyphRectInRoi = if (invMat6 != null) transformRectBBox(invMat6!!, glyphRectInRot) else glyphRectInRot

                            val clamped = rectSafe(glyphRectInRoi, safe.width, safe.height)
                            if (clamped.width <= 0 || clamped.height <= 0) continue
                            val absRect = Rect(baseX + clamped.x, baseY + clamped.y, clamped.width, clamped.height)

                            glyphs.add(
                                BitGlyph(
                                    absRect.x, absRect.y, absRect.width, absRect.height,
                                    bw.coerceAtMost(60),
                                    bitRows
                                )
                            )
                        }
                    }

                    glyphs.sortWith(compareBy<BitGlyph> { it.x }.thenBy { it.y })
                    return glyphs
                } finally {
                    if (segMask !== scaled) segMask.release()
                }
            } finally {
                lineMask.release()
                scaled.release()
            }
        } finally {
            rotMat?.release()
            mask?.release()
            work?.release()
        }
    }

    // ----------------------------
    // CC tiny blob detection + clustering
    // ----------------------------

    private fun findTinyClusters(mask13: Mat): List<Rect> {
        val labels = Mat()
        val stats = Mat()
        val cents = Mat()
        try {
            val n = Imgproc.connectedComponentsWithStats(mask13, labels, stats, cents, 8, CvType.CV_32S)
            if (n <= 1) return emptyList()

            val tinyRects = ArrayList<Rect>(16)
            for (i in 1 until n) {
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()

                if (w <= 0 || h <= 0) continue
                if (w <= tinyMaxW && h <= tinyMaxH && area <= tinyMaxArea) {
                    tinyRects.add(Rect(x, y, w, h))
                }
            }
            if (tinyRects.isEmpty()) return emptyList()
            return mergeRects(tinyRects, tinyMergePadX, tinyMergePadY)
        } finally {
            labels.release()
            stats.release()
            cents.release()
        }
    }

    private fun mergeRects(rects: List<Rect>, padX: Int, padY: Int): List<Rect> {
        val out = ArrayList<Rect>()
        val used = BooleanArray(rects.size)
        for (i in rects.indices) {
            if (used[i]) continue
            var cur = rects[i]
            used[i] = true

            var changed = true
            while (changed) {
                changed = false
                for (j in rects.indices) {
                    if (used[j]) continue
                    val r = rects[j]
                    if (rectsNear(cur, r, padX, padY)) {
                        cur = unionRect(cur, r)
                        used[j] = true
                        changed = true
                    }
                }
            }
            out.add(cur)
        }
        out.sortBy { it.x }
        return out
    }

    private fun rectsNear(a: Rect, b: Rect, padX: Int, padY: Int): Boolean {
        val ax0 = a.x - padX
        val ay0 = a.y - padY
        val ax1 = a.x + a.width + padX
        val ay1 = a.y + a.height + padY

        val bx0 = b.x
        val by0 = b.y
        val bx1 = b.x + b.width
        val by1 = b.y + b.height

        val interX = min(ax1, bx1) - max(ax0, bx0)
        val interY = min(ay1, by1) - max(ay0, by0)
        return interX > 0 && interY > 0
    }

    private fun unionRect(a: Rect, b: Rect): Rect {
        val x0 = min(a.x, b.x)
        val y0 = min(a.y, b.y)
        val x1 = max(a.x + a.width, b.x + b.width)
        val y1 = max(a.y + a.height, b.y + b.height)
        return Rect(x0, y0, (x1 - x0).coerceAtLeast(1), (y1 - y0).coerceAtLeast(1))
    }

    private fun eraseClusters(mask13: Mat, clusters: List<Rect>) {
        for (r in clusters) {
            Imgproc.rectangle(mask13, r.tl(), r.br(), Scalar(0.0), Imgproc.FILLED)
        }
    }

    // ----------------------------
    // Projection split (with "zero column hard break")
    // ----------------------------

    private fun splitByProjection(colSums: IntArray, threshold: Int, maxGap: Int): List<IntRange> {
        val n = colSums.size
        val segs = ArrayList<IntRange>()

        var x = 0
        while (x < n) {
            while (x < n && colSums[x] < threshold) x++
            if (x >= n) break
            val start = x

            var lastInk = x
            var gap = 0
            x++

            while (x < n) {
                val v = colSums[x]

                // hard split on fully empty column
                if (v == 0) break

                if (v >= threshold) {
                    lastInk = x
                    gap = 0
                } else {
                    gap++
                    if (gap > maxGap) break
                }
                x++
            }

            val end = lastInk
            if (end >= start) segs.add(start..end)

            while (x < n && colSums[x] < threshold) x++
        }

        return segs
    }

    // ----------------------------
    // Basic image helpers
    // ----------------------------

    private fun findForegroundBBox(mask: Mat): Rect? {
        val h = mask.rows()
        val w = mask.cols()
        if (w <= 0 || h <= 0) return null

        val row = ensureRowBuf(w)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var count = 0

        for (y in 0 until h) {
            mask.get(y, 0, row)
            for (x in 0 until w) {
                if ((row[x].toInt() and 0xFF) != 0) {
                    count++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }

        if (count == 0) return null
        return Rect(minX, minY, (maxX - minX + 1).coerceAtLeast(1), (maxY - minY + 1).coerceAtLeast(1))
    }

    private fun columnSums(mask13: Mat): IntArray {
        val h = mask13.rows()
        val w = mask13.cols()
        val sums = IntArray(w)
        val row = ensureRowBuf(w)

        for (y in 0 until h) {
            mask13.get(y, 0, row)
            for (x in 0 until w) {
                if ((row[x].toInt() and 0xFF) != 0) sums[x]++
            }
        }
        return sums
    }

    /**
     * Extract bits from a region [xStart,yStart,width,height] of mask (no sub-mat allocation).
     * bit0 corresponds to region leftmost.
     */
    private fun extractBitRowsFromMask(mask: Mat, xStart: Int, yStart: Int, width: Int, height: Int): LongArray {
        val w = mask.cols()
        val h = mask.rows()
        val len = width.coerceAtMost(60).coerceAtLeast(1)
        val outH = height.coerceAtLeast(1)
        val rows = LongArray(outH)

        if (w <= 0 || h <= 0) return rows

        val buf = ensureRowBuf(w)

        val y0 = yStart.coerceAtLeast(0)
        val yEnd = min(yStart + outH, h)

        var outY = 0
        var y = y0
        while (y < yEnd && outY < outH) {
            mask.get(y, 0, buf)

            var bits = 0L
            val x0 = xStart.coerceAtLeast(0)
            val xEnd = min(xStart + len, w)

            var pos = 0
            var x = x0
            while (x < xEnd) {
                if ((buf[x].toInt() and 0xFF) != 0) bits = bits or (1L shl pos)
                pos++
                x++
            }

            rows[outY] = bits
            outY++
            y++
        }
        return rows
    }

    private fun thresholdInRangeRgba(srcRgba: Mat, rgbaInt: Int, tolerance: Int, revert: Boolean): Mat {
        val r = (rgbaInt shr 16) and 0xFF
        val g = (rgbaInt shr 8) and 0xFF
        val b = rgbaInt and 0xFF
        fun clamp(v: Int) = v.coerceIn(0, 255)

        val lb = Scalar(clamp(r - tolerance).toDouble(), clamp(g - tolerance).toDouble(), clamp(b - tolerance).toDouble(), 0.0)
        val ub = Scalar(clamp(r + tolerance).toDouble(), clamp(g + tolerance).toDouble(), clamp(b + tolerance).toDouble(), 255.0)

        val mask = Mat()
        Core.inRange(srcRgba, lb, ub, mask)
        if (revert) Core.bitwise_not(mask, mask)
        return mask
    }

    private fun estimateSkewAngleMoments(mask: Mat, sampleStep: Int, minPoints: Int): Double {
        val h = mask.rows()
        val w = mask.cols()
        if (w <= 0 || h <= 0) return 0.0

        val step = sampleStep.coerceAtLeast(1)
        val row = ensureRowBuf(w)

        var n = 0L
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var sumXY = 0.0

        var y = 0
        while (y < h) {
            mask.get(y, 0, row)
            var x = 0
            while (x < w) {
                if ((row[x].toInt() and 0xFF) != 0) {
                    val xd = x.toDouble()
                    val yd = y.toDouble()
                    n++
                    sumX += xd
                    sumY += yd
                    sumXX += xd * xd
                    sumYY += yd * yd
                    sumXY += xd * yd
                }
                x += step
            }
            y += step
        }

        if (n < minPoints.toLong()) return 0.0

        val invN = 1.0 / n.toDouble()
        val meanX = sumX * invN
        val meanY = sumY * invN

        val mu20 = sumXX - sumX * meanX
        val mu02 = sumYY - sumY * meanY
        val mu11 = sumXY - sumX * meanY

        val angleRad = 0.5 * atan2(2.0 * mu11, (mu20 - mu02))
        var angleDeg = angleRad * 180.0 / Math.PI

        while (angleDeg <= -90) angleDeg += 180.0
        while (angleDeg > 90) angleDeg -= 180.0
        return angleDeg
    }

    private fun rectSafe(r: Rect, maxW: Int, maxH: Int): Rect {
        var x = r.x
        var y = r.y
        var w = r.width
        var h = r.height

        if (w < 0) { x += w; w = -w }
        if (h < 0) { y += h; h = -h }

        if (x < 0) { w += x; x = 0 }
        if (y < 0) { h += y; y = 0 }

        if (x + w > maxW) w = maxW - x
        if (y + h > maxH) h = maxH - y

        if (w < 0) w = 0
        if (h < 0) h = 0
        return Rect(x, y, w, h)
    }
}

object TinyOcr {
    @JvmStatic
    fun compile(labels: Array<String>, hashes: Array<String>): TinyOcrEngine =
        TinyOcrEngine(labels, hashes)
}
