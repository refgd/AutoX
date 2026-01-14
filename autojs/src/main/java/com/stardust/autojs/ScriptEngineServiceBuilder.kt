package com.stardust.autojs

import com.stardust.autojs.engine.ScriptEngineManager
import com.stardust.autojs.runtime.api.Console
import com.stardust.util.UiHandler

/**
 * Created by Stardust on 2017/4/2.
 */
class ScriptEngineServiceBuilder {

    lateinit var mScriptEngineManager: ScriptEngineManager
        private set

    lateinit var mGlobalConsole: Console
        private set

    lateinit var mUiHandler: UiHandler
        private set

    fun uiHandler(uiHandler: UiHandler): ScriptEngineServiceBuilder {
        mUiHandler = uiHandler
        return this
    }

    fun engineManger(manager: ScriptEngineManager): ScriptEngineServiceBuilder {
        mScriptEngineManager = manager
        return this
    }

    fun globalConsole(console: Console): ScriptEngineServiceBuilder {
        mGlobalConsole = console
        return this
    }

    fun build(): ScriptEngineService {
        return ScriptEngineService(this)
    }
}
