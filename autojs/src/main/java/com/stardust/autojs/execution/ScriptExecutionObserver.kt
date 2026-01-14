package com.stardust.autojs.execution

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Created by Stardust on 2017/5/3.
 */
class ScriptExecutionObserver : ScriptExecutionListener {

    private val scriptExecutionListeners = CopyOnWriteArraySet<ScriptExecutionListener>()

    override fun onStart(execution: ScriptExecution) {
        for (listener in scriptExecutionListeners) {
            listener.onStart(execution)
        }
    }

    override fun onSuccess(execution: ScriptExecution, result: Any?) {
        for (listener in scriptExecutionListeners) {
            listener.onSuccess(execution, result)
        }
    }

    override fun onException(execution: ScriptExecution, e: Throwable) {
        for (listener in scriptExecutionListeners) {
            listener.onException(execution, e)
        }
    }

    fun registerScriptExecutionListener(listener: ScriptExecutionListener): Boolean {
        return scriptExecutionListeners.add(listener)
    }

    fun removeScriptExecutionListener(listener: ScriptExecutionListener): Boolean {
        return scriptExecutionListeners.remove(listener)
    }

    class Wrapper(
        private val scriptExecutionObserver: ScriptExecutionObserver,
        private val scriptExecutionListener: ScriptExecutionListener
    ) : ScriptExecutionListener {

        override fun onStart(execution: ScriptExecution) {
            scriptExecutionListener.onStart(execution)
            scriptExecutionObserver.onStart(execution)
        }

        override fun onSuccess(execution: ScriptExecution, result: Any?) {
            scriptExecutionListener.onSuccess(execution, result)
            scriptExecutionObserver.onSuccess(execution, result)
        }

        override fun onException(execution: ScriptExecution, e: Throwable) {
            scriptExecutionListener.onException(execution, e)
            scriptExecutionObserver.onException(execution, e)
        }
    }
}
