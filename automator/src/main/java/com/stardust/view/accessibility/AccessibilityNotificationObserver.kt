package com.stardust.view.accessibility

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.stardust.notification.Notification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Created by Stardust on 2017/11/3.
 */
class AccessibilityNotificationObserver(
    private val mContext: Context
) : NotificationListener, AccessibilityDelegate {

    private val mNotificationListeners = CopyOnWriteArrayList<NotificationListener>()
    private val mToastListeners = CopyOnWriteArrayList<ToastListener>()

    override val eventTypes: Set<Int>?
        get() = EVENT_TYPES

    class Toast(val packageName: String, texts: List<CharSequence>) {
        val texts: List<String> = texts.map { it.toString() }
        val text: String?
            get() = texts.firstOrNull()

        override fun toString(): String {
            return "Toast{texts=$texts, packageName='$packageName'}"
        }
    }

    interface ToastListener {
        fun onToast(toast: Toast)
    }

    fun addNotificationListener(listener: NotificationListener) {
        mNotificationListeners.add(listener)
    }

    fun removeNotificationListener(listener: NotificationListener): Boolean {
        return mNotificationListeners.remove(listener)
    }

    fun addToastListener(listener: ToastListener) {
        mToastListeners.add(listener)
    }

    fun removeToastListener(listener: ToastListener): Boolean {
        return mToastListeners.remove(listener)
    }

    override fun onAccessibilityEvent(
        service: android.accessibilityservice.AccessibilityService,
        event: AccessibilityEvent
    ): Boolean {
        // 关键修复：TYPE_NOTIFICATION_STATE_CHANGED 的 parcelableData 一般是 android.app.Notification
        val data = event.parcelableData
        if (data is android.app.Notification) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onNotification: $data; pkg=${event.packageName}")
            }
            onNotification(Notification.create(data, event.packageName?.toString().orEmpty()))
            return false
        }

        // 某些 ROM/场景下该事件可能携带 text，可用于抓 toast
        val list = event.text
        if (list.isNullOrEmpty()) return false

        // 不处理自己应用发出的 toast/提示
        if (event.packageName == mContext.packageName) return false

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onToast: $list; pkg=${event.packageName}")
        }
        onToast(Toast(event.packageName?.toString().orEmpty(), list))
        return false
    }

    private fun onToast(toast: Toast) {
        for (listener in mToastListeners) {
            try {
                listener.onToast(toast)
            } catch (e: Exception) {
                Log.e(TAG, "Error onToast: $toast Listener: $listener", e)
            }
        }
    }

    override fun onNotification(notification: Notification) {
        for (listener in mNotificationListeners) {
            try {
                listener.onNotification(notification)
            } catch (e: Exception) {
                Log.e(TAG, "Error onNotification: $notification Listener: $listener", e)
            }
        }
    }

    companion object {
        private val EVENT_TYPES = setOf(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
        private const val TAG = "NotificationObserver"
    }
}
