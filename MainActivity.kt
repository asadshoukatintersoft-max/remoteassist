package com.remoteassist.fyp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
    private val ui = Handler(Looper.getMainLooper())
    private var dark = false
    private var lastSig = ""
    private var liveFrame: FrameLayout? = null
    private var devPanel: LinearLayout? = null
    private var devChip: TextView? = null
    private var devLive: TextView? = null
    private var devPhoneBtn: ImageButton? = null
    private var devState = 0 // 0 closed, 1 open, 2 minimised
    private val devVals = HashMap<String, TextView>()
    private val devRows = HashMap<String, View>()
    private val tick = object : Runnable {
        override fun run() {
            if (screen == Screen.SETUP && AppState.phase == Phase.IDLE && setupSig() != lastSig) render()
            ui.postDelayed(this, 700L)
        }
    }

    private val BLUE = 0xFF1565C0.toInt()
    private val GREEN = 0xFF2E7D32.toInt()
    private val RED = 0xFFC62828.toInt()
    private val GREY = 0xFF607D8B.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ---- theme + language helpers
    private fun bgC() = if (dark) 0xFF10151B.toInt() else Color.WHITE
    private fun fgC() = if (dark) 0xFFECEFF1.toInt() else 0xFF263238.toInt()
    private fun cardC() = if (dark) 0xFF1B232C.toInt() else 0xFFF3F6F9.toInt()
    private fun lineC() = if (dark) 0xFF2E3A46.toInt() else 0xFFDDE4EA.toInt()
    private fun adj(c: Int): Int = if (!dark) c else when (c) {
        BLUE -> 0xFF64B5F6.toInt()
        GREEN -> 0xFF81C784.toInt()
        RED -> 0xFFEF9A9A.toInt()
        GREY -> 0xFF90A4AE.toInt()
        else -> c
    }
    private fun tr(s: String) = Lang.tr(s)
    private fun tr(s: String, vararg a: Any) = Lang.tr(s, *a)
    private fun dlg() = AlertDialog.Builder(this, if (dark) AlertDialog.THEME_DEVICE_DEFAULT_DARK else AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)

    private fun loadTheme() {
        val p = getSharedPreferences("ra", MODE_PRIVATE)
        dark = if (p.contains("dark")) p.getBoolean("dark", false)
        else (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun toggleTheme() {
        dark = !dark
        getSharedPreferences("ra", MODE_PRIVATE).edit().putBoolean("dark", dark).apply()
        liveView = null
        render()
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        loadTheme()
        root.setBackgroundColor(bgC())
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
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 700L)
    }

    override fun onStop() {
        ui.removeCallbacks(tick)
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
        root.setBackgroundColor(bgC())
        if (!live) releaseRenderer()

        if (phase == Phase.IDLE || phase == Phase.ENDED) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val v: View = when {
            phase == Phase.IDLE -> when (screen) {
                Screen.HOME -> homeView()
                Screen.SETUP -> settingsView()
                Screen.JOIN -> joinView()
            }
            phase == Phase.ENDED -> endedView()
            role == Role.OWNER -> ownerView(phase)
            else -> helperView(phase)
        }
        root.addView(if (live) v else withFooter(v), FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** The simple "Powered by" footer, shown on every screen except the live helper view (which needs every pixel). */
    private fun withFooter(content: View): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.VERTICAL
        foot.gravity = Gravity.CENTER_HORIZONTAL
        foot.setPadding(dp(16), dp(6), dp(16), dp(12))
        val l1 = text("Powered by OsamaX", 12f, true, 0xFF78909C.toInt(), center = true)
        l1.setPadding(0, dp(2), 0, 0)
        val l2 = text("\u00A9 2026 OsamaX. All rights reserved.", 11f, false, 0xFF90A4AE.toInt(), center = true)
        l2.setPadding(0, 0, 0, 0)
        foot.addView(l1)
        foot.addView(l2)
        box.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))
        return box
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

    private fun text(t: String, size: Float = 16f, bold: Boolean = false, color: Int = Int.MIN_VALUE, center: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = tr(t)
        tv.textSize = size
        tv.setTextColor(if (color == Int.MIN_VALUE) fgC() else adj(color))
        if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        if (center) tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(6), 0, dp(6))
        return tv
    }

    private fun button(t: String, color: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = tr(t)
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

    private fun toast(m: String) = Toast.makeText(this, tr(m), Toast.LENGTH_LONG).show()

    private fun spaced(code: String) = code.chunked(4).joinToString(" ")

    // ------------------------------------------------------------- screens

    private fun themeToggle(): View =
        roundIcon(ThemeDrawable(dp(22), dark), if (dark) 0xFF2E3A46.toInt() else 0xFFE3EAF1.toInt(), 40,
            if (dark) "Light theme" else "Dark theme") { toggleTheme() }

    private fun topRow(title: String?, color: Int = BLUE): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        if (title != null) row.addView(text(title, 26f, true, color), LinearLayout.LayoutParams(0, WRAP, 1f))
        else row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(themeToggle(), LinearLayout.LayoutParams(dp(40), dp(40)))
        return row
    }

    private fun homeView(): View {
        val c = column()
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(topRow(null))
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_launcher)
        c.addView(logo, LinearLayout.LayoutParams(dp(104), dp(104)).also { it.gravity = Gravity.CENTER_HORIZONTAL })
        c.addView(text("Remote Assist", 30f, true, BLUE, center = true))
        c.addView(text("Get help on your phone, or help someone else. Free, private, no account needed.", 16f, center = true))
        c.addView(text("Both people need this app.", 14f, color = GREY, center = true))
        c.addView(spacer(16))
        c.addView(button("I need help\n(share my screen)", BLUE) { startOwnerFlow() })
        c.addView(button("Join\n(enter the code)", GREEN) { screen = Screen.JOIN; render() })
        c.addView(spacer(12))
        c.addView(button("Settings", GREY) { screen = Screen.SETUP; render() })
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

    private fun notifOk() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun setupSig() = "${micOk()}|${notifOk()}|${batteryOk()}|${a11yOn()}|$dark"

    private fun card(ok: Boolean, title: String, desc: String): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        val g = GradientDrawable()
        g.setColor(cardC())
        g.cornerRadius = dp(16).toFloat()
        g.setStroke(dp(if (ok) 1 else 2), if (ok) lineC() else 0xFFFFB300.toInt())
        card.background = g
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, dp(6), 0, dp(6))
        card.layoutParams = lp
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val icon = ImageView(this)
        icon.setImageDrawable(StatusDrawable(dp(30), ok))
        head.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, 0, 0)
        col.addView(text(title, 17f, true))
        col.addView(text(desc, 13f, color = GREY))
        head.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(head)
        return card
    }

    private fun settingsView(): View {
        lastSig = setupSig()
        val c = column()
        c.addView(topRow("Settings"))
        val total = if (Config.CONTROL_ENABLED) 3 else 2
        val done = (if (micOk() && notifOk()) 1 else 0) + (if (batteryOk()) 1 else 0) +
                (if (Config.CONTROL_ENABLED && a11yOn()) 1 else 0)
        if (done == total) {
            c.addView(text("All set ✔  You are ready to go.", 16f, true, GREEN))
        } else {
            c.addView(text(tr("{0} of {1} ready. This page checks itself automatically.", done, total), 14f, color = GREY))
        }

        val micDone = micOk() && notifOk()
        val c1 = card(micDone, "1. Microphone & notifications", "Needed for voice chat and the session notification.")
        if (!micDone) c1.addView(button("Allow", BLUE) { askBasicPermissions(9) })
        c.addView(c1)

        val bat = batteryOk()
        val c2 = card(bat, "2. Background use", "Stops Android from closing the session when you switch apps.")
        if (!bat) c2.addView(button("Allow", BLUE) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) { toast("Open Settings > Apps > Remote Assist > Battery > Unrestricted") }
        })
        c.addView(c2)

        if (Config.CONTROL_ENABLED) {
            val on = a11yOn()
            val c3 = card(on, "3. Remote control", "Only needed on the phone that is being helped. It stays inactive until you press \"Give control\".")
            if (!on) {
                c3.addView(button("Turn on", BLUE) { openAccessibility() })
                c3.addView(text("Android will open the exact page. Switch \"Remote Assist remote control\" ON, tap Allow, then come back here.", 13f, color = GREY))
                c3.addView(text("If Android says \"Restricted setting\": open App info, tap the ⋮ menu (top right), choose \"Allow restricted settings\", then try again.", 12f, color = GREY))
                c3.addView(button("Open App info", GREY) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                })
            }
            c.addView(c3)
        }
        c.addView(spacer(8))
        c.addView(button("Back", GREY) { screen = Screen.HOME; render() })
        return scroll(c)
    }

    /** Opens the exact accessibility page for this app (and highlights its switch) when the phone supports it. */
    private fun openAccessibility() {
        val cn = ComponentName(this, ControlService::class.java).flattenToString()
        val hint = "Switch \"Remote Assist remote control\" ON, then tap Allow. (On some phones it is under \"Installed apps\".)"
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val i = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                i.putExtra("android.intent.extra.COMPONENT_NAME", cn)
                startActivity(i)
                toast(hint)
                return
            }
        } catch (_: Exception) {}
        try {
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val args = Bundle()
            args.putString(":settings:fragment_args_key", cn)
            i.putExtra(":settings:fragment_args_key", cn)
            i.putExtra(":settings:show_fragment_args", args)
            startActivity(i)
            toast(hint)
        } catch (e: Exception) { toast("Open Settings > Accessibility") }
    }

    /** Friendly one-time pop-up that takes the owner straight to the switch. */
    private fun askForControlSetup(thenContinue: () -> Unit) {
        dlg()
            .setTitle(tr("One quick step"))
            .setMessage(tr("To let your helper tap on your phone, turn on \"Remote Assist\" in the page that opens next:\n\n1. Tap the Remote Assist switch (ON)\n2. Tap Allow / OK\n3. Come back to this app\n\nIf the switch is greyed out: open App info > ⋮ menu > Allow restricted settings, then try again."))
            .setPositiveButton(tr("Open settings")) { _, _ -> openAccessibility() }
            .setNeutralButton(tr("App info")) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(tr("Skip (view only)")) { _, _ -> thenContinue() }
            .show()
    }

    private fun joinView(): View {
        val c = column()
        c.addView(topRow("Join a session", GREEN))
        c.addView(text(tr("Ask the person you're helping to open Remote Assist, tap \"I need help\", and read you the {0}-digit code.", Config.CODE_LENGTH)))
        val et = EditText(this)
        et.setTextColor(fgC())
        et.setHintTextColor(0xFF78909C.toInt())
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "1".repeat(Config.CODE_LENGTH).replace(Regex("1"), "•")
        et.textSize = 30f
        et.gravity = Gravity.CENTER
        c.addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
        c.addView(button("Join", GREEN) {
            val code = et.text.toString().filter { it.isDigit() }
            if (code.length != Config.CODE_LENGTH) toast(tr("Please enter all {0} digits.", Config.CODE_LENGTH)) else startHelperFlow(code)
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
                c.addView(text(spaced(AppState.code), 56f, true, center = true))
                c.addView(text(tr("Tell this code to your helper. They open Remote Assist, tap \"Join\" and type it in.\n\nOnly share it with someone you trust. It expires in {0} minutes.", Config.CODE_VALID_MIN)))
                c.addView(button("Send code", BLUE) {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_TEXT, tr("Open Remote Assist, tap \"Join\", and enter this code: {0}", spaced(AppState.code)))
                    startActivity(Intent.createChooser(i, tr("Send code")))
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
                    if (!a11yOn()) c.addView(text("Remote control is not turned on yet. Tap the button above and follow the steps, or see \"Settings\" on the start screen.", 14f, color = GREY))
                }
                }
                if (AppState.deviceStreaming) c.addView(text("Your helper is viewing your phone details (battery, storage, network). It stops when you take back control.", 13f, color = GREY))
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

        val status = text("", 11f, color = Color.WHITE)
        status.setPadding(dp(8), 0, dp(8), 0)
        status.maxLines = 1
        status.ellipsize = android.text.TextUtils.TruncateAt.END
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
        liveFrame = frame
        buildDevicePanel(frame)
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
        // One short line only, so the screen gets all the room.
        liveStatus?.text = when {
            AppState.message.isNotBlank() -> tr(AppState.message)
            AppState.net.isNotBlank() -> tr(if (AppState.controlGranted) "In control" else "Watching") + " \u00b7 " + AppState.net.trimStart(' ', '\u00b7')
            AppState.controlGranted -> tr("In control")
            else -> tr("Watching")
        }
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
            if (AppState.controlGranted) {
                add(r1, "Back", BLUE) { navKey("back") }
                add(r1, "Home", BLUE) { navKey("home") }
            }
            else add(r1, "Ask control", BLUE) { AppState.session?.requestControl() }
        }
        if (Config.CONTROL_ENABLED && AppState.controlGranted) {
            addIcon(r1, PhoneDrawable(dp(24)), if (devState != 0) 0xFF1565C0.toInt() else GREY, "Phone details") { toggleDevice() }
        }
        add(r1, "End", RED) { confirmEnd("You left the session.") }
        updateDevicePanel()
    }

    // ------------------------------------------------ helper: live phone details

    private fun toggleDevice() {
        when (devState) {
            0 -> { devState = 1; AppState.session?.requestDevice(true) }
            1 -> devState = 2
            else -> devState = 1
        }
        updateLive()
    }

    private fun closeDevice() {
        if (devState != 0) AppState.session?.requestDevice(false)
        devState = 0
        updateLive()
    }

    private val DEV_SECTIONS: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Device" to listOf("model" to "Model", "android" to "Android version", "patch" to "Security patch", "hw" to "Board", "abi" to "Processor", "cores" to "CPU cores"),
        "Battery" to listOf("bat" to "Level", "chg" to "Status", "temp" to "Temperature", "volt" to "Voltage", "health" to "Health", "saver" to "Battery saver"),
        "Memory & storage" to listOf("ram" to "Memory (RAM)", "st" to "Storage"),
        "Network" to listOf("net" to "Connection", "netOk" to "Internet", "sig" to "Wi-Fi signal", "speed" to "Link speed", "op" to "Operator", "bw" to "Estimated speed", "air" to "Airplane mode"),
        "Screen" to listOf("res" to "Resolution", "hz" to "Refresh rate", "bright" to "Brightness", "scr" to "Screen"),
        "Sound" to listOf("vm" to "Media volume", "vr" to "Ring volume", "ringer" to "Ring mode", "dnd" to "Do not disturb"),
        "System" to listOf("boot" to "Running for", "time" to "Phone time", "tz" to "Time zone", "lang" to "Language", "loc" to "Location services", "therm" to "Heat level")
    )

    private fun buildDevicePanel(frame: FrameLayout) {
        devVals.clear()
        devRows.clear()
        val r = dp(18).toFloat()
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val g = GradientDrawable()
        g.setColor(0xF2121820.toInt())
        g.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        g.setStroke(dp(1), 0xFF33414E.toInt())
        card.background = g

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(14), dp(6), dp(6), dp(6))
        val title = TextView(this)
        title.text = tr("Phone details")
        title.textSize = 16f
        title.setTextColor(Color.WHITE)
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        head.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        val live = TextView(this)
        live.textSize = 11f
        live.setTextColor(0xFF81C784.toInt())
        live.text = "\u25CF " + tr("LIVE")
        live.setPadding(0, 0, dp(6), 0)
        devLive = live
        head.addView(live, LinearLayout.LayoutParams(WRAP, WRAP))
        fun small(kind: Int, desc: String, click: () -> Unit): ImageButton {
            val b = ImageButton(this)
            b.setImageDrawable(GlyphDrawable(dp(18), kind))
            b.scaleType = ImageView.ScaleType.CENTER
            b.setBackgroundColor(Color.TRANSPARENT)
            b.contentDescription = tr(desc)
            b.setOnClickListener { click() }
            return b
        }
        head.addView(small(0, "Minimise") { toggleDevice() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        head.addView(small(1, "Close") { closeDevice() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(14), 0, dp(14), dp(10))
        for ((sec, rows) in DEV_SECTIONS) {
            val st = TextView(this)
            st.text = tr(sec).uppercase()
            st.textSize = 11f
            st.setTextColor(0xFFFFC107.toInt())
            st.setTypeface(st.typeface, android.graphics.Typeface.BOLD)
            st.setPadding(0, dp(12), 0, dp(4))
            body.addView(st)
            for ((key, label) in rows) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, dp(4), 0, dp(4))
                val l = TextView(this)
                l.text = tr(label)
                l.textSize = 13f
                l.setTextColor(0xFF90A4AE.toInt())
                row.addView(l, LinearLayout.LayoutParams(0, WRAP, 1f))
                val v = TextView(this)
                v.text = "\u2014"
                v.textSize = 13f
                v.setTextColor(Color.WHITE)
                v.gravity = Gravity.END
                row.addView(v, LinearLayout.LayoutParams(0, WRAP, 1.3f))
                body.addView(row)
                devVals[key] = v
                devRows[key] = row
            }
        }
        val sc = ScrollView(this)
        sc.addView(body)
        card.addView(sc, LinearLayout.LayoutParams(MATCH, 0, 1f))
        card.visibility = View.GONE
        val h = resources.displayMetrics.heightPixels * 55 / 100
        frame.addView(card, FrameLayout.LayoutParams(MATCH, h, Gravity.BOTTOM))
        devPanel = card

        val chip = TextView(this)
        chip.textSize = 12f
        chip.setTextColor(Color.WHITE)
        chip.setPadding(dp(12), dp(6), dp(12), dp(6))
        val cg = GradientDrawable()
        cg.setColor(0xCC121820.toInt())
        cg.cornerRadius = dp(16).toFloat()
        cg.setStroke(dp(1), 0xFF33414E.toInt())
        chip.background = cg
        chip.setOnClickListener { toggleDevice() }
        chip.visibility = View.GONE
        val clp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
        clp.setMargins(dp(8), dp(8), dp(8), 0)
        frame.addView(chip, clp)
        devChip = chip
    }

    /** Shows/hides the panel and refreshes every value in place (no flicker, scroll position kept). */
    private fun updateDevicePanel() {
        val panel = devPanel ?: return
        if (!AppState.controlGranted && devState != 0) {
            devState = 0
        }
        panel.visibility = if (devState == 1) View.VISIBLE else View.GONE
        devChip?.visibility = if (devState == 2) View.VISIBLE else View.GONE
        val j = AppState.device ?: return
        if (devState == 0) return
        for ((key, tv) in devVals) {
            val t = try { devText(key, j) } catch (_: Exception) { null }
            devRows[key]?.visibility = if (t == null && key != "model") View.GONE else View.VISIBLE
            if (t != null) tv.text = t
        }
        val age = System.currentTimeMillis() - AppState.deviceAt
        devLive?.text = if (age < 6000) "\u25CF " + tr("LIVE") else "\u25CB " + tr("Waiting\u2026")
        devLive?.setTextColor(if (age < 6000) 0xFF81C784.toInt() else 0xFFFFB74D.toInt())
        val net = devText("net", j) ?: ""
        val bat = if (j.has("bat")) j.optInt("bat").toString() + "%" else ""
        val free = if (j.has("stFree")) String.format(java.util.Locale.US, "%.1f GB", j.optLong("stFree") / 1e9) else ""
        devChip?.text = listOf(bat, net, if (free.isEmpty()) "" else tr("{0} free", free)).filter { it.isNotEmpty() }.joinToString("  \u00b7  ")
    }

    private fun onOff(b: Boolean) = if (b) tr("On") else tr("Off")

    private fun gb(bytes: Long, decimal: Boolean): String =
        String.format(java.util.Locale.US, "%.1f GB", bytes / (if (decimal) 1e9 else 1073741824.0))

    private fun devText(k: String, j: JSONObject): String? {
        fun has(n: String) = j.has(n) && !j.isNull(n)
        return when (k) {
            "model" -> (j.optString("brand").replaceFirstChar { it.uppercase() } + " " + j.optString("model")).trim()
            "android" -> "Android " + j.optString("android") + " (API " + j.optInt("sdk") + ")"
            "patch" -> if (has("patch")) j.optString("patch") else null
            "hw" -> j.optString("hw").ifBlank { null }
            "abi" -> j.optString("abi").ifBlank { null }
            "cores" -> if (has("cores")) j.optInt("cores").toString() else null
            "bat" -> if (has("bat")) j.optInt("bat").toString() + "%" else null
            "chg" -> {
                val st = when (j.optInt("chg", -1)) {
                    2 -> tr("Charging"); 3 -> tr("Discharging"); 4 -> tr("Not charging"); 5 -> tr("Full"); else -> null
                }
                val pl = when (j.optInt("plug", 0)) { 1 -> "AC"; 2 -> "USB"; 4 -> tr("Wireless"); else -> "" }
                if (st == null) null else if (pl.isEmpty()) st else "$st ($pl)"
            }
            "temp" -> if (j.optInt("temp", -1) >= 0) String.format(java.util.Locale.US, "%.1f \u00B0C", j.optInt("temp") / 10.0) else null
            "volt" -> if (j.optInt("volt", -1) > 0) String.format(java.util.Locale.US, "%.2f V", j.optInt("volt") / 1000.0) else null
            "health" -> when (j.optInt("health", -1)) {
                2 -> tr("Good"); 3 -> tr("Overheating"); 4 -> tr("Dead"); 5 -> tr("Over voltage"); 6 -> tr("Failure"); 7 -> tr("Cold"); else -> null
            }
            "saver" -> if (has("saver")) onOff(j.optBoolean("saver")) else null
            "ram" -> if (has("ramTotal")) tr("{0} free of {1}", gb(j.optLong("ramFree"), false), gb(j.optLong("ramTotal"), false)) else null
            "st" -> if (has("stTotal")) tr("{0} free of {1}", gb(j.optLong("stFree"), true), gb(j.optLong("stTotal"), true)) else null
            "net" -> when (j.optString("net")) {
                "wifi" -> "Wi-Fi"; "cell" -> tr("Mobile data"); "eth" -> "Ethernet"; "vpn" -> "VPN"; "none" -> tr("Offline"); else -> tr("Other")
            }
            "netOk" -> if (has("netOk")) (if (j.optBoolean("netOk")) tr("Working") else tr("No internet access")) else null
            "sig" -> if (j.optString("net") == "wifi" && has("rssi")) {
                val r = j.optInt("rssi")
                val q = if (r >= -55) tr("Excellent") else if (r >= -67) tr("Good") else if (r >= -80) tr("Fair") else tr("Weak")
                "$r dBm ($q)"
            } else null
            "speed" -> if (j.optString("net") == "wifi" && has("link")) {
                val f = j.optInt("freq", 0)
                val band = if (f >= 5900) " \u00b7 6 GHz" else if (f >= 4900) " \u00b7 5 GHz" else if (f > 0) " \u00b7 2.4 GHz" else ""
                j.optInt("link").toString() + " Mbps" + band
            } else null
            "op" -> if (j.optString("net") == "cell" && j.optString("op").isNotBlank()) j.optString("op") else null
            "bw" -> if (has("dwn")) String.format(java.util.Locale.US, "\u2193 %.0f  \u2191 %.0f Mbps", j.optInt("dwn") / 1000.0, j.optInt("upl") / 1000.0) else null
            "air" -> if (has("air")) onOff(j.optBoolean("air")) else null
            "res" -> if (has("w")) j.optInt("w").toString() + " \u00D7 " + j.optInt("h") + " \u00b7 " + j.optInt("dpi") + " dpi" else null
            "hz" -> if (has("hz")) Math.round(j.optDouble("hz")).toString() + " Hz" else null
            "bright" -> if (has("bright")) Math.round(j.optInt("bright") * 100f / 255f).toString() + "%" else null
            "scr" -> if (has("on")) onOff(j.optBoolean("on")) + " \u00b7 " + (if (j.optBoolean("land")) tr("Landscape") else tr("Portrait")) else null
            "vm" -> if (j.optInt("vmMax") > 0) (j.optInt("vm") * 100 / j.optInt("vmMax")).toString() + "%" else null
            "vr" -> if (j.optInt("vrMax") > 0) (j.optInt("vr") * 100 / j.optInt("vrMax")).toString() + "%" else null
            "ringer" -> when (j.optInt("ringer", -1)) { 0 -> tr("Silent"); 1 -> tr("Vibrate"); 2 -> tr("Normal"); else -> null }
            "dnd" -> when (j.optInt("dnd", 0)) { 1 -> tr("Off"); 2 -> tr("Priority only"); 3 -> tr("Total silence"); 4 -> tr("Alarms only"); else -> null }
            "boot" -> if (has("boot")) {
                val m = j.optLong("boot") / 60000
                if (m >= 1440) (m / 1440).toString() + " d " + ((m % 1440) / 60) + " h" else (m / 60).toString() + " h " + (m % 60) + " min"
            } else null
            "time" -> if (has("time")) {
                val f = java.text.SimpleDateFormat("EEE d MMM, HH:mm:ss", java.util.Locale.getDefault())
                f.timeZone = java.util.TimeZone.getTimeZone(j.optString("tz", "UTC"))
                f.format(java.util.Date(j.optLong("time")))
            } else null
            "tz" -> j.optString("tz").ifBlank { null }
            "lang" -> j.optString("lang").ifBlank { null }
            "loc" -> if (has("loc")) onOff(j.optBoolean("loc")) else null
            "therm" -> if (has("therm")) when (j.optInt("therm")) {
                0 -> tr("Normal"); 1 -> tr("Slightly warm"); 2 -> tr("Warm"); 3 -> tr("Hot"); else -> tr("Very hot")
            } else null
            else -> null
        }
    }

    /** Asks "are you sure?" so the session is never ended by an accidental tap. */
    private fun confirmEnd(reason: String) {
        dlg()
            .setTitle(tr("End session?"))
            .setMessage(tr("Do you really want to end this session?"))
            .setPositiveButton(tr("Yes, end")) { _, _ -> AppState.session?.end(reason) }
            .setNegativeButton(tr("No"), null)
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
        val d = dlg()
            .setTitle(tr("Type on the other phone"))
            .setMessage(tr("First tap the text box (for example the search bar) on their screen. What you type here appears there instantly."))
            .setView(et)
            .setPositiveButton(tr("Search")) { _, _ ->
                AppState.session?.sendCtl(JSONObject().put("t", "type").put("s", et.text.toString()).put("clear", true).put("enter", true))
            }
            .setNegativeButton(tr("Close"), null)
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
        liveFrame = null
        devPanel = null
        devChip = null
        devLive = null
        devState = 0
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

/** Small phone outline icon (white). */
class PhoneDrawable(private val sizePx: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val u = minOf(b.width(), b.height()) / 24f
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * u
        c.drawRoundRect(cx - 5.5f * u, cy - 9.5f * u, cx + 5.5f * u, cy + 9.5f * u, 2.5f * u, 2.5f * u, paint)
        paint.style = Paint.Style.FILL
        c.drawCircle(cx, cy + 6.6f * u, 1.1f * u, paint)
        c.drawRect(cx - 2f * u, cy - 7f * u, cx + 2f * u, cy - 6f * u, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** kind 0 = chevron down (minimise), kind 1 = cross (close). */
class GlyphDrawable(private val sizePx: Int, private val kind: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val u = minOf(b.width(), b.height()) / 24f
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2.4f * u
        if (kind == 0) {
            c.drawLine(cx - 7f * u, cy - 3f * u, cx, cy + 4f * u, paint)
            c.drawLine(cx, cy + 4f * u, cx + 7f * u, cy - 3f * u, paint)
        } else {
            c.drawLine(cx - 6f * u, cy - 6f * u, cx + 6f * u, cy + 6f * u, paint)
            c.drawLine(cx - 6f * u, cy + 6f * u, cx + 6f * u, cy - 6f * u, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Moon (shown in light theme) or sun (shown in dark theme): tap to switch theme. */
class ThemeDrawable(private val sizePx: Int, private val dark: Boolean) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val u = minOf(b.width(), b.height()) / 24f
        paint.style = Paint.Style.FILL
        if (dark) {
            paint.color = 0xFFFFC107.toInt()
            c.drawCircle(cx, cy, 4.8f * u, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 1.8f * u
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                val x1 = cx + (7.6f * u) * Math.cos(a).toFloat()
                val y1 = cy + (7.6f * u) * Math.sin(a).toFloat()
                val x2 = cx + (10.2f * u) * Math.cos(a).toFloat()
                val y2 = cy + (10.2f * u) * Math.sin(a).toFloat()
                c.drawLine(x1, y1, x2, y2, paint)
            }
        } else {
            paint.color = 0xFF455A64.toInt()
            val p = Path()
            p.addCircle(cx, cy, 8.5f * u, Path.Direction.CW)
            val cut = Path()
            cut.addCircle(cx + 4.6f * u, cy - 3.4f * u, 7.2f * u, Path.Direction.CW)
            p.op(cut, Path.Op.DIFFERENCE)
            c.drawPath(p, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Green tick (ready) or orange exclamation (needs attention). */
class StatusDrawable(private val sizePx: Int, private val ok: Boolean) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f
        val u = r / 12f
        paint.style = Paint.Style.FILL
        paint.color = if (ok) 0xFF2E9E4F.toInt() else 0xFFFFA000.toInt()
        c.drawCircle(cx, cy, r, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2.6f * u
        if (ok) {
            c.drawLine(cx - 5f * u, cy + 0.5f * u, cx - 1.5f * u, cy + 4f * u, paint)
            c.drawLine(cx - 1.5f * u, cy + 4f * u, cx + 5.5f * u, cy - 4f * u, paint)
        } else {
            c.drawLine(cx, cy - 6f * u, cx, cy + 1.5f * u, paint)
            paint.style = Paint.Style.FILL
            c.drawCircle(cx, cy + 5.5f * u, 1.5f * u, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
