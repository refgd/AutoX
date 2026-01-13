@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.stardust.autojs.core.image

import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.os.Build
import androidx.annotation.RequiresApi
import com.stardust.autojs.core.opencv.Mat
import com.stardust.autojs.core.opencv.OpenCVHelper
import com.stardust.pio.UncheckedIOException
import org.opencv.android.Utils
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kotlin improved ImageWrapper with:
 * - Mat.get() coordinate fix (row=y, col=x)
 * - ROI RGBA bytes LRU cache
 * - ByteArray buffer pool reuse (arrays returned are cache-owned; do not hold long-term)
 */
class ImageWrapper private constructor(
    private var mMat: Mat?,
    private var mBitmap: Bitmap?
) {

    private var mWidth: Int = (mBitmap?.width ?: mMat?.cols()) ?: 0
    private var mHeight: Int = (mBitmap?.height ?: mMat?.rows()) ?: 0

    private val mVersion = AtomicInteger(1)

    private data class RoiKey(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val version: Int,
        val type: Int,
        val channels: Int
    )

    private val roiCacheLock = Any()

    /**
     * ROI RGBA bytes LRU cache (max 4).
     * When an entry is evicted, we recycle its ByteArray back to ByteArrayPool.
     */
    private val mRoiRgbaCache: LinkedHashMap<RoiKey, ByteArray> =
        object : LinkedHashMap<RoiKey, ByteArray>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RoiKey, ByteArray>?): Boolean {
                if (size <= 4) return false
                eldest?.value?.let { ByteArrayPool.recycle(it) }
                return true
            }
        }

    @Volatile
    private var mWholeRgbaCache: ByteArray? = null
    @Volatile
    private var mWholeRgbaCacheVersion: Int = 0
    @Volatile
    private var mWholeRgbaCacheType: Int = 0
    @Volatile
    private var mWholeRgbaCacheChannels: Int = 0

    constructor(width: Int, height: Int) : this(
        mMat = null,
        mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    )

    companion object {
        @JvmStatic
        @RequiresApi(Build.VERSION_CODES.KITKAT)
        fun ofImage(image: Image?): ImageWrapper? {
            if (image == null) return null
            return ImageWrapper(mMat = null, mBitmap = toBitmap(image))
        }

        @JvmStatic
        fun ofMat(mat: Mat?): ImageWrapper? {
            if (mat == null) return null
            return ImageWrapper(mMat = mat, mBitmap = null)
        }

        @JvmStatic
        fun ofBitmap(bitmap: Bitmap): ImageWrapper {
            return ImageWrapper(mMat = null, mBitmap = bitmap)
        }

        @JvmStatic
        @RequiresApi(Build.VERSION_CODES.KITKAT)
        fun toBitmap(image: Image): Bitmap {
            val plane: Image.Plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            buffer.position(0)
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            return if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        }
    }

    fun getWidth(): Int {
        ensureNotRecycled()
        return mWidth
    }

    fun getHeight(): Int {
        ensureNotRecycled()
        return mHeight
    }

    fun getMat(): Mat {
        ensureNotRecycled()
        if (mMat == null && mBitmap != null) {
            val mat = Mat()
            Utils.bitmapToMat(mBitmap, mat)
            mMat = mat
        }
        return mMat!!
    }

    fun getBitmap(): Bitmap {
        ensureNotRecycled()
        if (mBitmap == null && mMat != null) {
            val bmp = Bitmap.createBitmap(mMat!!.width(), mMat!!.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mMat, bmp)
            mBitmap = bmp
        }
        return mBitmap!!
    }

    fun saveTo(path: String) {
        ensureNotRecycled()
        if (mBitmap != null) {
            try {
                mBitmap!!.compress(Bitmap.CompressFormat.PNG, 100, FileOutputStream(path))
            } catch (e: FileNotFoundException) {
                throw UncheckedIOException(e)
            }
        } else {
            Imgcodecs.imwrite(path, mMat)
        }
    }

    /**
     * Legacy single-pixel access. Corrected Mat.get(row=y, col=x).
     * Prefer bulk APIs in hot loops.
     */
    fun pixel(x: Int, y: Int): Int {
        ensureNotRecycled()
        if (mBitmap != null) return mBitmap!!.getPixel(x, y)

        val mat = mMat ?: throw IllegalStateException("image has been recycled")
        val channels = mat.get(y, x) ?: return 0 // ✅ correct order
        val a = channels.getOrNull(3)?.toInt() ?: 255
        val r = channels.getOrNull(0)?.toInt() ?: 0
        val g = channels.getOrNull(1)?.toInt() ?: 0
        val b = channels.getOrNull(2)?.toInt() ?: 0
        return Color.argb(a, r, g, b)
    }

    /**
     * Fast ROI RGBA bytes, tight packed [R,G,B,A] row-major.
     *
     * Uses:
     * - ROI LRU cache (max 4)
     * - ByteArrayPool to reuse buffers
     *
     * ⚠️ IMPORTANT:
     * Returned ByteArray is cache-owned and may be recycled back to the pool
     * when the ROI cache evicts entries. Do not hold it long-term.
     * If you need long-term storage, use `copyOf()`.
     */
    fun getRgbaRoiBytes(rect: Rect): ByteArray {
        ensureNotRecycled()

        val mat = getMat()
        val version = mVersion.get()
        val type = mat.type()
        val channels = mat.channels()

        val safe = rectSafe(rect, mWidth, mHeight)

        val key = RoiKey(
            x = safe.x, y = safe.y, w = safe.width, h = safe.height,
            version = version, type = type, channels = channels
        )

        synchronized(roiCacheLock) {
            mRoiRgbaCache[key]?.let { return it }
        }

        val roi = Mat(mat, safe)
        try {
            val w = safe.width
            val h = safe.height
            val needBytes = w * h * 4

            val out: ByteArray = when (roi.channels()) {
                4 -> {
                    val buf = ByteArrayPool.obtain(needBytes)
                    roi.get(0, 0, buf)
                    buf
                }
                3 -> {
                    val rgba = Mat()
                    try {
                        Imgproc.cvtColor(roi, rgba, Imgproc.COLOR_BGR2RGBA)
                        val buf = ByteArrayPool.obtain(needBytes)
                        rgba.get(0, 0, buf)
                        buf
                    } finally {
                        OpenCVHelper.release(rgba)
                    }
                }
                1 -> {
                    val rgba = Mat()
                    try {
                        Imgproc.cvtColor(roi, rgba, Imgproc.COLOR_GRAY2RGBA)
                        val buf = ByteArrayPool.obtain(needBytes)
                        rgba.get(0, 0, buf)
                        buf
                    } finally {
                        OpenCVHelper.release(rgba)
                    }
                }
                else -> {
                    val rgba = Mat()
                    try {
                        // best-effort fallback
                        Imgproc.cvtColor(roi, rgba, Imgproc.COLOR_BGR2RGBA)
                        val buf = ByteArrayPool.obtain(needBytes)
                        rgba.get(0, 0, buf)
                        buf
                    } finally {
                        OpenCVHelper.release(rgba)
                    }
                }
            }

            synchronized(roiCacheLock) {
                // In case of replacing an existing value (rare), recycle old.
                val old = mRoiRgbaCache.put(key, out)
                if (old != null && old !== out) {
                    ByteArrayPool.recycle(old)
                }
            }

            return out
        } finally {
            OpenCVHelper.release(roi)
        }
    }

    /**
     * Whole-image RGBA bytes (tight packed). Cached ONE buffer per version.
     * Great if you do many different ROIs per frame.
     *
     * ⚠️ Same lifetime note as getRgbaRoiBytes().
     */
    fun getRgbaBytes(): ByteArray {
        ensureNotRecycled()

        val mat = getMat()
        val version = mVersion.get()
        val type = mat.type()
        val channels = mat.channels()

        val cached = mWholeRgbaCache
        if (cached != null &&
            mWholeRgbaCacheVersion == version &&
            mWholeRgbaCacheType == type &&
            mWholeRgbaCacheChannels == channels
        ) return cached

        // Rebuild whole buffer
        val fullRect = Rect(0, 0, mWidth, mHeight)
        val bytes = getRgbaRoiBytes(fullRect)

        // If previous existed and is different, recycle it (avoid leaks)
        val old = mWholeRgbaCache
        if (old != null && old !== bytes) {
            ByteArrayPool.recycle(old)
        }

        mWholeRgbaCache = bytes
        mWholeRgbaCacheVersion = version
        mWholeRgbaCacheType = type
        mWholeRgbaCacheChannels = channels
        return bytes
    }

    fun recycle() {
        // return cached ROI buffers to pool
        synchronized(roiCacheLock) {
            for (b in mRoiRgbaCache.values) {
                ByteArrayPool.recycle(b)
            }
            mRoiRgbaCache.clear()
        }

        // return whole buffer
        mWholeRgbaCache?.let { ByteArrayPool.recycle(it) }
        mWholeRgbaCache = null

        mBitmap?.recycle()
        mBitmap = null

        mMat?.let { OpenCVHelper.release(it) }
        mMat = null
    }

    fun ensureNotRecycled() {
        if (mBitmap == null && mMat == null) throw IllegalStateException("image has been recycled")
    }

    public fun clone(): ImageWrapper {
        ensureNotRecycled()
        val newBitmap = mBitmap?.copy(mBitmap!!.config, true)
        val newMat = mMat?.clone()
        return ImageWrapper(newMat, newBitmap)
    }

    private fun rectSafe(r: Rect, maxW: Int, maxH: Int): Rect {
        var x = r.x
        var y = r.y
        var w = r.width
        var h = r.height

        if (x < 0) { w += x; x = 0 }
        if (y < 0) { h += y; y = 0 }
        if (x + w > maxW) w = maxW - x
        if (y + h > maxH) h = maxH - y
        if (w < 0) w = 0
        if (h < 0) h = 0
        return Rect(x, y, w, h)
    }
}

/**
 * Simple ByteArray pool with size buckets.
 *
 * - Bucket sizes are rounded up to 4KB blocks to reduce fragmentation.
 * - Max arrays per bucket is limited to avoid memory blowups.
 * - Thread-safe.
 *
 * Note:
 * - We only recycle arrays that come from this pool.
 * - If you pass a random ByteArray to recycle(), it will still be stored if size matches bucket;
 *   so you should only recycle arrays obtained from obtain().
 */
internal object ByteArrayPool {
    // 4KB block buckets (4096, 8192, 12288, ...)
    private const val BLOCK = 4096
    private const val MAX_PER_BUCKET = 8
    private const val MAX_BUCKETS = 64

    private val lock = Any()
    private val buckets: LinkedHashMap<Int, ArrayDeque<ByteArray>> =
        object : LinkedHashMap<Int, ArrayDeque<ByteArray>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ArrayDeque<ByteArray>>?): Boolean {
                return size > MAX_BUCKETS
            }
        }

    fun obtain(size: Int): ByteArray {
        val bucketSize = bucketOf(size)
        synchronized(lock) {
            val q = buckets[bucketSize]
            if (q != null && q.isNotEmpty()) {
                return q.removeFirst()
            }
        }
        return ByteArray(bucketSize)
    }

    fun recycle(buf: ByteArray) {
        val bucketSize = bucketOf(buf.size)
        // Only recycle exact bucket-sized arrays to keep things predictable
        if (buf.size != bucketSize) return

        synchronized(lock) {
            val q = buckets.getOrPut(bucketSize) { ArrayDeque() }
            if (q.size >= MAX_PER_BUCKET) return
            q.addLast(buf)
        }
    }

    private fun bucketOf(size: Int): Int {
        if (size <= 0) return BLOCK
        val blocks = (size + BLOCK - 1) / BLOCK
        return blocks * BLOCK
    }
}
