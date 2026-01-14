package org.autojs.autojs.autojs

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.annotation.NonNull
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.stardust.app.GlobalAppContext
import com.stardust.autojs.core.console.GlobalConsole
import com.stardust.autojs.runtime.ScriptRuntimeV2
import com.stardust.autojs.runtime.accessibility.AccessibilityConfig
import com.stardust.autojs.runtime.api.AppUtils
import com.stardust.autojs.runtime.exception.ScriptException
import com.stardust.autojs.runtime.exception.ScriptInterruptedException
import com.stardust.view.accessibility.AccessibilityService
import com.stardust.view.accessibility.LayoutInspector
import com.stardust.view.accessibility.NodeInfo
import org.autojs.autoxjs.BuildConfig
import org.autojs.autojs.Pref
import org.autojs.autoxjs.R
import org.autojs.autojs.devplugin.DevPlugin
import org.autojs.autojs.external.fileprovider.AppFileProvider
import org.autojs.autojs.tool.AccessibilityServiceTool
import org.autojs.autojs.ui.floating.FloatyWindowManger
import org.autojs.autojs.ui.floating.FullScreenFloatyWindow
import org.autojs.autojs.ui.floating.layoutinspector.LayoutBoundsFloatyWindow
import org.autojs.autojs.ui.floating.layoutinspector.LayoutHierarchyFloatyWindow
import org.autojs.autojs.ui.log.LogActivityKt
import org.autojs.autojs.ui.settings.SettingsActivity

/**
 * Created by Stardust on 2017/4/2.
 */
class AutoJs private constructor(application: Application) : com.stardust.autojs.AutoJs(application) {

    private var enableDebugLog = false

    private interface LayoutInspectFloatyWindow {
        fun create(nodeInfo: NodeInfo): FullScreenFloatyWindow
    }

    private val layoutInspectBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                ensureAccessibilityServiceEnabled()
                when (intent.action) {
                    LayoutBoundsFloatyWindow::class.java.name -> capture { node ->
                        LayoutBoundsFloatyWindow(node)
                    }

                    LayoutHierarchyFloatyWindow::class.java.name -> capture { node ->
                        LayoutHierarchyFloatyWindow(node)
                    }
                }
            } catch (e: Exception) {
                // 保持原语义：非主线程抛出，主线程吞掉避免崩
                if (Looper.myLooper() != Looper.getMainLooper()) throw e
            }
        }
    }

    init {
        // listener 单例化，避免重复 init 时无限增长
        scriptEngineService.registerGlobalScriptExecutionListener(GLOBAL_EXECUTION_LISTENER)

        val filter = IntentFilter().apply {
            addAction(LayoutBoundsFloatyWindow::class.java.name)
            addAction(LayoutHierarchyFloatyWindow::class.java.name)
        }
        LocalBroadcastManager.getInstance(application)
            .registerReceiver(layoutInspectBroadcastReceiver, filter)
    }

    /**
     * 如果你未来支持“重建 AutoJs instance”，建议在覆盖前调用它，避免重复注册 receiver。
     * 目前 initInstance 是幂等的，正常不会用到。
     */
    fun destroy() {
        try {
            LocalBroadcastManager.getInstance(application)
                .unregisterReceiver(layoutInspectBroadcastReceiver)
        } catch (_: Throwable) {
        }
    }

    private fun capture(window: (NodeInfo) -> FullScreenFloatyWindow) {
        val inspector: LayoutInspector = layoutInspector

        val listener = object : LayoutInspector.CaptureAvailableListener {
            override fun onCaptureAvailable(capture: NodeInfo?) {
                inspector.removeCaptureAvailableListener(this)

                if (capture == null) {
                    return
                }

                uiHandler.post {
                    FloatyWindowManger.addWindow(
                        application.applicationContext,
                        window(capture)
                    )
                }
            }
        }


        inspector.addCaptureAvailableListener(listener)
        if (!inspector.captureCurrentWindow()) {
            inspector.removeCaptureAvailableListener(listener)
        }
    }

    override fun createAppUtils(context: Context): AppUtils {
        return AppUtils(context, AppFileProvider.AUTHORITY)
    }

    override fun createGlobalConsole(): GlobalConsole {
        return object : GlobalConsole(uiHandler) {
            override fun println(level: Int, charSequence: CharSequence?): String? {
                val log = super.println(level, charSequence)
                if (log != null) {
                    DevPlugin.log(log)
                }
                return log
            }
        }
    }

    override fun ensureAccessibilityServiceEnabled() {
        if (AccessibilityService.instance != null) return

        var errorMessage: String? = null
        if (AccessibilityServiceTool.isAccessibilityServiceEnabled(GlobalAppContext.get())) {
            errorMessage =
                GlobalAppContext.getString(R.string.text_auto_operate_service_enabled_but_not_running)
        } else {
            errorMessage = if (Pref.shouldEnableAccessibilityServiceByRoot()) {
                if (!AccessibilityServiceTool.enableAccessibilityServiceByRootAndWaitFor(2000)) {
                    GlobalAppContext.getString(R.string.text_enable_accessibility_service_by_root_timeout)
                } else null
            } else {
                GlobalAppContext.getString(R.string.text_no_accessibility_permission)
            }
        }

        if (errorMessage != null) {
            AccessibilityServiceTool.goToAccessibilitySetting()
            throw ScriptException(errorMessage)
        }
    }

    override fun waitForAccessibilityServiceEnabled() {
        if (AccessibilityService.instance != null) return

        var errorMessage: String? = null
        if (AccessibilityServiceTool.isAccessibilityServiceEnabled(GlobalAppContext.get())) {
            errorMessage =
                GlobalAppContext.getString(R.string.text_auto_operate_service_enabled_but_not_running)
        } else {
            errorMessage = if (Pref.shouldEnableAccessibilityServiceByRoot()) {
                if (!AccessibilityServiceTool.enableAccessibilityServiceByRootAndWaitFor(2000)) {
                    GlobalAppContext.getString(R.string.text_enable_accessibility_service_by_root_timeout)
                } else null
            } else {
                GlobalAppContext.getString(R.string.text_no_accessibility_permission)
            }
        }

        if (errorMessage != null) {
            AccessibilityServiceTool.goToAccessibilitySetting()
            if (!AccessibilityService.waitForEnabled(-1)) {
                throw ScriptInterruptedException()
            }
        }
    }

    override fun createAccessibilityConfig(): AccessibilityConfig {
        val config = super.createAccessibilityConfig() ?: AccessibilityConfig()
        if (BuildConfig.CHANNEL == "coolapk") {
            config.addWhiteList("com.coolapk.market")
        }
        return config
    }

    @NonNull
    override fun createRuntime(): ScriptRuntimeV2 {
        val runtime = super.createRuntime()
        runtime.putProperty("class.settings", SettingsActivity::class.java)
        runtime.putProperty("class.console", LogActivityKt::class.java)
        runtime.putProperty("broadcast.inspect_layout_bounds", LayoutBoundsFloatyWindow::class.java.name)
        runtime.putProperty("broadcast.inspect_layout_hierarchy", LayoutHierarchyFloatyWindow::class.java.name)
        return runtime
    }

    companion object {

        // 单例 listener，避免重复 init 时不断注册新对象
        private val GLOBAL_EXECUTION_LISTENER by lazy { ScriptExecutionGlobalListener() }

        @JvmStatic
        fun getInstance(): com.stardust.autojs.AutoJs = com.stardust.autojs.AutoJs.instance

        /**
         * 幂等初始化：重复调用直接返回，不会重复注册 receiver/listener
         */
        @JvmStatic
        @Synchronized
        fun initInstance(application: Application) {
            val alreadyInit = runCatching { com.stardust.autojs.AutoJs.instance }.isSuccess
            if (alreadyInit) return
            com.stardust.autojs.AutoJs.instance = AutoJs(application)
        }
    }
}
