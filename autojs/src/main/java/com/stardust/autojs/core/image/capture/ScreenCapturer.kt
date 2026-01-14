package com.stardust.autojs.core.image.capture

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.stardust.autojs.core.image.ImageWrapper
import com.stardust.util.ScreenMetrics
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class ScreenCapturer(
    private val mediaProjection: MediaProjection,
    orientation: Int = 0,
    private val screenDensity: Int = ScreenMetrics.getDeviceScreenDensity(),
) {
    private var virtualDisplay: VirtualDisplay
    private var imageReader: ImageReader

    private val imageThread = HandlerThread("ScreenCapturer-Image").apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    private val executor = Executors.newSingleThreadExecutor()

    private val cachedImageBitmap = AtomicReference<Bitmap?>()
    private val latestImage = AtomicReference<Image?>()
    private val publishSubject = PublishSubject.create<ImageWrapper>()

    @Volatile
    var available = true
        private set

    private var detectedOrientation = 0

    init {
        val h = ScreenMetrics.getOrientationAwareScreenHeight(orientation)
        val w = ScreenMetrics.getOrientationAwareScreenWidth(orientation)
        imageReader = createImageReader(w, h)
        virtualDisplay = createVirtualDisplay(w, h, screenDensity)
    }

    private fun createImageReader(width: Int, height: Int): ImageReader {
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    if (!publishSubject.hasObservers()) {
                        latestImage.getAndSet(image)?.close()
                        return@setOnImageAvailableListener
                    }
                    executor.submit {
                        try {
                            val bmp = ImageWrapper.toBitmap(image)
                            cachedImageBitmap.set(bmp)
                            publishSubject.onNext(ImageWrapper.ofBitmap(bmp))
                        } catch (_: Throwable) {
                        } finally {
                            try { image.close() } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {
                }
            }, imageHandler)
        }
    }

    private fun createVirtualDisplay(width: Int, height: Int, density: Int): VirtualDisplay {
        return mediaProjection.createVirtualDisplay(
            LOG_TAG,
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )
    }

    fun setOrientation(orientation: Int, context: Context) {
        detectedOrientation = context.resources.configuration.orientation
        val target = if (orientation == ORIENTATION_AUTO) detectedOrientation else orientation
        refreshVirtualDisplay(target)
    }

    /**
     * ⚠️ 不能对同一个 MediaProjection 多次 createVirtualDisplay（某些系统会直接 SecurityException）
     * 所以这里采用：重建 ImageReader + virtualDisplay.resize + virtualDisplay.surface=新 surface
     */
    private fun refreshVirtualDisplay(orientation: Int) = synchronized(this) {
        if (!available) return@synchronized

        latestImage.getAndSet(null)?.close()

        val h = ScreenMetrics.getOrientationAwareScreenHeight(orientation)
        val w = ScreenMetrics.getOrientationAwareScreenWidth(orientation)

        val oldReader = imageReader
        val newReader = try {
            createImageReader(w, h)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "createImageReader failed: ${t.message}")
            return@synchronized
        }

        imageReader = newReader

        try {
            // 先换 surface 再 resize（顺序不同 ROM 表现不一，建议两步都做）
            virtualDisplay.surface = newReader.surface
            virtualDisplay.resize(w, h, screenDensity)
        } catch (t: Throwable) {
            // 这里不能 fallback createVirtualDisplay（会再次触发 SecurityException）
            Log.w(LOG_TAG, "VirtualDisplay resize/surface failed: ${t.message}")
            // 回滚到旧 reader，尽量保持可用
            try { newReader.close() } catch (_: Throwable) {}
            imageReader = oldReader
            return@synchronized
        }

        try { oldReader.close() } catch (_: Throwable) {}
    }

    fun capture(): Image? {
        if (!available) throw Exception("ScreenCapturer is not available")
        return latestImage.getAndSet(null)
    }

    fun registerAsyncCapture(scheduler: Scheduler, onNext: Consumer<ImageWrapper>): Disposable {
        val eventProcessing = AtomicReference(false)
        return publishSubject
            .filter { eventProcessing.getAndSet(true) != true }
            .observeOn(scheduler)
            .subscribe({
                try { onNext.accept(it) } finally { eventProcessing.set(false) }
            }, { eventProcessing.set(false) })
    }

    fun createImageWrapper(image: Image): ImageWrapper {
        val bitmap = ImageWrapper.toBitmap(image)
        try { image.close() } catch (_: Throwable) {}
        cachedImageBitmap.set(bitmap)
        return ImageWrapper.ofBitmap(bitmap)
    }

    suspend fun captureImageWrapper(): ImageWrapper {
        val direct = synchronized(this) { capture()?.let { createImageWrapper(it) } }
        if (direct != null) return direct

        cachedImageBitmap.get()?.let {
            Log.i(LOG_TAG, "Using cached image")
            return ImageWrapper.ofBitmap(it)
        }

        return withTimeout(2000) {
            var result: ImageWrapper? = null
            while (result == null) {
                delay(200)
                result = synchronized(this@ScreenCapturer) {
                    capture()?.let { createImageWrapper(it) }
                }
            }
            result
        }
    }

    fun release() = synchronized(this) {
        if (!available) return@synchronized
        available = false

        try { latestImage.getAndSet(null)?.close() } catch (_: Throwable) {}
        try { virtualDisplay.release() } catch (_: Throwable) {}
        try { imageReader.close() } catch (_: Throwable) {}
        try { executor.shutdownNow() } catch (_: Throwable) {}
        try { imageThread.quitSafely() } catch (_: Throwable) {}

        cachedImageBitmap.set(null)
        try { publishSubject.onComplete() } catch (_: Throwable) {}
    }

    companion object {
        @JvmStatic val ORIENTATION_AUTO = Configuration.ORIENTATION_UNDEFINED
        @JvmStatic val ORIENTATION_LANDSCAPE = Configuration.ORIENTATION_LANDSCAPE
        @JvmStatic val ORIENTATION_PORTRAIT = Configuration.ORIENTATION_PORTRAIT
        private const val LOG_TAG = "ScreenCapturer"
    }
}
