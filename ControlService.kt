package com.remoteassist.fyp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Performs taps and swipes on this phone. It does nothing unless the phone owner
 * has tapped "Give control" in the app (Session checks that before calling us).
 */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: ControlService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Coordinates are 0..1 fractions of the screen. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val (w, h) = realScreenSize(this)
        val path = Path()
        val sx = x1.coerceIn(0f, 1f) * w
        val sy = y1.coerceIn(0f, 1f) * h
        val ex = x2.coerceIn(0f, 1f) * w
        val ey = y2.coerceIn(0f, 1f) * h
        path.moveTo(sx, sy)
        if (sx == ex && sy == ey) path.lineTo(sx + 1f, sy + 1f) else path.lineTo(ex, ey)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1L, 4000L))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun nav(key: String) {
        when (key) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }
}
