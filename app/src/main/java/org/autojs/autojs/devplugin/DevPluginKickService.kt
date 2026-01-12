package org.autojs.autojs.devplugin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class DevPluginKickService : Service() {

    companion object {
        private const val TAG = "DevPluginKickService"
        const val EXTRA_URL = "url"
        const val ACTION_CONNECT_IF_NEEDED = "connect_if_needed"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 防止短时间内多次 startService 导致并发 connect
    private val connecting = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val url = intent?.getStringExtra(EXTRA_URL)

        if (action != ACTION_CONNECT_IF_NEEDED || url.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                // 已连接：直接退出
                if (DevPlugin.isActive) {
                    Log.d(TAG, "Already connected, skip. url=$url")
                    return@launch
                }

                // 有人正在连：直接退出（让正在连接的那次完成）
                if (!connecting.compareAndSet(false, true)) {
                    Log.d(TAG, "Connecting in progress, skip. url=$url")
                    return@launch
                }

                // double-check：避免 race
                if (DevPlugin.isActive) {
                    Log.d(TAG, "Already connected after lock, skip. url=$url")
                    return@launch
                }

                Log.i(TAG, "Connecting... url=$url")
                DevPlugin.connect(url) // suspend
                Log.i(TAG, "Connect finished. active=${DevPlugin.isActive}")
            } catch (t: Throwable) {
                Log.e(TAG, "Connect failed", t)
            } finally {
                connecting.set(false)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
