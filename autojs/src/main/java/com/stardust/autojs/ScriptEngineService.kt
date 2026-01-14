package com.stardust.autojs

import android.content.Context
import android.util.Log
import com.stardust.autojs.engine.JavaScriptEngine
import com.stardust.autojs.engine.ScriptEngine
import com.stardust.autojs.engine.ScriptEngineManager
import com.stardust.autojs.engine.ScriptEngineManager.EngineLifecycleCallback
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.LoopedBasedJavaScriptExecution
import com.stardust.autojs.execution.RunnableScriptExecution
import com.stardust.autojs.execution.ScriptExecuteActivity
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.execution.ScriptExecutionListener
import com.stardust.autojs.execution.ScriptExecutionObserver
import com.stardust.autojs.execution.ScriptExecutionTask
import com.stardust.autojs.runtime.ScriptRuntime
import com.stardust.autojs.runtime.api.Console
import com.stardust.autojs.runtime.exception.ScriptInterruptedException
import com.stardust.autojs.script.JavaScriptSource
import com.stardust.autojs.script.ScriptSource
import com.stardust.lang.ThreadCompat
import com.stardust.util.UiHandler
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by Stardust on 2017/1/23.
 */
class ScriptEngineService internal constructor(builder: ScriptEngineServiceBuilder) {

    private val mUiHandler: UiHandler = builder.mUiHandler
    private val mContext: Context = mUiHandler.context
    val globalConsole: Console = builder.mGlobalConsole
    private val mScriptEngineManager: ScriptEngineManager = builder.mScriptEngineManager

    // 并发安全：脚本线程/回调线程/UI线程都可能访问
    private val mScriptExecutions = ConcurrentHashMap<Int, ScriptExecution>()

    private val mEngineLifecycleObserver: EngineLifecycleObserver =
        object : EngineLifecycleObserver() {
            override fun onEngineRemove(engine: ScriptEngine<*>?) {
                engine?.let { mScriptExecutions.remove(it.id) }
                super.onEngineRemove(engine)
            }
        }

    private val mScriptExecutionObserver = ScriptExecutionObserver()

    private val disposable: Disposable = executionEventPublish.subscribe { event ->
        when (event.code) {
            ScriptExecutionEvent.ON_START -> {
                globalConsole.verbose(
                    mContext.getString(R.string.text_start_running) + "[" + event.message + "]"
                )
            }

            ScriptExecutionEvent.ON_EXCEPTION -> {
                mUiHandler.toast(mContext.getString(R.string.text_error) + ": " + event.message)
            }
        }
    }

    init {
        mScriptEngineManager.setEngineLifecycleCallback(mEngineLifecycleObserver)
        mScriptExecutionObserver.registerScriptExecutionListener(GLOBAL_LISTENER)

        // context 建议使用 applicationContext，避免意外泄漏
        mScriptEngineManager.putGlobal("context", mContext.applicationContext)
        ScriptRuntime.setApplicationContext(mContext.applicationContext)
    }

    /**
     * 建议在你有“重启引擎/重启服务”的路径里调用一次，避免重复订阅泄漏
     */
    fun destroy() {
        try {
            disposable.dispose()
        } catch (_: Throwable) {
        }
    }

    fun registerEngineLifecycleCallback(engineLifecycleCallback: EngineLifecycleCallback) {
        mEngineLifecycleObserver.registerCallback(engineLifecycleCallback)
    }

    fun unregisterEngineLifecycleCallback(engineLifecycleCallback: EngineLifecycleCallback) {
        mEngineLifecycleObserver.unregisterCallback(engineLifecycleCallback)
    }

    fun registerGlobalScriptExecutionListener(listener: ScriptExecutionListener): Boolean {
        return mScriptExecutionObserver.registerScriptExecutionListener(listener)
    }

    fun unregisterGlobalScriptExecutionListener(listener: ScriptExecutionListener): Boolean {
        return mScriptExecutionObserver.removeScriptExecutionListener(listener)
    }

    fun setupExecutionTaskListener(task: ScriptExecutionTask) {
        if (task.listener != null) {
            task.setExecutionListener(
                ScriptExecutionObserver.Wrapper(
                    mScriptExecutionObserver,
                    task.listener
                )
            )
        } else {
            task.setExecutionListener(mScriptExecutionObserver)
        }
    }

    fun execute(task: ScriptExecutionTask): ScriptExecution {
        val execution = executeInternal(task)
        mScriptExecutions[execution.id] = execution
        return execution
    }

    fun createScriptExecution(task: ScriptExecutionTask): ScriptExecution {
        val source = task.source
        if (source is JavaScriptSource) {
            val mode = source.executionMode
            if (mode and JavaScriptSource.EXECUTION_MODE_UI != 0) {
                return ScriptExecuteActivity.ActivityScriptExecution(mScriptEngineManager, task)
            }
        }
        return when (source) {
            is JavaScriptSource -> LoopedBasedJavaScriptExecution(mScriptEngineManager, task)
            else -> RunnableScriptExecution(mScriptEngineManager, task)
        }
    }

    fun startScriptExecution(scriptExecution: ScriptExecution) {
        if (scriptExecution is RunnableScriptExecution) {
            ThreadCompat(scriptExecution).start()
        } else if (scriptExecution is ScriptExecuteActivity.ActivityScriptExecution) {
            ScriptExecuteActivity.start(mContext, scriptExecution)
        }
    }

    // 脚本启动入口
    private fun executeInternal(task: ScriptExecutionTask): ScriptExecution {
        setupExecutionTaskListener(task)
        val execution = createScriptExecution(task)
        startScriptExecution(execution)
        return execution
    }

    fun execute(
        source: ScriptSource,
        listener: ScriptExecutionListener?,
        config: ExecutionConfig = ExecutionConfig()
    ): ScriptExecution {
        return execute(ScriptExecutionTask(source, listener, config))
    }

    fun execute(
        source: ScriptSource,
        config: ExecutionConfig = ExecutionConfig()
    ): ScriptExecution {
        return execute(ScriptExecutionTask(source, null, config))
    }

    fun stopAll(): Int {
        return mScriptEngineManager.stopAll()
    }

    fun stopAllAndToast() {
        val n = stopAll()
        if (n > 0) mUiHandler.toast(
            mContext.getString(R.string.text_already_stop_n_scripts, n),
        )
    }

    val engines: Set<ScriptEngine<*>>
        get() = mScriptEngineManager.engines

    val scriptExecutions: Collection<ScriptExecution>
        get() = mScriptExecutions.values

    fun getScriptExecution(id: Int): ScriptExecution? {
        return if (id == ScriptExecution.NO_ID) null else mScriptExecutions[id]
    }

    private open class EngineLifecycleObserver : EngineLifecycleCallback {
        private val callbacks = LinkedHashSet<EngineLifecycleCallback>()
        private val lock = Any()

        override fun onEngineCreate(engine: ScriptEngine<*>?) {
            val snapshot = synchronized(lock) { callbacks.toList() }
            for (cb in snapshot) cb.onEngineCreate(engine)
        }

        override fun onEngineRemove(engine: ScriptEngine<*>?) {
            val snapshot = synchronized(lock) { callbacks.toList() }
            for (cb in snapshot) cb.onEngineRemove(engine)
        }

        fun registerCallback(callback: EngineLifecycleCallback) {
            synchronized(lock) { callbacks.add(callback) }
        }

        fun unregisterCallback(callback: EngineLifecycleCallback) {
            synchronized(lock) { callbacks.remove(callback) }
        }
    }

    class ScriptExecutionEvent internal constructor(val code: Int, val message: String) {
        companion object {
            const val ON_START = 1001
            const val ON_SUCCESS = 1002
            const val ON_EXCEPTION = 1003
        }
    }

    companion object {
        private const val LOG_TAG = "ScriptEngineService"

        // 仍保持静态 Subject（不改外部行为），但实例必须可 dispose，且 instance setter 幂等
        private val executionEventPublish = PublishSubject.create<ScriptExecutionEvent>()

        private val GLOBAL_LISTENER: ScriptExecutionListener =
            object : ScriptExecutionListener {
                override fun onStart(execution: ScriptExecution) {
                    if (execution.engine is JavaScriptEngine) {
                        (execution.engine as JavaScriptEngine).runtime.console.setTitle(
                            execution.source.name,
                            "#ffffffff",
                            -1
                        )
                    }
                    executionEventPublish.onNext(
                        ScriptExecutionEvent(
                            ScriptExecutionEvent.ON_START,
                            execution.source.toString()
                        )
                    )
                }

                override fun onSuccess(execution: ScriptExecution?, result: Any?) {
                    onFinish(execution)
                }

                private fun onFinish(execution: ScriptExecution?) {
                    // no-op (保持原逻辑)
                }

                override fun onException(execution: ScriptExecution, e: Throwable) {
                    Log.e(LOG_TAG, e.stackTraceToString())
                    onFinish(execution)

                    var message: String? = null
                    val engine = execution.engine
                    if (!ScriptInterruptedException.causedByInterrupted(e)) {
                        message = e.message
                        if (engine is JavaScriptEngine) {
                            engine.runtime.console.error(e)
                        }
                    }
                    if (engine is JavaScriptEngine) {
                        val uncaughtException = engine.uncaughtException
                        if (uncaughtException != null) {
                            engine.runtime.console.error(uncaughtException)
                            message = uncaughtException.message
                        }
                    }
                    if (message != null) {
                        executionEventPublish.onNext(
                            ScriptExecutionEvent(
                                ScriptExecutionEvent.ON_EXCEPTION,
                                message
                            )
                        )
                    }
                }
            }

        @Volatile
        private var sInstance: ScriptEngineService? = null

        @JvmStatic
        var instance: ScriptEngineService?
            get() = sInstance
            set(service) {
                if (sInstance != null) {
                    Log.w(LOG_TAG, "ScriptEngineService.instance already set; ignoring new instance.")
                    return
                }
                sInstance = service
            }
    }
}
