package com.remoteassist.fyp

import android.content.Context
import android.hardware.display.DisplayManager
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
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
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
