package com.remoteassist.fyp

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

enum class Role { OWNER, HELPER }

enum class Phase {
    IDLE,
    CONNECTING,
    WAITING_FOR_HELPER,   // owner: code is shown, nobody joined yet
    WAITING_APPROVAL,     // owner: helper joined, must tap Allow. helper: waiting for that tap
    LIVE,
    ENDED
}

/** Shared state that the screens and the background service both read. */
object AppState {
    @Volatile var role: Role? = null
    @Volatile var phase: Phase = Phase.IDLE
    @Volatile var code: String = ""
    @Volatile var message: String = ""
    @Volatile var controlGranted = false
    @Volatile var controlRequested = false
    @Volatile var micMuted = false
    @Volatile var speakerMuted = false
    @Volatile var quality = Config.DEFAULT_QUALITY
    @Volatile var net: String = ""
    @Volatile var device: JSONObject? = null
    @Volatile var deviceAt = 0L
    @Volatile var deviceStreaming = false
    @Volatile var session: Session? = null

    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun changed() {
        main.post { for (l in listeners) l() }
    }

    fun reset() {
        role = null
        phase = Phase.IDLE
        code = ""
        message = ""
        controlGranted = false
        controlRequested = false
        micMuted = false
        speakerMuted = false
        quality = Config.DEFAULT_QUALITY
        net = ""
        device = null
        deviceAt = 0L
        deviceStreaming = false
        session = null
        changed()
    }
}
