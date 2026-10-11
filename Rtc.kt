package com.remoteassist.fyp

import android.content.Context
import android.hardware.display.DisplayManager
import org.json.JSONArray
import org.webrtc.PeerConnection
import java.net.HttpURLConnection
import java.net.URL
import android.util.DisplayMetrics
import android.view.Display
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/** One shared WebRTC engine for the whole app. */
object Rtc {
    lateinit var egl: EglBase
    lateinit var factory: PeerConnectionFactory
    private var inited = false

    @Synchronized
    fun init(ctx: Context) {
        if (inited) return
        val app = ctx.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app)
                // Ask for zero buffering delay on the receiving side: the picture is shown as soon as it arrives.
                .setFieldTrials("WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:0/")
                .createInitializationOptions()
        )
        egl = EglBase.create()
        val adm = JavaAudioDeviceModule.builder(app).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        inited = true
    }
}

/** Real screen size in pixels (width, height) in the current orientation. */
fun realScreenSize(ctx: Context): Pair<Int, Int> {
    val dm = DisplayMetrics()
    val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    d.getRealMetrics(dm)
    return Pair(dm.widthPixels, dm.heightPixels)
}


/** Downloads fresh relay (TURN) credentials if Config.TURN_API_URL is set. Call off the main thread. */
object IceFetcher {
    fun fetch(): List<PeerConnection.IceServer> {
        if (Config.TURN_API_URL.isBlank()) return emptyList()
        return try {
            val c = URL(Config.TURN_API_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val arr = JSONArray(text)
            val out = ArrayList<PeerConnection.IceServer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val urls = ArrayList<String>()
                val u = o.opt("urls")
                if (u is JSONArray) { for (k in 0 until u.length()) urls.add(u.getString(k)) }
                else if (u is String) urls.add(u)
                if (urls.isEmpty()) continue
                val b = PeerConnection.IceServer.builder(urls)
                if (o.has("username")) b.setUsername(o.getString("username"))
                if (o.has("credential")) b.setPassword(o.getString("credential"))
                out.add(b.createIceServer())
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
