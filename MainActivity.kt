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
import android.widget.ImageButton
import android.widget.ImageView
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
    private var vidRot = 0
    private var streaming = false
    private var lastMoveT = 0L
    private var lastNx = 0f
    private var lastNy = 0f
    private var liveView: LinearLayout? = null
    private var liveStatus: TextView? = null
    private var liveBar: LinearLayout? = null
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
        val role = AppState.role
        val phase = AppState.phase
        val live = role == Role.HELPER && phase == Phase.LIVE
        // While watching, keep the video view in place and only refresh the label and buttons.
        // Rebuilding it made the picture blink out whenever anything changed (e.g. tapping Mute).
        if (live && liveView != null && liveView?.parent === root) {
            updateLive()
            return
        }
        root.removeAllViews()
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
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL

        val c = column()
        c.gravity = Gravity.CENTER_HORIZONTAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_launcher)
        c.addView(logo, LinearLayout.LayoutParams(dp(104), dp(104)).also { it.gravity = Gravity.CENTER_HORIZONTAL })
        c.addView(text("Remote Assist", 30f, true, BLUE, center = true))
        c.addView(text("Get help on your phone, or help someone else. Free, private, no account needed.", 16f, center = true))
        c.addView(text("Both people need this app.", 14f, color = GREY, center = true))
        c.addView(spacer(16))
        c.addView(button("I need help\n(share my screen)", BLUE) { startOwnerFlow() })
        c.addView(button("I'm helping someone\n(join with a code)", GREEN) { screen = Screen.JOIN; render() })
        c.addView(spacer(12))
        c.addView(button("Setup check (do this once)", GREY) { screen = Screen.SETUP; render() })
        outer.addView(scroll(c), LinearLayout.LayoutParams(MATCH, 0, 1f))

        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.VERTICAL
        foot.gravity = Gravity.CENTER_HORIZONTAL
        foot.setPadding(dp(16), dp(6), dp(16), dp(14))
        val l1 = text("Powered by OsamaX", 12f, true, 0xFF78909C.toInt(), center = true)
        l1.setPadding(0, dp(2), 0, 0)
        val l2 = text("\u00A9 2026 OsamaX. All rights reserved.", 11f, false, 0xFF90A4AE.toInt(), center = true)
        l2.setPadding(0, 0, 0, 0)
        foot.addView(l1)
        foot.addView(l2)
        outer.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))
        return outer
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
            } catch (e: Exception) { toast("Open Settings > Apps > Remote Assist > Battery > Unrestricted") }
        })

        if (Config.CONTROL_ENABLED) {
        c.addView(text("3. Remote control  ${if (a11yOn()) "✔ done" else "✘ not yet"}", 18f, true))
        c.addView(text("Only needed on the phone that is being helped, and only if you want your helper to tap for you. It stays inactive until you press \"Give control\"."))
        if (!a11yOn()) {
            c.addView(button("Turn on remote control", BLUE) { openAccessibility() })
            c.addView(text("If Android says \"Restricted setting\": go to Settings > Apps > Remote Assist, tap the ⋮ menu (top right), choose \"Allow restricted settings\", then try again.", 14f, color = GREY))
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

    /** Opens the exact settings page for Remote Assist when the phone supports it, else the general list. */
    private fun openAccessibility() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val i = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
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
            .setMessage("To let your helper tap on your phone, turn on \"Remote Assist\" in the page that opens next:\n\n1. Tap the Remote Assist switch (ON)\n2. Tap Allow / OK\n3. Come back to this app\n\nIf the switch is greyed out: open App info > ⋮ menu > Allow restricted settings, then try again.")
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
        c.addView(text("Ask the person you're helping to open Remote Assist, tap \"I need help\", and read you the ${Config.CODE_LENGTH}-digit code."))
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
                c.addView(text("Tell this code to your helper. They open Remote Assist, tap \"I'm helping someone\" and type it in.\n\nOnly share it with someone you trust. It expires in ${Config.CODE_VALID_MIN} minutes."))
                c.addView(button("Send code", BLUE) {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_TEXT, "Open Remote Assist, tap \"I'm helping someone\", and enter this code: ${spaced(AppState.code)}")
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
                c.addView(text("Use your phone normally. To return here, tap the Remote Assist notification.", 13f, color = GREY))
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
                c.addView(audioRow())
                c.addView(button("Stop sharing", RED) { confirmEnd("You stopped sharing.") })
            }
        }
        return scroll(c)
    }

    /** A round icon button (like a call screen). */
    private fun roundIcon(d: android.graphics.drawable.Drawable, bg: Int, sizeDp: Int, desc: String, onClick: () -> Unit): ImageButton {
        val b = ImageButton(this)
        b.setImageDrawable(d)
        b.scaleType = ImageView.ScaleType.CENTER
        val g = GradientDrawable()
        g.setColor(bg)
        g.cornerRadius = dp(sizeDp).toFloat()
        b.background = g
        b.contentDescription = desc
        b.setOnClickListener { onClick() }
        return b
    }

    /** Owner: big microphone and speaker icons, grey = on, red with a slash = off. */
    private fun audioRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(0, dp(10), 0, dp(10))
        val mic = roundIcon(MicDrawable(dp(30), AppState.micMuted, RED), if (AppState.micMuted) RED else GREY, 64,
            if (AppState.micMuted) "Unmute microphone" else "Mute microphone") { AppState.session?.setMic(AppState.micMuted) }
        val spk = roundIcon(SpeakerDrawable(dp(30), AppState.speakerMuted, RED), if (AppState.speakerMuted) RED else GREY, 64,
            if (AppState.speakerMuted) "Unmute speaker" else "Mute speaker") { AppState.session?.setSpeaker(AppState.speakerMuted) }
        val lp1 = LinearLayout.LayoutParams(dp(64), dp(64)); lp1.setMargins(dp(14), 0, dp(14), 0)
        val lp2 = LinearLayout.LayoutParams(dp(64), dp(64)); lp2.setMargins(dp(14), 0, dp(14), 0)
        row.addView(mic, lp1)
        row.addView(spk, lp2)
        return row
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

        // LIVE: built once and kept, so the picture never blinks
        liveView?.let { updateLive(); return it }

        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setBackgroundColor(Color.BLACK)

        val status = text("", 12f, color = Color.WHITE)
        status.setPadding(dp(10), dp(2), dp(10), dp(2))
        liveStatus = status
        outer.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))

        val frame = FrameLayout(this)
        val r = SurfaceViewRenderer(this)
        r.init(Rtc.egl.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {}
            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) { vidW = w; vidH = h; vidRot = rotation }
        })
        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        r.setEnableHardwareScaler(true)
        r.setOnTouchListener { v, e -> onVideoTouch(v, e) }
        renderer = r
        AppState.session?.setSink(r)
        // WRAP_CONTENT (not MATCH_PARENT): the view shrinks to the exact shape of the other phone's
        // screen, so the WHOLE screen is visible and touches map exactly. With MATCH_PARENT the
        // picture is stretched to fill the box and its top/bottom get cropped.
        frame.addView(r, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        outer.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(0xFF111111.toInt())
        bar.setPadding(dp(4), dp(2), dp(4), dp(2))
        liveBar = bar
        outer.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        liveView = outer
        updateLive()
        return outer
    }

    /** Refreshes the status line and the button bar without touching the video view. */
    private fun updateLive() {
        val base = when {
            AppState.message.isNotBlank() -> AppState.message
            AppState.controlGranted -> "You are in control. Tap and swipe on the screen."
            else -> "Watching. Talk to guide them."
        }
        liveStatus?.text = if (AppState.net.isBlank()) base else "$base   [${AppState.net}]"
        val bar = liveBar ?: return
        bar.removeAllViews()
        fun newRow(): LinearLayout {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            bar.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            return r
        }
        fun add(row: LinearLayout, label: String, color: Int, onClick: () -> Unit) {
            val b = button(label, color, onClick)
            b.textSize = 12f
            b.minHeight = 0
            b.minimumHeight = 0
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(dp(4), dp(8), dp(4), dp(8))
            val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            b.layoutParams = lp
            row.addView(b)
        }
        fun addIcon(row: LinearLayout, d: android.graphics.drawable.Drawable, bg: Int, desc: String, onClick: () -> Unit) {
            val b = roundIcon(d, bg, 14, desc, onClick)
            val g = GradientDrawable()
            g.setColor(bg)
            g.cornerRadius = dp(14).toFloat()
            b.background = g
            val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            row.addView(b, lp)
        }
        // One single row: microphone, speaker, Home (or "Ask control"), End
        val r1 = newRow()
        addIcon(r1, MicDrawable(dp(24), AppState.micMuted, RED), if (AppState.micMuted) RED else GREY,
            if (AppState.micMuted) "Unmute microphone" else "Mute microphone") { AppState.session?.setMic(AppState.micMuted) }
        addIcon(r1, SpeakerDrawable(dp(24), AppState.speakerMuted, RED), if (AppState.speakerMuted) RED else GREY,
            if (AppState.speakerMuted) "Unmute speaker" else "Mute speaker") { AppState.session?.setSpeaker(AppState.speakerMuted) }
        if (Config.CONTROL_ENABLED) {
            if (AppState.controlGranted) add(r1, "Home", BLUE) { navKey("home") }
            else add(r1, "Ask control", BLUE) { AppState.session?.requestControl() }
        }
        add(r1, "End", RED) { confirmEnd("You left the session.") }
    }

    /** Asks "are you sure?" so the session is never ended by an accidental tap. */
    private fun confirmEnd(reason: String) {
        AlertDialog.Builder(this)
            .setTitle("End session?")
            .setMessage("Do you really want to end this session?")
            .setPositiveButton("Yes, end") { _, _ -> AppState.session?.end(reason) }
            .setNegativeButton("No", null)
            .show()
    }

    // ------------------------------------------------- helper: touch → phone

    /** Live typing: every letter typed here appears instantly in the text box selected on the other phone. */
    private fun askTypeText() {
        val et = EditText(this)
        et.hint = "Type here"
        et.textSize = 18f
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                AppState.session?.sendCtl(JSONObject().put("t", "type").put("s", s?.toString() ?: "").put("clear", true))
            }
        })
        val d = AlertDialog.Builder(this)
            .setTitle("Type on the other phone")
            .setMessage("First tap the text box (for example the search bar) on their screen. What you type here appears there instantly.")
            .setView(et)
            .setPositiveButton("Search") { _, _ ->
                AppState.session?.sendCtl(JSONObject().put("t", "type").put("s", et.text.toString()).put("clear", true).put("enter", true))
            }
            .setNegativeButton("Close", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        d.show()
    }

    private fun navKey(k: String) {
        AppState.session?.sendCtl(JSONObject().put("t", "nav").put("k", k))
    }

    /** Converts a touch position on the view into 0..1 coordinates of the remote screen. */
    private fun norm(x: Float, y: Float, v: View): Pair<Float, Float>? {
        val fw = if (vidRot == 90 || vidRot == 270) vidH else vidW
        val fh = if (vidRot == 90 || vidRot == 270) vidW else vidH
        if (fw <= 0 || fh <= 0 || v.width <= 0 || v.height <= 0) return null
        val scale = minOf(v.width.toFloat() / fw, v.height.toFloat() / fh)
        val w = fw * scale
        val h = fh * scale
        val ox = (v.width - w) / 2f
        val oy = (v.height - h) / 2f
        val nx = (x - ox) / w
        val ny = (y - oy) / h
        if (nx < -0.03f || nx > 1.03f || ny < -0.03f || ny > 1.03f) return null
        return Pair(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }

    private fun onVideoTouch(v: View, e: MotionEvent): Boolean {
        if (!AppState.controlGranted) return true
        val session = AppState.session ?: return true
        // Swipes that start at a screen edge (notification panel, back gesture) snap to the edge.
        fun edge(p: Float) = if (p < 0.04f) 0.001f else if (p > 0.96f) 0.999f else p
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                downT = e.eventTime
                streaming = false
            }
            MotionEvent.ACTION_MOVE -> {
                // Once the finger really moves, stream it live so the other phone follows with no waiting.
                if (!streaming && hypot(e.x - downX, e.y - downY) > dp(10)) {
                    val a = norm(downX, downY, v)
                    if (a != null) {
                        session.sendCtl(JSONObject().put("t", "ts").put("x", edge(a.first).toDouble()).put("y", edge(a.second).toDouble()))
                        streaming = true
                        lastMoveT = 0L
                    }
                }
                if (streaming && e.eventTime - lastMoveT >= 16) {
                    val b = norm(e.x, e.y, v)
                    if (b != null) {
                        lastNx = b.first
                        lastNy = b.second
                        lastMoveT = e.eventTime
                        session.sendCtl(JSONObject().put("t", "tm").put("x", b.first.toDouble()).put("y", b.second.toDouble()))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (streaming) {
                    streaming = false
                    val b = norm(e.x, e.y, v)
                    val fx = b?.first ?: lastNx
                    val fy = b?.second ?: lastNy
                    session.sendCtl(JSONObject().put("t", "te").put("x", fx.toDouble()).put("y", fy.toDouble()))
                } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                    val a = norm(downX, downY, v)
                    if (a != null) {
                        val dur = e.eventTime - downT
                        val j = JSONObject()
                        if (dur < 400) {
                            j.put("t", "tap").put("x", a.first.toDouble()).put("y", a.second.toDouble())
                        } else { // long press
                            j.put("t", "swipe")
                                .put("x1", a.first.toDouble()).put("y1", a.second.toDouble())
                                .put("x2", a.first.toDouble()).put("y2", a.second.toDouble())
                                .put("d", dur.coerceIn(400L, 3000L))
                        }
                        session.sendCtl(j)
                    }
                }
            }
        }
        return true
    }

    private fun releaseRenderer() {
        AppState.session?.setSink(null)
        renderer?.release()
        renderer = null
        liveView = null
        liveStatus = null
        liveBar = null
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
        // Android 14+: only offer "Entire screen" (not "a single app"), so the helper sees the whole phone.
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 42)
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

/** A microphone icon drawn in code (white). When muted it gets a slash, like on a call screen. */
class MicDrawable(private val sizePx: Int, private val muted: Boolean, private val underlay: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val u = minOf(b.width(), b.height()) / 24f
        paint.color = Color.WHITE
        paint.style = android.graphics.Paint.Style.FILL
        c.drawRoundRect(cx - 3.5f * u, cy - 10f * u, cx + 3.5f * u, cy + 3f * u, 3.5f * u, 3.5f * u, paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeCap = android.graphics.Paint.Cap.ROUND
        paint.strokeWidth = 2f * u
        c.drawArc(cx - 7f * u, cy - 6.5f * u, cx + 7f * u, cy + 7.5f * u, 0f, 180f, false, paint)
        c.drawLine(cx, cy + 7.5f * u, cx, cy + 10f * u, paint)
        c.drawLine(cx - 3.5f * u, cy + 10f * u, cx + 3.5f * u, cy + 10f * u, paint)
        if (muted) {
            paint.color = underlay
            paint.strokeWidth = 5f * u
            c.drawLine(cx - 9f * u, cy - 10f * u, cx + 9f * u, cy + 10f * u, paint)
            paint.color = Color.WHITE
            paint.strokeWidth = 2.2f * u
            c.drawLine(cx - 9f * u, cy - 10f * u, cx + 9f * u, cy + 10f * u, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** A speaker icon drawn in code (white). When muted it loses its sound waves and gets a slash. */
class SpeakerDrawable(private val sizePx: Int, private val muted: Boolean, private val underlay: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val u = minOf(b.width(), b.height()) / 24f
        paint.color = Color.WHITE
        paint.style = android.graphics.Paint.Style.FILL
        val p = android.graphics.Path()
        p.moveTo(cx - 9f * u, cy - 3.5f * u)
        p.lineTo(cx - 4.5f * u, cy - 3.5f * u)
        p.lineTo(cx + 0.5f * u, cy - 7.5f * u)
        p.lineTo(cx + 0.5f * u, cy + 7.5f * u)
        p.lineTo(cx - 4.5f * u, cy + 3.5f * u)
        p.lineTo(cx - 9f * u, cy + 3.5f * u)
        p.close()
        c.drawPath(p, paint)
        if (!muted) {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.strokeWidth = 2f * u
            c.drawArc(cx - 4.5f * u, cy - 5f * u, cx + 5.5f * u, cy + 5f * u, -50f, 100f, false, paint)
            c.drawArc(cx - 8f * u, cy - 9f * u, cx + 9f * u, cy + 9f * u, -50f, 100f, false, paint)
        } else {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.color = underlay
            paint.strokeWidth = 5f * u
            c.drawLine(cx - 9f * u, cy - 10f * u, cx + 9f * u, cy + 10f * u, paint)
            paint.color = Color.WHITE
            paint.strokeWidth = 2.2f * u
            c.drawLine(cx - 9f * u, cy - 10f * u, cx + 9f * u, cy + 10f * u, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
