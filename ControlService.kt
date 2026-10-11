package com.remoteassist.fyp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    /** A quick tap. Coordinates are 0..1 fractions of the screen. */
    fun tap(nx: Float, ny: Float) = gesture(nx, ny, nx, ny, 60L)

    /** Swipe, or a long press when start and end are (almost) the same point. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = gesture(x1, y1, x2, y2, durationMs)

    // ---- live touch: the finger on this phone follows the helper's finger, so scrolling feels instant

    private val ui = Handler(Looper.getMainLooper())
    private var liveStroke: GestureDescription.StrokeDescription? = null
    private var liveActive = false
    private var liveEnding = false
    private var liveBusy = false
    private var curX = 0f
    private var curY = 0f
    private var tgtX = 0f
    private var tgtY = 0f
    private var haveTarget = false
    private var lastDispatchT = 0L
    private val liveWatchdog = Runnable { touchEnd(-1f, -1f) }

    private val liveCb = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) {
            liveBusy = false
            if (!liveActive) liveStroke = null
            pumpLive()
        }
        override fun onCancelled(g: GestureDescription?) { resetLive() }
    }

    private fun resetLive() {
        ui.removeCallbacks(liveWatchdog)
        liveActive = false
        liveEnding = false
        liveBusy = false
        liveStroke = null
        haveTarget = false
    }

    private fun armWatchdog() {
        ui.removeCallbacks(liveWatchdog)
        ui.postDelayed(liveWatchdog, 15_000L)
    }

    fun touchStart(nx: Float, ny: Float) {
        resetLive()
        val (w, h) = realScreenSize(this)
        curX = nx.coerceIn(0f, 1f) * w
        curY = ny.coerceIn(0f, 1f) * h
        tgtX = curX
        tgtY = curY
        val p = Path()
        p.moveTo(curX, curY)
        val s = GestureDescription.StrokeDescription(p, 0, 1, true)
        liveStroke = s
        liveActive = true
        liveEnding = false
        haveTarget = false
        lastDispatchT = SystemClock.uptimeMillis()
        sendLive(s)
        armWatchdog()
    }

    fun touchMove(nx: Float, ny: Float) {
        if (!liveActive) return
        val (w, h) = realScreenSize(this)
        tgtX = nx.coerceIn(0f, 1f) * w
        tgtY = ny.coerceIn(0f, 1f) * h
        haveTarget = true
        armWatchdog()
        pumpLive()
    }

    /** Lift the finger. A negative position means "where it is now". */
    fun touchEnd(nx: Float, ny: Float) {
        if (!liveActive) return
        if (nx >= 0f && ny >= 0f) {
            val (w, h) = realScreenSize(this)
            tgtX = nx.coerceIn(0f, 1f) * w
            tgtY = ny.coerceIn(0f, 1f) * h
            haveTarget = true
        }
        liveEnding = true
        ui.removeCallbacks(liveWatchdog)
        pumpLive()
    }

    fun touchCancel() = touchEnd(-1f, -1f)

    private fun pumpLive() {
        val s0 = liveStroke ?: return
        if (!liveActive || liveBusy) return
        val moved = haveTarget && kotlin.math.hypot(tgtX - curX, tgtY - curY) > 1f
        if (!liveEnding && !moved) return
        val now = SystemClock.uptimeMillis()
        val p = Path()
        p.moveTo(curX, curY)
        if (moved) p.lineTo(tgtX, tgtY)
        val dur = (now - lastDispatchT).coerceIn(12L, 60L)
        val last = liveEnding
        val s = s0.continueStroke(p, 0, dur, !last)
        liveStroke = s
        if (moved) {
            curX = tgtX
            curY = tgtY
        }
        haveTarget = false
        lastDispatchT = now
        if (last) liveActive = false
        sendLive(s)
    }

    private fun sendLive(s: GestureDescription.StrokeDescription) {
        liveBusy = true
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(s).build(), liveCb, null)
        if (!ok) resetLive()
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val (w, h) = realScreenSize(this)
        val sx = x1.coerceIn(0f, 1f) * w
        val sy = y1.coerceIn(0f, 1f) * h
        val ex = x2.coerceIn(0f, 1f) * w
        val ey = y2.coerceIn(0f, 1f) * h
        val moved = kotlin.math.hypot(ex - sx, ey - sy) > 12f
        val path = Path()
        path.moveTo(sx, sy)
        if (moved) path.lineTo(ex, ey) // otherwise a pure tap / long press (no movement)
        val dur = if (moved) durationMs.coerceIn(120L, 3000L) else durationMs.coerceIn(30L, 3000L)
        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun nav(key: String) {
        when (key) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notif" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        }
    }

    /** Types into the text box that is currently selected on this phone. Returns false if none is selected. */
    fun typeText(text: String, clear: Boolean, enter: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val current = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val newText = if (clear) text else current + text
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && enter && Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
    }
}
