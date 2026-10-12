package com.remoteassist.fyp

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.Display
import android.hardware.display.DisplayManager
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/** Reads live facts about THIS phone. Everything here needs no special permission. */
object DeviceInfo {

    fun collect(ctx: Context): JSONObject {
        val j = JSONObject()
        j.put("t", "dev")
        try { device(j) } catch (_: Exception) {}
        try { battery(ctx, j) } catch (_: Exception) {}
        try { memory(ctx, j) } catch (_: Exception) {}
        try { network(ctx, j) } catch (_: Exception) {}
        try { screen(ctx, j) } catch (_: Exception) {}
        try { sound(ctx, j) } catch (_: Exception) {}
        try { system(ctx, j) } catch (_: Exception) {}
        return j
    }

    private fun device(j: JSONObject) {
        j.put("brand", Build.MANUFACTURER ?: "")
        j.put("model", Build.MODEL ?: "")
        j.put("android", Build.VERSION.RELEASE ?: "")
        j.put("sdk", Build.VERSION.SDK_INT)
        if (Build.VERSION.SDK_INT >= 23) j.put("patch", Build.VERSION.SECURITY_PATCH ?: "")
        j.put("abi", (Build.SUPPORTED_ABIS?.firstOrNull() ?: ""))
        j.put("cores", Runtime.getRuntime().availableProcessors())
        j.put("hw", Build.HARDWARE ?: "")
    }

    private fun battery(ctx: Context, j: JSONObject) {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i != null) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) j.put("bat", Math.round(level * 100f / scale))
            j.put("chg", i.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
            j.put("plug", i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
            j.put("temp", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
            j.put("volt", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
            j.put("health", i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1))
        }
        val pm = ctx.getSystemService(PowerManager::class.java)
        j.put("saver", pm.isPowerSaveMode)
    }

    private fun memory(ctx: Context, j: JSONObject) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        j.put("ramTotal", mi.totalMem)
        j.put("ramFree", mi.availMem)
        val st = StatFs(Environment.getDataDirectory().path)
        j.put("stTotal", st.totalBytes)
        j.put("stFree", st.availableBytes)
    }

    @Suppress("DEPRECATION")
    private fun network(ctx: Context, j: JSONObject) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork
        val caps = if (n != null) cm.getNetworkCapabilities(n) else null
        var kind = "none"
        if (caps != null) {
            kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            j.put("netOk", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            j.put("dwn", caps.linkDownstreamBandwidthKbps)
            j.put("upl", caps.linkUpstreamBandwidthKbps)
        }
        j.put("net", kind)
        if (kind == "wifi") {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wi = wm?.connectionInfo
            if (wi != null) {
                if (wi.rssi > -127) j.put("rssi", wi.rssi)
                if (wi.linkSpeed > 0) j.put("link", wi.linkSpeed)
                if (Build.VERSION.SDK_INT >= 21 && wi.frequency > 0) j.put("freq", wi.frequency)
            }
        }
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val op = tm?.networkOperatorName
        if (!op.isNullOrBlank()) j.put("op", op)
        j.put("air", Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
    }

    private fun screen(ctx: Context, j: JSONObject) {
        val dm = DisplayMetrics()
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        d.getRealMetrics(dm)
        j.put("w", dm.widthPixels)
        j.put("h", dm.heightPixels)
        j.put("dpi", dm.densityDpi)
        j.put("hz", d.refreshRate.toDouble())
        try {
            j.put("bright", Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        } catch (_: Exception) {}
        val pm = ctx.getSystemService(PowerManager::class.java)
        j.put("on", pm.isInteractive)
        j.put("land", dm.widthPixels > dm.heightPixels)
    }

    private fun sound(ctx: Context, j: JSONObject) {
        val am = ctx.getSystemService(AudioManager::class.java)
        j.put("vm", am.getStreamVolume(AudioManager.STREAM_MUSIC))
        j.put("vmMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        j.put("vr", am.getStreamVolume(AudioManager.STREAM_RING))
        j.put("vrMax", am.getStreamMaxVolume(AudioManager.STREAM_RING))
        j.put("ringer", am.ringerMode)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        j.put("dnd", nm.currentInterruptionFilter)
    }

    private fun system(ctx: Context, j: JSONObject) {
        j.put("boot", SystemClock.elapsedRealtime())
        j.put("time", System.currentTimeMillis())
        j.put("tz", TimeZone.getDefault().id)
        j.put("lang", Locale.getDefault().displayName)
        if (Build.VERSION.SDK_INT >= 28) {
            val lm = ctx.getSystemService(LocationManager::class.java)
            j.put("loc", lm.isLocationEnabled)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            j.put("therm", pm.currentThermalStatus)
        }
    }
}
