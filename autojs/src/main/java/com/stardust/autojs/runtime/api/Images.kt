package com.stardust.autojs.runtime.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import android.view.Gravity
import androidx.core.graphics.createBitmap
import com.stardust.autojs.annotation.ScriptVariable
import com.stardust.autojs.core.image.ColorFinder
import com.stardust.autojs.core.image.ImageWrapper
import com.stardust.autojs.core.image.TemplateMatching
import com.stardust.autojs.core.image.capture.ScreenCaptureRequester
import com.stardust.autojs.core.opencv.Mat
import com.stardust.autojs.core.opencv.OpenCVHelper
import com.stardust.autojs.core.ui.inflater.util.Drawables
import com.stardust.autojs.runtime.ScriptRuntime
import com.stardust.pio.UncheckedIOException
import com.stardust.util.ScreenMetrics
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Created by Stardust on 2017/5/20.
 */
class Images(
    private val mContext: Context,
    private val mScriptRuntime: ScriptRuntime,
    private val mScreenCaptureRequester: ScreenCaptureRequester
) {
    private val appContext: Context = mContext.applicationContext
    private val mScreenMetrics: ScreenMetrics = mScriptRuntime.screenMetrics
    private val disposables = mutableListOf<Disposable>()

    @ScriptVariable
    val colorFinder: ColorFinder = ColorFinder(mScreenMetrics)

    /**
     * 记住最近一次请求截图时的方向；脚本只调用 captureScreen() 时也能用它来重连
     */
    @Volatile
    private var lastOrientation: Int =
        com.stardust.autojs.core.image.capture.ScreenCapturer.ORIENTATION_AUTO

    /**
     * 显式请求截图权限（脚本常用）
     */
    fun requestScreenCapture(orientation: Int): Boolean = runBlocking {
        try {
            lastOrientation = orientation
            mScreenCaptureRequester.requestScreenCapture(appContext, orientation)

            // 预热：最多等一小会儿拿首帧，降低后续首帧抖动；拿不到也别卡太久
            warmUpCapture(maxMs = 450)

            true
        } catch (e: Throwable) {
            mScriptRuntime.toast(e.message)
            Log.e(Images::class.java.name, "请求截图权限失败", e)
            false
        }
    }

    fun stopScreenCapturer() {
        mScreenCaptureRequester.recycle()
    }

    /**
     * 同步拿一张截图：
     * - 若锁屏/系统 stop 导致丢失：会自动 request（无感重连优先，必要时弹授权）
     * - single-flight/timeout/reconnect 都由 ScreenCaptureManager 统一处理
     */
    fun captureScreen(): ImageWrapper {
        val sc0 = mScreenCaptureRequester.screenCapture
        if (sc0 != null && sc0.available) {
            return runBlocking { sc0.captureImageWrapper() }
        }

        runBlocking {
            mScreenCaptureRequester.requestScreenCapture(appContext, lastOrientation)
        }

        val sc = mScreenCaptureRequester.screenCapture
        checkNotNull(sc) { SecurityException("No screen capture permission") }
        if (!sc.available) throw IllegalStateException("ScreenCapturer is not available")
        return runBlocking { sc.captureImageWrapper() }
    }

    /**
     * 严格版异步订阅：不自动弹授权/不自动重连（避免后台弹 UI）
     * - 要求脚本先显式 requestScreenCapture()
     */
    fun registerAsyncCapture(onNext: Consumer<ImageWrapper>): Disposable {
        val sc = mScreenCaptureRequester.screenCapture
        checkNotNull(sc) { SecurityException("No screen capture permission") }
        if (!sc.available) throw IllegalStateException("ScreenCapturer is not available")

        val scheduler = AndroidSchedulers.from(mScriptRuntime.loopers.servantLooper)
        var disposable: Disposable? = null
        disposable = sc.registerAsyncCapture(scheduler, { img ->
            try {
                onNext.accept(img)
            } catch (e: Throwable) {
                disposable?.dispose()
                mScriptRuntime.exit(e)
            }
        }).also { disposables.add(it) }

        return disposable
    }

    fun captureScreen(path: String): Boolean {
        val rpath = mScriptRuntime.files.path(path)
        val image = captureScreen()
        image.saveTo(rpath)
        return true
    }

    /**
     * 释放异步订阅
     */
    fun releaseScreenCapturer() {
        disposables.forEach { it.dispose() }
        disposables.clear()
    }

    fun copy(image: ImageWrapper): ImageWrapper = image.clone()

    @Throws(IOException::class)
    fun save(image: ImageWrapper, path: String?, format: String, quality: Int): Boolean {
        val compressFormat = parseImageFormat(format)
            ?: throw IllegalArgumentException("unknown format $format")
        val bitmap = image.getBitmap()
        val outputStream = FileOutputStream(mScriptRuntime.files.path(path))
        return outputStream.use { out ->
            val ok = bitmap.compress(compressFormat, quality, out)
            out.flush()
            ok
        }
    }

    fun rotate(img: ImageWrapper, x: Float, y: Float, degree: Float): ImageWrapper {
        val matrix = Matrix().apply { postRotate(degree, x, y) }
        return ImageWrapper.ofBitmap(
            Bitmap.createBitmap(
                img.getBitmap(),
                0,
                0,
                img.getWidth(),
                img.getHeight(),
                matrix,
                true
            )
        )
    }

    fun clip(img: ImageWrapper, x: Int, y: Int, w: Int, h: Int): ImageWrapper? {
        return ImageWrapper.ofBitmap(Bitmap.createBitmap(img.getBitmap(), x, y, w, h))
    }

    fun read(path: String): ImageWrapper? {
        val bitmap = BitmapFactory.decodeFile(mScriptRuntime.files.path(path))
        return ImageWrapper.ofBitmap(bitmap)
    }

    fun fromBase64(data: String): ImageWrapper? = ImageWrapper.ofBitmap(Drawables.loadBase64Data(data))

    fun toBase64(wrapper: ImageWrapper, format: String, quality: Int): String {
        return Base64.encodeToString(toBytes(wrapper, format, quality), Base64.NO_WRAP)
    }

    fun toBytes(wrapper: ImageWrapper, format: String, quality: Int): ByteArray {
        val compressFormat = parseImageFormat(format)
            ?: throw IllegalArgumentException("unknown format $format")
        val bitmap = wrapper.getBitmap()
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(compressFormat, quality, outputStream)
        return outputStream.toByteArray()
    }

    fun fromBytes(bytes: ByteArray): ImageWrapper {
        return ImageWrapper.ofBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun parseImageFormat(format: String): CompressFormat? = when (format) {
        "png" -> CompressFormat.PNG
        "jpeg", "jpg" -> CompressFormat.JPEG
        "webp" -> CompressFormat.WEBP
        else -> null
    }

    fun load(src: String): ImageWrapper? {
        return try {
            val url = URL(src)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                doInput = true
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
            }
            connection.connect()
            connection.inputStream.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                ImageWrapper.ofBitmap(bitmap)
            }
        } catch (_: IOException) {
            null
        }
    }

    @JvmOverloads
    fun findImage(
        image: ImageWrapper?,
        template: ImageWrapper?,
        threshold: Float = 0.9f,
        rect: Rect? = null
    ): Point? {
        return findImage(image, template, 0.7f, threshold, rect, TemplateMatching.MAX_LEVEL_AUTO)
    }

    @JvmOverloads
    fun findImage(
        image: ImageWrapper?,
        template: ImageWrapper?,
        weakThreshold: Float,
        threshold: Float,
        rect: Rect?,
        maxLevel: Int,
        transparentMask: Boolean = false
    ): Point? {
        initOpenCvIfNeeded()
        if (image == null) throw NullPointerException("image = null")
        if (template == null) throw NullPointerException("template = null")

        val full = image.getMat()
        val src = if (rect != null) Mat(full, rect) else full

        val point = TemplateMatching.fastTemplateMatching(
            src,
            template.getMat(),
            TemplateMatching.MATCHING_METHOD_DEFAULT,
            weakThreshold,
            threshold,
            maxLevel,
            transparentMask
        )

        if (point != null) {
            if (rect != null) {
                point.x += rect.x.toDouble()
                point.y += rect.y.toDouble()
            }
            point.x = mScreenMetrics.scaleX(point.x.toInt()).toDouble()
            point.y = mScreenMetrics.scaleY(point.y.toInt()).toDouble() // 修复：Y 用 scaleY
        }

        if (src !== full) {
            OpenCVHelper.release(src)
        }
        return point
    }

    @JvmOverloads
    fun matchTemplate(
        image: ImageWrapper?,
        template: ImageWrapper?,
        weakThreshold: Float,
        threshold: Float,
        rect: Rect?,
        maxLevel: Int,
        limit: Int,
        transparentMask: Boolean = false
    ): List<TemplateMatching.Match> {
        initOpenCvIfNeeded()
        if (image == null) throw NullPointerException("image = null")
        if (template == null) throw NullPointerException("template = null")

        val full = image.getMat()
        val src = if (rect != null) Mat(full, rect) else full

        val result = TemplateMatching.fastTemplateMatching(
            src,
            template.getMat(),
            Imgproc.TM_CCOEFF_NORMED,
            weakThreshold,
            threshold,
            maxLevel,
            limit,
            transparentMask
        )

        for (match in result) {
            val p = match.point
            if (rect != null) {
                p.x += rect.x.toDouble()
                p.y += rect.y.toDouble()
            }
            p.x = mScreenMetrics.scaleX(p.x.toInt()).toDouble()
            p.y = mScreenMetrics.scaleY(p.y.toInt()).toDouble()
        }

        if (src !== full) {
            OpenCVHelper.release(src)
        }
        return result
    }

    fun newMat(): Mat = Mat()

    fun newMat(mat: Mat?, roi: Rect?): Mat = Mat(mat, roi)

    fun initOpenCvIfNeeded() {
        if (OpenCVHelper.isInitialized.isCompleted) return

        val currentActivity = mScriptRuntime.app.currentActivity
        val context = currentActivity ?: appContext
        mScriptRuntime.console.info("opencv initializing")
        OpenCVHelper.initIfNeeded(context)
        mScriptRuntime.console.info("opencv initialized")
    }

    fun pixel(image: ImageWrapper?, x: Int, y: Int): Int {
        if (image == null) throw NullPointerException("image = null")
        return image.pixel(x, y)
    }

    fun concat(img1: ImageWrapper, img2: ImageWrapper, direction: Int): ImageWrapper {
        var a = img1
        var b = img2
        require(
            direction == Gravity.LEFT ||
                direction == Gravity.RIGHT ||
                direction == Gravity.TOP ||
                direction == Gravity.BOTTOM
        ) { "unknown direction $direction" }

        if (direction == Gravity.LEFT || direction == Gravity.TOP) {
            val tmp = a
            a = b
            b = tmp
        }

        val width: Int
        val height: Int
        if (direction == Gravity.LEFT || direction == Gravity.RIGHT) {
            width = a.getWidth() + b.getWidth()
            height = maxOf(a.getHeight(), b.getHeight())
        } else {
            width = maxOf(a.getWidth(), b.getWidth())
            height = a.getHeight() + b.getHeight()
        }

        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        if (direction == Gravity.LEFT || direction == Gravity.RIGHT) {
            canvas.drawBitmap(a.getBitmap(), 0f, ((height - a.getHeight()) / 2f), paint)
            canvas.drawBitmap(b.getBitmap(), a.getWidth().toFloat(), ((height - b.getHeight()) / 2f), paint)
        } else {
            canvas.drawBitmap(a.getBitmap(), ((width - a.getWidth()) / 2f), 0f, paint)
            canvas.drawBitmap(b.getBitmap(), ((width - b.getWidth()) / 2f), a.getHeight().toFloat(), paint)
        }
        return ImageWrapper.ofBitmap(bitmap)
    }

    fun saveBitmap(bitmap: Bitmap, path: String?) {
        try {
            bitmap.compress(CompressFormat.PNG, 100, FileOutputStream(path))
        } catch (e: FileNotFoundException) {
            throw UncheckedIOException(e)
        }
    }

    fun scaleBitmap(origin: Bitmap?, newWidth: Int, newHeight: Int): Bitmap? {
        if (origin == null) return null
        val width = origin.width
        val height = origin.height
        val matrix = Matrix().apply {
            postScale(newWidth.toFloat() / width, newHeight.toFloat() / height)
        }
        return Bitmap.createBitmap(origin, 0, 0, width, height, matrix, false)
    }

    private suspend fun warmUpCapture(maxMs: Long) {
        val sc = mScreenCaptureRequester.screenCapture ?: return
        if (!sc.available) return

        try {
            withTimeout(maxMs) {
                sc.captureImageWrapper()
            }
        } catch (_: TimeoutCancellationException) {
            // ignore
        } catch (_: Throwable) {
            // ignore
        }
    }
}
