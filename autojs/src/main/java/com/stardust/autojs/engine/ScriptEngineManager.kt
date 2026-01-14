package com.stardust.autojs.engine

import android.content.Context
import com.stardust.autojs.engine.ScriptEngine.OnDestroyListener
import com.stardust.autojs.engine.ScriptEngineFactory.EngineNotFoundException
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.script.ScriptSource
import com.stardust.util.Supplier
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by Stardust on 2017/1/27.
 */
class ScriptEngineManager(val androidContext: Context) {

    interface EngineLifecycleCallback {
        fun onEngineCreate(engine: ScriptEngine<*>?)
        fun onEngineRemove(engine: ScriptEngine<*>?)
    }

    private val enginesLock = Any()
    private val mEngines: MutableSet<ScriptEngine<*>> = HashSet()

    @Volatile
    private var mEngineLifecycleCallback: EngineLifecycleCallback? = null

    private val mEngineSuppliers: MutableMap<String, Supplier<ScriptEngine<*>>> = ConcurrentHashMap()
    private val mGlobalVariableMap: MutableMap<String, Any> = ConcurrentHashMap()

    private val mOnEngineDestroyListener = object : OnDestroyListener {
        override fun onDestroy(engine: ScriptEngine<*>) = removeEngine(engine)
    }

    private fun addEngine(engine: ScriptEngine<*>) {
        engine.setOnDestroyListener(mOnEngineDestroyListener)

        val cb = mEngineLifecycleCallback
        val added: Boolean = synchronized(enginesLock) { mEngines.add(engine) }

        if (added && cb != null) {
            cb.onEngineCreate(engine)
        }
    }

    fun setEngineLifecycleCallback(engineLifecycleCallback: EngineLifecycleCallback?) {
        mEngineLifecycleCallback = engineLifecycleCallback
    }

    val engines: Set<ScriptEngine<*>>
        get() = synchronized(enginesLock) { mEngines.toSet() }

    fun removeEngine(engine: ScriptEngine<*>) {
        val cb = mEngineLifecycleCallback
        val removed: Boolean = synchronized(enginesLock) { mEngines.remove(engine) }
        if (removed && cb != null) {
            cb.onEngineRemove(engine)
        }
    }

    fun stopAll(): Int {
        val snapshot: List<ScriptEngine<*>> = synchronized(enginesLock) { mEngines.toList() }
        for (engine in snapshot) {
            engine.forceStop()
        }
        return snapshot.size
    }

    fun putGlobal(varName: String, value: Any) {
        mGlobalVariableMap[varName] = value
    }

    private fun putProperties(engine: ScriptEngine<*>) {
        for ((key, value) in mGlobalVariableMap) {
            engine.put(key, value)
        }
    }

    fun createEngine(name: String, id: Int): ScriptEngine<*>? {
        val supplier = mEngineSuppliers[name] ?: return null
        val engine = supplier.get()
        engine.id = id
        putProperties(engine)
        addEngine(engine)
        return engine
    }

    fun createEngineOfSource(source: ScriptSource, id: Int): ScriptEngine<*>? {
        return createEngine(source.engineName, id)
    }

    @JvmOverloads
    fun createEngineOfSourceOrThrow(
        source: ScriptSource,
        id: Int = ScriptExecution.NO_ID
    ): ScriptEngine<*> {
        return createEngineOfSource(source, id) ?: throw EngineNotFoundException("source: $source")
    }

    fun registerEngine(name: String, supplier: Supplier<ScriptEngine<*>>) {
        mEngineSuppliers[name] = supplier
    }

    fun unregisterEngine(name: String) {
        mEngineSuppliers.remove(name)
    }

    companion object {
        private const val TAG = "ScriptEngineManager"
    }
}
