package com.stardust.autojs.core.image.capture

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import com.github.aiselp.autox.activity.TransparentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

class ScreenCaptureManager : ScreenCaptureRequester {

    @Volatile
    override var screenCapture: ScreenCapturer? = null

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var lastResultData: Intent? = null

    // single-flight：并发 request 共享一次执行
    private val inFlightRef = AtomicReference<CompletableDeferred<Unit>?>(null)

    // MediaProjection.Callback 唯一 owner：只在 Manager 注册
    private val mpCallbackHandler = Handler(Looper.getMainLooper())
    private val mpCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system")
            handleProjectionStopped()
        }
    }

    override suspend fun requestScreenCapture(context: Context, orientation: Int) {
        val appCtx = context.applicationContext

        // 快路径：已可用，仅更新方向（这里不会 createVirtualDisplay，只会 resize/surface 切换）
        screenCapture?.let { sc ->
            if (sc.available) {
                sc.setOrientation(orientation, appCtx)
                return
            }
        }

        // single-flight：有进行中的请求就等待
        inFlightRef.get()?.let { it.await() }
        screenCapture?.let { sc ->
            if (sc.available) {
                sc.setOrientation(orientation, appCtx)
                return
            }
        }

        // 我来发起 single-flight
        val mine = CompletableDeferred<Unit>()
        if (!inFlightRef.compareAndSet(null, mine)) {
            inFlightRef.get()?.await()
            screenCapture?.let { sc ->
                if (sc.available) {
                    sc.setOrientation(orientation, appCtx)
                    return
                }
            }
            // 继续往下发起（极少发生）
        } else {
            try {
                // 1) 优先无感重连（不弹授权）
                if (tryReconnectWithLastData(appCtx, orientation)) {
                    mine.complete(Unit)
                    return
                }

                // 2) 弹授权
                val resultIntent = requestPermissionViaTransparentActivity(appCtx)
                lastResultData = resultIntent

                // 3) 确保前台服务已启动
                ensureForegroundServiceReady(appCtx)

                // 4) 创建 projection + capturer
                val mpm = appCtx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mp = mpm.getMediaProjection(Activity.RESULT_OK, resultIntent)
                checkNotNull(mp) { "getMediaProjection returned null" }

                attachMediaProjection(mp)

                // ⚠️ 这里构造时已经使用 orientation 了，后面不要再 setOrientation 兜底，否则会二次 createVirtualDisplay
                screenCapture = ScreenCapturer(
                    mediaProjection = mp,
                    orientation = orientation
                )

                mine.complete(Unit)
            } catch (t: Throwable) {
                mine.completeExceptionally(t)
                throw t
            } finally {
                inFlightRef.compareAndSet(mine, null)
            }
        }

        // 兜底：只检查，不再 setOrientation
        val sc = screenCapture
        checkNotNull(sc) { SecurityException("No screen capture permission") }
        if (!sc.available) throw IllegalStateException("ScreenCapturer is not available")
    }

    suspend fun tryReconnectIfPossible(context: Context, orientation: Int): Boolean {
        val appCtx = context.applicationContext
        if (screenCapture?.available == true) return true
        return tryReconnectWithLastData(appCtx, orientation)
    }

    private suspend fun tryReconnectWithLastData(context: Context, orientation: Int): Boolean {
        val data = lastResultData ?: return false
        return try {
            ensureForegroundServiceReady(context)

            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(Activity.RESULT_OK, data) ?: return false

            attachMediaProjection(mp)

            screenCapture = ScreenCapturer(
                mediaProjection = mp,
                orientation = orientation
            )
            true
        } catch (t: Throwable) {
            // 如果系统不允许复用 token，清掉避免下次重复失败
            val msg = (t.message ?: "").lowercase()
            if (msg.contains("reuse") || msg.contains("resultdata") || msg.contains("result data")) {
                lastResultData = null
            }
            false
        }
    }

    private fun attachMediaProjection(mp: MediaProjection) {
        detachMediaProjection()
        mediaProjection = mp
        try {
            mp.registerCallback(mpCallback, mpCallbackHandler)
        } catch (_: Throwable) {
        }
    }

    private fun detachMediaProjection() {
        val mp = mediaProjection ?: return
        try {
            mp.unregisterCallback(mpCallback)
        } catch (_: Throwable) {
        }
        mediaProjection = null
    }

    private fun handleProjectionStopped() {
        try {
            screenCapture?.release()
        } catch (_: Throwable) {
        }
        screenCapture = null
        detachMediaProjection()
    }

    private suspend fun requestPermissionViaTransparentActivity(context: Context): Intent {
        val d = CompletableDeferred<Intent>()
        TransparentActivity.requestNewActivity(context) { activity ->
            activity.registerForActivityResult(ScreenCaptureRequester()) { data ->
                activity.finish()
                if (data != null) d.complete(data)
                else d.completeExceptionally(CancellationException("data is null"))
            }.launch(activity)
        }
        return d.await()
    }

    private suspend fun ensureForegroundServiceReady(context: Context) {
        val intent = Intent(context, CaptureForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        val ready = CompletableDeferred<Unit>()
        var bound = false

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                ready.complete(Unit)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (!ready.isCompleted) ready.completeExceptionally(
                    IllegalStateException("Service disconnected unexpectedly")
                )
            }
            override fun onNullBinding(name: ComponentName?) {
                ready.complete(Unit)
            }
        }

        try {
            bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!bound) return
            withTimeout(5_000) { ready.await() }
        } finally {
            if (bound) {
                try { context.unbindService(conn) } catch (_: Throwable) {}
            }
        }
    }

    class ScreenCaptureRequester : ActivityResultContract<Context, Intent?>() {
        override fun createIntent(context: Context, input: Context): Intent {
            return (input.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .createScreenCaptureIntent()
        }
        override fun parseResult(resultCode: Int, intent: Intent?): Intent? {
            return if (resultCode != Activity.RESULT_OK) null else intent
        }
    }

    override fun recycle() {
        try { screenCapture?.release() } catch (_: Throwable) {}
        screenCapture = null

        try { mediaProjection?.stop() } catch (_: Throwable) {}
        detachMediaProjection()
        // lastResultData 可保留或按需清空
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
}
