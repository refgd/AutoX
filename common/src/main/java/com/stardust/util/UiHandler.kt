package com.stardust.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Created by Stardust on 2017/5/2.
 */
class UiHandler(context: Context) : Handler(Looper.getMainLooper()) {

    // 强制 applicationContext，避免外部误传 Activity 导致泄漏
    val context: Context = context.applicationContext

    fun toast(message: String?) {
        post {
            Toast.makeText(
                context,
                message.toString(),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun toast(resId: Int) {
        post { Toast.makeText(context, resId, Toast.LENGTH_SHORT).show() }
    }
}
