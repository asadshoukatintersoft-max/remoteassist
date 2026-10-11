package com.remoteassist.fyp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlin.math.hypot

class MainActivity : Activity() {

    private enum class Screen { HOME, SETUP, JOIN }

    private var screen = Screen.HOME
    private lateinit var root: FrameLayout
    private var renderer: SurfaceViewRenderer? = null
    private var vidW = 0
    private var vidH = 0
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var pendingCode: String? = null
    private var askedControlSetup = false
    private val listener: () -> Unit = { render() }

    private val BLUE = 0xFF1565C0.toInt()
    private val GREEN = 0xFF2E7D32.toInt()
    private val RED = 0xFFC62828.toInt()
    private val GREY = 0xFF607D8B.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        AppState.listeners.add(listener)
        render()
    }

    override fun onStop() {
        AppState.listeners.remove(listener)
        super.onStop()
    }

    override fun onDestroy() {
        releaseRenderer()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (AppState.phase == Phase.IDLE) {
            if (screen != Screen.HOME) { screen = Screen.HOME; render() } else super.onBackPressed()
        } else {
            moveTaskToBack(true)
        }
    }

    // ------------------------------------------------------------ rendering

    private fun render() {
        root.removeAllViews()
        val role = AppState.role
        val phase = AppState.phase
        val live = role == Role.HELPER && phase == Phase.LIVE
        if (!live) releaseRenderer()

        if (phase == Phase.IDLE || phase == Phase.ENDED) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val v: View = when {
            phase == Phase.IDLE -> when (screen) {
                Screen.HOME -> homeView()
                Screen.SETUP -> setupView()
                Screen.JOIN -> joinView()
            }
            phase == Phase.ENDED -> endedView()
            role == Role.OWNER -> ownerView(phase)
            else -> helperView(phase)
        }
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---- small UI helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun column(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(20), dp(24), dp(20), dp(24))
        return c
    }

    private fun scroll(content: View): View {
        val s = ScrollView(this)
        s.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        return s
    }

    private fun text(t: String, size: Float = 16f, bold: Boolean = false, color: Int = 0xFF263238.toInt(), center: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(color)
        if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        if (center) tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(6), 0, dp(6))
        return tv
    }

    private fun button(t: String, color: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.textSize = 17f
        b.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(14).toFloat()
        b.background = bg
        b.setPadding(dp(16), dp(16), dp(16), dp(16))
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, dp(8), 0, dp(8))
        b.layoutParams = lp
        return b
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun spaced(code: String) = code.chunked(4).joinToString(" ")

    // ------------------------------------------------------------- screens

    private fun homeView(): View {
        val c = column()
        c.addView(text("RemoteAssist", 30f, true, BLUE))
        c.addView(text("Get help on your phone, or help someone else. Free, private, no account needed.", 16f))
        c.addView(text("Both people need this app.", 14f, color = GREY))
        c.addView(spacer(24))
        c.addView(button("I need help\n(share my screen)", BLUE) { startOwnerFlow() })
        c.addView(button("I'm helping someone\n(join with a code)", GREEN) { screen = Screen.JOIN; render() })
        c.addView(spacer(12))
        c.addView(button("Setup check (do this once)", GREY) { screen = Screen.SETUP; render() })
        return scroll(c)
    }

    private fun spacer(h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(MATCH, dp(h))
        return v
    }

    private fun a11yOn(): Boolean {
        if (ControlService.instance != null) return true
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(packageName)
    }

    private fun batteryOk(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun micOk() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun setupView(): View {
        val c = column()
        c.addView(text("Setup check", 26f, true, BLUE))
        c.addView(text("Do these once so nothing gets in the way during a session.", 15f, color = GREY))

        c.addView(text("1. Microphone  ${if (micOk()) "✔ done" else "✘ not yet"}", 18f, true))
        c.addView(text("Needed for voice chat."))
        if (!micOk()) c.addView(button("Allow microphone & notifications", BLUE) { askBasicPermissions(9) })

        c.addView(text("2. Background use  ${if (batteryOk()) "✔ done" else "✘ not yet"}", 18f, true))
        c.addView(text("Stops Android from shutting the session when you switch apps."))
        if (!batteryOk()) c.addView(button("Allow background use", BLUE) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) { toast("Open Settings > Apps > RemoteAssist > Battery > Unrestricted") }
        })

        if (Config.CONTROL_ENABLED) {
        c.addView(text("3. Remote control  ${if (a11yOn()) "✔ done" else "✘ not yet"}", 18f, true))
        c.addView(text("Only needed on the phone that is being helped, and only if you want your helper to tap for you. It stays inactive until you press \"Give control\"."))
        if (!a11yOn()) {
            c.addView(button("Turn on remote control", BLUE) { openAccessibility() })
            c.addView(text("If Android says \"Restricted setting\": go to Settings > Apps > RemoteAssist, tap the ⋮ menu (top right), choose \"Allow restricted settings\", then try again.", 14f, color = GREY))
            c.addView(button("Open App info", GREY) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
        }
        c.addView(spacer(12))
        c.addView(button("Refresh", GREEN) { render() })
        c.addView(button("Back", GREY) { screen = Screen.HOME; render() })
        return scroll(c)
    }

    /** Opens the exact settings page for RemoteAssist when the phone supports it, else the general list. */
    private fun openAccessibility() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val i = Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
                i.putExtra("android.intent.extra.COMPONENT_NAME", ComponentName(this, ControlService::class.java).flattenToString())
                startActivity(i)
                return
            }
        } catch (_: Exception) {}
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { toast("Open Settings > Accessibility") }
    }

    /** Friendly one-time pop-up that takes the owner straight to the switch. */
    private fun askForControlSetup(thenContinue: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("One quick step")
            .setMessage("To let your helper tap on your phone, turn on \"RemoteAssist\" in the page that opens next:\n\n1. Tap the RemoteAssist switch (ON)\n2. Tap Allow / OK\n3. Come back to this app\n\nIf the switch is greyed out: open App info > ⋮ menu > Allow restricted settings, then try again.")
            .setPositiveButton("Open settings") { _, _ -> openAccessibility() }
            .setNeutralButton("App info") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Skip (view only)") { _, _ -> thenContinue() }
            .show()
    }

    private fun joinView(): View {
        val c = column()
        c.addView(text("Join a session", 26f, true, GREEN))
        c.addView(text("Ask the person you're helping to open RemoteAssist, tap \"I need help\", and read you the ${Config.CODE_LENGTH}-digit code."))
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "1".repeat(Config.CODE_LENGTH).replace(Regex("1"), "•")
        et.textSize = 30f
        et.gravity = Gravity.CENTER
        c.addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
        c.addView(button("Join", GREEN) {
            val code = et.text.toString().filter { it.isDigit() }
            if (code.length != Config.CODE_LENGTH) toast("Please enter all ${Config.CODE_LENGTH} digits.") else startHelperFlow(code)
        })
        c.addView(button("Back", GREY) { screen = Screen.HOME; render() })
        return scroll(c)
    }

    private fun endedView(): View {
        val c = column()
        c.addView(text("Session ended", 26f, true, BLUE))
        c.addView(text(AppState.message.ifBlank { "The session has ended." }))
        c.addView(button("Back to start", BLUE) { AppState.reset(); screen = Screen.HOME; render() })
        return scroll(c)
    }

    private fun ownerView(phase: Phase): View {
        val c = column()
        when (phase) {
            Phase.CONNECTING -> {
                c.addView(text("Connecting…", 24f, true, BLUE))
                c.addView(text("If Android asks \"Start recording or casting?\", tap Start now.", 14f, color = GREY))
            }
            Phase.WAITING_FOR_HELPER -> {
                c.addView(text("Your session code", 22f, true, BLUE))
                c.addView(text(spaced(AppState.code), 56f, true, 0xFF000000.toInt(), center = true))
                c.addView(text("Tell this code to your helper. They open RemoteAssist, tap \"I'm helping someone\" and type it in.\n\nOnly share it with someone you trust. It expires in ${Config.CODE_VALID_MIN} minutes."))
                c.addView(button("Send code", BLUE) {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_TEXT, "Open RemoteAssist, tap \"I'm helping someone\", and enter this code: ${spaced(AppState.code)}")
                    startActivity(Intent.createChooser(i, "Send code"))
                })
                c.addView(button("Cancel", RED) { AppState.session?.end("You cancelled.") ?: AppState.reset() })
            }
            Phase.WAITING_APPROVAL -> {
                c.addView(text("Someone joined", 26f, true, BLUE))
                c.addView(text("A helper entered your code. Allow them to see your screen and talk with you?"))
                c.addView(button("Allow", GREEN) { AppState.session?.approveView() })
                c.addView(button("Decline", RED) { AppState.session?.end("You declined the helper.") })
            }
            else -> { // LIVE
                c.addView(text("Sharing your screen", 22f, true, GREEN))
                if (AppState.message.isNotBlank()) c.addView(text(AppState.message, 14f, color = RED))
                c.addView(text("Use your phone normally. To return here, tap the RemoteAssist notification.", 13f, color = GREY))
                if (Config.CONTROL_ENABLED) {
                if (AppState.controlGranted) {
                    c.addView(text("Your helper is controlling your phone right now. Please don't touch the screen.", 17f, true, RED))
                    c.addView(button("Take back control", RED) { AppState.session?.setControl(false) })
                } else {
                    if (AppState.controlRequested) c.addView(text("Your helper is asking to control your phone.", 17f, true, BLUE))
                    val label = if (AppState.controlRequested) "Allow control" else "Give control to helper"
                    c.addView(button(label, BLUE) {
                        if (a11yOn()) AppState.session?.setControl(true)
                        else askForControlSetup { }
                    })
                    if (AppState.controlRequested) c.addView(button("Not now", GREY) { AppState.controlRequested = false; render() })
                    if (!a11yOn()) c.addView(text("Remote control is not turned on yet. Tap the button above and follow the steps, or see \"Setup check\" on the start screen.", 14f, color = GREY))
                }
                }
                c.addView(button(if (AppState.micMuted) "Unmute my microphone" else "Mute my microphone", GREY) {
                    AppState.session?.setMic(AppState.micMuted)
                })
                c.addView(button("Stop sharing", RED) { AppState.session?.end("You stopped sharing.") })
            }
        }
        return scroll(c)
    }

    private fun helperView(phase: Phase): View {
        if (phase == Phase.CONNECTING || phase == Phase.WAITING_APPROVAL) {
            val c = column()
            c.addView(text(if (phase == Phase.CONNECTING) "Connecting…" else "Connected!", 26f, true, GREEN))
            c.addView(text(if (phase == Phase.CONNECTING) "This can take up to 30 seconds."
            else "Waiting for the other person to allow you to see their screen…"))
            c.addView(button("Cancel", RED) { AppState.session?.end("You left the session.") ?: AppState.reset() })
            return scroll(c)
        }

        // LIVE
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setBackgroundColor(Color.BLACK)

        val status = text(
            when {
                AppState.message.isNotBlank() -> AppState.message
                AppState.controlGranted -> "You are in control. Tap and swipe on the screen."
                else -> "Watching. Talk to guide them."
            }, 12f, color = Color.WHITE
        )
        status.setPadding(dp(10), dp(2), dp(10), dp(2))
        outer.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))

        val frame = FrameLayout(this)
        var r = renderer
        if (r == null) {
            r = SurfaceViewRenderer(this)
            r.init(Rtc.egl.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {}
                override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) { vidW = w; vidH = h }
            })
            r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            r.setEnableHardwareScaler(true)
            r.setOnTouchListener { v, e -> onVideoTouch(v, e) }
            renderer = r
            AppState.session?.setSink(r)
        }
        (r.parent as? ViewGroup)?.removeView(r)
        frame.addView(r, FrameLayout.LayoutParams(MATCH, MATCH))
        outer.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(0xFF111111.toInt())
        bar.setPadding(dp(4), dp(2), dp(4), dp(2))
        fun add(label: String, color: Int, onClick: () -> Unit) {
            val b = button(label, color, onClick)
            b.textSize = 12f
            b.minHeight = 0
            b.minimumHeight = 0
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(dp(4), dp(8), dp(4), dp(8))
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            b.layoutParams = lp
            bar.addView(b)
        }
        add(if (AppState.micMuted) "Muted" else "Mic", GREY) { AppState.session?.setMic(AppState.micMuted) }
        if (Config.CONTROL_ENABLED) {
            if (AppState.controlGranted) {
                add("Back", BLUE) { navKey("back") }
                add("Home", BLUE) { navKey("home") }
                add("Apps", BLUE) { navKey("recents") }
                add("Panel", BLUE) { navKey("notif") }
                add("Type", GREEN) { askTypeText() }
            } else {
                add("Ask control", BLUE) { AppState.session?.requestControl() }
            }
        }
        add("End", RED) { AppState.session?.end("You left the session.") }
        outer.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        return outer
    }

    // ------------------------------------------------- helper: touch → phone

    /** Helper types here; the text goes into the text box selected on the other phone. */
    private fun askTypeText() {
        val et = EditText(this)
        et.hint = "Type here"
        et.textSize = 18f
        AlertDialog.Builder(this)
            .setTitle("Type on the other phone")
            .setMessage("First tap the text box (for example the search bar) on their screen.")
            .setView(et)
            .setPositiveButton("Send") { _, _ ->
                AppState.session?.sendCtl(JSONObject().put("t", "type").put("s", et.text.toString()))
            }
            .setNeutralButton("Send + Search") { _, _ ->
                AppState.session?.sendCtl(JSONObject().put("t", "type").put("s", et.text.toString()).put("enter", true))
            }
            .setNegativeButton("Clear box") { _, _ ->
                AppState.session?.sendCtl(JSONObject().put("t", "type").put("s", "").put("clear", true))
            }
            .show()
    }

    private fun navKey(k: String) {
        AppState.session?.sendCtl(JSONObject().put("t", "nav").put("k", k))
    }

    /** Converts a touch position on the view into 0..1 coordinates of the remote screen. */
    private fun norm(x: Float, y: Float, v: View): Pair<Float, Float>? {
        if (vidW <= 0 || vidH <= 0) return null
        val scale = minOf(v.width.toFloat() / vidW, v.height.toFloat() / vidH)
        val w = vidW * scale
        val h = vidH * scale
        val ox = (v.width - w) / 2f
        val oy = (v.height - h) / 2f
        val nx = (x - ox) / w
        val ny = (y - oy) / h
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return Pair(nx, ny)
    }

    private fun onVideoTouch(v: View, e: MotionEvent): Boolean {
        if (!AppState.controlGranted) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime }
            MotionEvent.ACTION_UP -> {
                val a = norm(downX, downY, v)
                val b = norm(e.x, e.y, v)
                if (a != null && b != null) {
                    val dur = e.eventTime - downT
                    val dist = hypot(b.first - a.first, b.second - a.second)
                    val j = JSONObject()
                    // Swipes that start at a screen edge (notification panel, back gesture) snap to the edge.
                    fun snap(v: Float) = if (v < 0.04f) 0.001f else if (v > 0.96f) 0.999f else v
                    if (dist < 0.03f && dur < 400) {
                        j.put("t", "tap").put("x", a.first.toDouble()).put("y", a.second.toDouble())
                    } else {
                        j.put("t", "swipe")
                            .put("x1", snap(a.first).toDouble()).put("y1", snap(a.second).toDouble())
                            .put("x2", b.first.toDouble()).put("y2", b.second.toDouble())
                            .put("d", dur.coerceIn(60L, 3000L))
                    }
                    AppState.session?.sendCtl(j)
                }
            }
        }
        return true
    }

    private fun releaseRenderer() {
        AppState.session?.setSink(null)
        renderer?.release()
        renderer = null
    }

    // ------------------------------------------------------ flows/permissions

    private fun askBasicPermissions(requestCode: Int) {
        val list = ArrayList<String>()
        list.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), requestCode)
    }

    private fun startOwnerFlow() {
        if (Config.CONTROL_ENABLED && !a11yOn() && !askedControlSetup) {
            askedControlSetup = true
            askForControlSetup { startOwnerFlow() }
            return
        }
        if (!micOk() || (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        ) {
            askBasicPermissions(1)
        } else {
            launchCapture()
        }
    }

    private fun launchCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 42)
    }

    private fun startHelperFlow(code: String) {
        pendingCode = code
        if (!micOk()) askBasicPermissions(2) else beginHelper(code)
    }

    private fun beginHelper(code: String) {
        val i = Intent(this, ShareService::class.java).setAction("helper").putExtra("code", code)
        startForegroundService(i)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, ShareService::class.java).setAction("owner").putExtra("data", data)
                startForegroundService(i)
            } else {
                toast("Screen sharing was not allowed, so nothing was shared.")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!micOk()) {
            toast("The microphone permission is needed for voice chat. Please allow it.")
            if (requestCode == 9) render()
            return
        }
        when (requestCode) {
            1 -> launchCapture()
            2 -> pendingCode?.let { beginHelper(it) }
            9 -> render()
        }
    }
}
