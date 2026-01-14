package com.stardust.autojs.core.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import com.stardust.autojs.core.pref.Pref
import com.stardust.view.accessibility.AccessibilityService

class AccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        val serviceInfo = serviceInfo
        Pref.init(applicationContext)
        if (Pref.isStableModeEnabled) {
            serviceInfo.flags = serviceInfo.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
        } else {
            serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        if (Pref.isGestureObservingEnabled) {
            serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        } else {
            serviceInfo.flags = serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
        }
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
        setServiceInfo(serviceInfo)
        super.onServiceConnected()
    }
}