package com.stardust.view.accessibility

import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.stardust.event.EventDispatcher
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TreeMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Created by Stardust on 2017/5/2.
 */
open class AccessibilityService : android.accessibilityservice.AccessibilityService() {

    interface GestureListener {
        fun onGesture(gestureId: Int)
    }

    private val keyObserver = PublishSubject.create<KeyEvent>()
    val onKeyObserver = OnKeyListener.Observer()
    val keyInterrupterObserver = KeyInterceptor.Observer()
    val gestureEventDispatcher = EventDispatcher<GestureListener>()

    @Volatile
    private var mFastRootInActiveWindow: AccessibilityNodeInfo? = null

    // 事件执行线程池：异步执行要注意事件对象可能会被回收或修改
    private val eventExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        object : ThreadFactory {
            private val df = Executors.defaultThreadFactory()
            override fun newThread(r: Runnable): Thread {
                return df.newThread(r).apply {
                    name = "AccessibilityService-events"
                    isDaemon = true
                }
            }
        }
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 过滤：filterEventTypes == null 表示不过滤（任意 delegate 需要全量事件）
        val localFilter = synchronized(LOCK) { filterEventTypes }
        if (localFilter?.contains(event.eventType) == false) return

        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            val root = rootInActiveWindow
            if (root != null) {
                // obtain 一份安全持有，并回收旧缓存，避免持有系统复用对象
                val copy = AccessibilityNodeInfo.obtain(root)
                val old = mFastRootInActiveWindow
                mFastRootInActiveWindow = copy
                old?.recycle()
            }
        }

        // 快照 delegates，避免遍历时并发修改
        val delegatesSnapshot: List<AccessibilityDelegate> = synchronized(LOCK) {
            mDelegates.values.toList()
        }

        for (delegate in delegatesSnapshot) {
            val set = delegate.eventTypes
            if (set?.contains(event.eventType) == false) continue
            if (delegate.onAccessibilityEvent(this@AccessibilityService, event)) break
        }
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val eventClone = KeyEvent(event)
        Log.v(TAG, "onKeyEvent: $eventClone")
        eventExecutor.execute {
            stickOnKeyObserver.onKeyEvent(eventClone.keyCode, eventClone)
            onKeyObserver.onKeyEvent(eventClone.keyCode, eventClone)
            keyObserver.onNext(eventClone)
        }
        return keyInterrupterObserver.onInterceptKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onGesture(gestureId: Int): Boolean {
        eventExecutor.execute {
            gestureEventDispatcher.dispatchEvent { it.onGesture(gestureId) }
        }
        return false
    }

    override fun getRootInActiveWindow(): AccessibilityNodeInfo? {
        return try {
            super.getRootInActiveWindow()
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        Log.v(TAG, "onDestroy: $instance")
        ENABLED = Job()
        instance = null

        // 回收缓存 root，避免泄漏
        mFastRootInActiveWindow?.recycle()
        mFastRootInActiveWindow = null

        try {
            eventExecutor.shutdown()
        } finally {
            eventExecutor.shutdownNow()
        }
        super.onDestroy()
    }

    override fun onServiceConnected() {
        Log.v(TAG, "onServiceConnected: $serviceInfo")
        instance = this
        super.onServiceConnected()
        ENABLED.complete()
        // FIXME: 2017/2/12 有时在无障碍中开启服务后这里不会调用服务也不会运行，安卓的BUG???
    }

    fun fastRootInActiveWindow(): AccessibilityNodeInfo? {
        return mFastRootInActiveWindow
    }

    companion object {

        private const val TAG = "AccessibilityService"
        private val LOCK = Any()

        private val mDelegates = TreeMap<Int, AccessibilityDelegate>()

        @Volatile
        private var ENABLED = Job()

        @Volatile
        var instance: AccessibilityService? = null
            private set

        val stickOnKeyObserver = OnKeyListener.Observer()

        /**
         * null 表示不过滤（任意 delegate 需要全量事件）
         * 非 null 表示仅允许 set 中的 eventType 进入 onAccessibilityEvent
         */
        @Volatile
        private var filterEventTypes: HashSet<Int>? = HashSet()

        fun addDelegate(uniquePriority: Int, delegate: AccessibilityDelegate) {
            synchronized(LOCK) {
                mDelegates[uniquePriority] = delegate
                val set = delegate.eventTypes
                if (set == null) {
                    filterEventTypes = null
                } else {
                    filterEventTypes?.addAll(set)
                }
            }
        }

        fun removeDelegate(uniquePriority: Int) {
            synchronized(LOCK) {
                mDelegates.remove(uniquePriority)

                var needsAll = false
                val newSet = HashSet<Int>()
                for (d in mDelegates.values) {
                    val types = d.eventTypes
                    if (types == null) {
                        needsAll = true
                        break
                    } else {
                        newSet.addAll(types)
                    }
                }
                filterEventTypes = if (needsAll) null else newSet
            }
        }

        fun disable(): Boolean {
            instance?.disableSelf()
            return true
        }

        fun waitForEnabled(timeOut: Long): Boolean = runBlocking {
            if (instance != null) return@runBlocking true
            if (timeOut == -1L) {
                ENABLED.join(); true
            } else {
                withTimeoutOrNull(timeOut) { ENABLED.join(); true } != null
            }
        }

        suspend fun suspendWaitForEnabled(timeOut: Long): Boolean {
            if (instance != null) return true
            return if (timeOut == -1L) {
                ENABLED.join(); true
            } else {
                withTimeoutOrNull(timeOut) { ENABLED.join(); true } != null
            }
        }
    }
}
