package com.remoteassist.fyp

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

/**
 * One help session. The phone owner creates the offer and shares the screen;
 * the helper answers, watches, talks, and (only when allowed) controls.
 */
class Session(private val ctx: Context, val role: Role, val code: String) {

    private val main = Handler(Looper.getMainLooper())
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var texHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var dc: DataChannel? = null
    private var signaling: Signaling? = null
    private var gatherCb: (() -> Unit)? = null
    private var gotRemote = false
    @Volatile private var closed = false
    private var remoteVideo: VideoTrack? = null
    private var sink: VideoSink? = null
    private var waitTimeout: Runnable? = null
    private var connectTimeout: Runnable? = null
    private var audioSaved = false
    private var everConnected = false
    private var lostTimeout: Runnable? = null
    private var netPoll: Runnable? = null
    private var remoteAudio: AudioTrack? = null
    private var ownerPoll: Runnable? = null
    private var scaleStep = 0
    private var lowCount = 0
    private var highCount = 0
    private var lastBytes = -1.0
    private var lastBytesT = 0L
    private var lastFrames = -1.0
    private var lastFramesT = 0L

    // ---------------------------------------------------------------- start

    private var fetchedIce: List<PeerConnection.IceServer> = emptyList()

    private fun loadIce(next: () -> Unit) {
        Thread {
            val l = IceFetcher.fetch()
            main.post { fetchedIce = l; if (!closed) next() }
        }.start()
    }

    fun startOwner(data: Intent) = loadIce { doStartOwner(data) }

    fun startHelper() = loadIce { doStartHelper() }

    private fun doStartOwner(data: Intent) {
        try {
            Rtc.init(ctx)
            createPc()
            val p = pc!!
            dc = p.createDataChannel("ctl", DataChannel.Init())
            dc!!.registerObserver(dcObserver)
            addAudio()

            val (w, h) = captureSize(AppState.quality)

            // true = "screen content" mode: crisp text and icons, no denoising/blurring, no surprise resizing.
            videoSource = Rtc.factory.createVideoSource(true)
            // Drop surplus frames right at the source (many phones draw 90-120 times a second).
            videoSource!!.adaptOutputFormat(w, h, Config.FPS)
            texHelper = SurfaceTextureHelper.create("capture", Rtc.egl.eglBaseContext)
            capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { end("Screen sharing was stopped.") }
                }
            })
            capturer!!.initialize(texHelper, ctx, videoSource!!.capturerObserver)
            capturer!!.startCapture(w, h, Config.FPS)

            videoTrack = Rtc.factory.createVideoTrack("screen", videoSource)
            videoTrack!!.setEnabled(false) // stays black until the owner taps Allow
            videoSender = p.addTrack(videoTrack, listOf("s"))

            signaling = Signaling(code, "A") { onAnswer(it) }.also { it.start() }

            p.createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(d: SessionDescription?) {
                    val d2 = if (d != null)
                        SessionDescription(d.type, preferCodec(d.description, if (Config.PREFER_H264) "H264" else "VP8")) else d
                    p.setLocalDescription(SdpAdapter(), d2)
                    main.post { waitGather { sendSdp("O") } }
                }
                override fun onCreateFailure(e: String?) {
                    main.post { end("Could not start: $e", false) }
                }
            }, MediaConstraints())

            waitTimeout = Runnable {
                if (!gotRemote) end("Nobody joined within ${Config.CODE_VALID_MIN} minutes. Start again to get a new code.", false)
            }
            main.postDelayed(waitTimeout!!, Config.CODE_VALID_MIN * 60_000L)

            AppState.phase = Phase.WAITING_FOR_HELPER
            AppState.changed()
        } catch (e: Exception) {
            end("Could not start sharing: ${e.message}", false)
        }
    }

    private fun doStartHelper() {
        try {
            Rtc.init(ctx)
            createPc()
            signaling = Signaling(code, "O") { onOffer(it) }.also { it.start() }
            waitTimeout = Runnable {
                if (!gotRemote) end("Could not find a session with this code. Check the code, and make sure the other person has tapped \"Share my screen\".", false)
            }
            main.postDelayed(waitTimeout!!, 45_000L)
            AppState.phase = Phase.CONNECTING
            AppState.changed()
        } catch (e: Exception) {
            end("Could not start: ${e.message}", false)
        }
    }

    // ----------------------------------------------------------- handshake

    private fun onAnswer(payload: String) {
        main.post {
            if (gotRemote || closed) return@post
            val json = Crypto.open(code, payload) ?: return@post
            gotRemote = true
            val sdp = try { JSONObject(json).getString("sdp") } catch (_: Exception) { return@post }
            pc?.setRemoteDescription(SdpAdapter(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
            AppState.phase = Phase.CONNECTING
            AppState.changed()
            armConnectTimeout()
        }
    }

    private fun onOffer(payload: String) {
        main.post {
            if (gotRemote || closed) return@post
            val json = Crypto.open(code, payload) ?: return@post
            gotRemote = true
            val sdp = try { JSONObject(json).getString("sdp") } catch (_: Exception) { return@post }
            val p = pc ?: return@post
            armConnectTimeout()
            p.setRemoteDescription(object : SdpAdapter() {
                override fun onSetSuccess() {
                    main.post {
                        addAudio()
                        p.createAnswer(object : SdpAdapter() {
                            override fun onCreateSuccess(d: SessionDescription?) {
                                p.setLocalDescription(SdpAdapter(), d)
                                main.post { waitGather { sendSdp("A") } }
                            }
                            override fun onCreateFailure(e: String?) {
                                main.post { end("Could not connect: $e", false) }
                            }
                        }, MediaConstraints())
                    }
                }
                override fun onSetFailure(e: String?) {
                    main.post { end("Could not connect: $e", false) }
                }
            }, SessionDescription(SessionDescription.Type.OFFER, sdp))
        }
    }

    private fun sendSdp(kind: String) {
        val d = pc?.localDescription ?: return
        val json = JSONObject().put("sdp", d.description).toString()
        signaling?.send(kind, Crypto.seal(code, json))
    }

    private fun waitGather(cb: () -> Unit) {
        gatherCb = cb
        if (pc?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            fireGather()
        } else {
            main.postDelayed({ fireGather() }, 5000)
        }
    }

    private fun fireGather() {
        val cb = gatherCb ?: return
        gatherCb = null
        cb()
    }

    private fun armConnectTimeout() {
        connectTimeout?.let { main.removeCallbacks(it) }
        connectTimeout = Runnable {
            if (!closed && AppState.phase == Phase.CONNECTING) {
                end("Could not connect. One of the networks may be blocking direct connections. Try again, or switch one phone to a different Wi-Fi or mobile data.", false)
            }
        }
        main.postDelayed(connectTimeout!!, 40_000L)
    }

    // ------------------------------------------------------ peer connection

    private fun createPc() {
        val ice = ArrayList<PeerConnection.IceServer>()
        for (s in Config.STUN) ice.add(PeerConnection.IceServer.builder(s).createIceServer())
        if (fetchedIce.isNotEmpty()) {
            ice.addAll(fetchedIce)
        } else if (Config.TURN_URLS.isNotEmpty()) {
            ice.add(
                PeerConnection.IceServer.builder(Config.TURN_URLS)
                    .setUsername(Config.TURN_USER)
                    .setPassword(Config.TURN_PASS)
                    .createIceServer()
            )
        }
        val cfg = PeerConnection.RTCConfiguration(ice)
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        pc = Rtc.factory.createPeerConnection(cfg, pcObserver)
    }

    private fun addAudio() {
        audioSource = Rtc.factory.createAudioSource(MediaConstraints())
        audioTrack = Rtc.factory.createAudioTrack("mic", audioSource)
        // The owner's microphone stays off until they approve the helper.
        audioTrack!!.setEnabled(!AppState.micMuted && role == Role.HELPER)
        pc?.addTrack(audioTrack, listOf("s"))
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            main.post {
                if (closed) return@post
                when (s) {
                    PeerConnection.IceConnectionState.FAILED ->
                        if (everConnected) {
                            // Already connected once: never drop the session on a network blip.
                            AppState.message = "Connection lost. Trying to reconnect…"
                            AppState.changed()
                            armLostTimeout()
                        } else {
                            end(
                                if (Config.TURN_API_URL.isBlank())
                                    "The two phones could not reach each other. They are on different networks and no relay server is set up yet (see Config.kt)."
                                else "The connection failed. Please try again.", false)
                        }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        AppState.message = "Connection is unstable…"
                        AppState.changed()
                        armLostTimeoutIfConnected()
                    }
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        lostTimeout?.let { main.removeCallbacks(it) }
                        lostTimeout = null
                        tuneVideo()
                        AppState.message = ""
                        AppState.changed()
                    }
                    else -> {}
                }
            }
        }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) main.post { fireGather() }
        }
        override fun onIceCandidate(c: IceCandidate?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(ch: DataChannel?) {
            ch ?: return
            dc = ch
            ch.registerObserver(dcObserver)
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {
            val t = r?.track()
            if (t is VideoTrack) {
                remoteVideo = t
                sink?.let { t.addSink(it) }
            } else if (t is AudioTrack) {
                remoteAudio = t
                t.setEnabled(!AppState.speakerMuted)
            }
        }
    }

    /** If the link drops after a session started, wait this long for it to come back before giving up. */
    private fun armLostTimeoutIfConnected() {
        if (everConnected) armLostTimeout()
    }

    private fun armLostTimeout() {
        if (lostTimeout != null) return
        lostTimeout = Runnable { end("The connection was lost and could not be restored.", false) }
        main.postDelayed(lostTimeout!!, Config.RECONNECT_MIN * 60_000L)
    }

    // ---------------------------------------------------------- data channel

    private val dcObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(l: Long) {}
        override fun onStateChange() {
            if (dc?.state() == DataChannel.State.OPEN) main.post { onChannelOpen() }
        }
        override fun onMessage(b: DataChannel.Buffer?) {
            b ?: return
            val bytes = ByteArray(b.data.remaining())
            b.data.get(bytes)
            val text = String(bytes)
            main.post { handle(text) }
        }
    }

    private fun onChannelOpen() {
        if (closed) return
        everConnected = true
        startNetPoll()
        startOwnerPoll()
        connectTimeout?.let { main.removeCallbacks(it) }
        waitTimeout?.let { main.removeCallbacks(it) }
        useSpeaker(true)
        AppState.phase = Phase.WAITING_APPROVAL
        AppState.message = ""
        AppState.changed()
    }

    fun sendCtl(j: JSONObject) {
        val d = dc ?: return
        if (d.state() != DataChannel.State.OPEN) return
        d.send(DataChannel.Buffer(ByteBuffer.wrap(j.toString().toByteArray()), false))
    }

    private fun handle(msg: String) {
        if (closed) return
        val j = try { JSONObject(msg) } catch (_: Exception) { return }
        when (j.optString("t")) {
            "view" -> if (role == Role.HELPER) {
                AppState.phase = Phase.LIVE
                AppState.changed()
            }
            "grant" -> if (role == Role.HELPER) {
                AppState.controlGranted = j.optBoolean("on")
                AppState.message = if (AppState.controlGranted) "" else "Control was taken back. You are watching only."
                AppState.changed()
            }
            "q" -> if (role == Role.OWNER) applyQuality(j.optInt("v", Config.DEFAULT_QUALITY))
            "req" -> if (role == Role.OWNER) {
                AppState.controlRequested = true
                AppState.changed()
            }
            "err" -> if (role == Role.HELPER) {
                AppState.message = j.optString("m")
                AppState.changed()
            }
            "tap", "swipe", "nav", "type", "ts", "tm", "te" -> if (role == Role.OWNER && AppState.controlGranted) applyControl(j)
            "bye" -> end(
                if (role == Role.OWNER) "The helper left the session." else "The other person ended the session.",
                false
            )
        }
    }

    private fun applyControl(j: JSONObject) {
        val svc = ControlService.instance
        if (svc == null) {
            sendCtl(JSONObject().put("t", "err").put("m", "Remote control is not turned on on the other phone."))
            return
        }
        when (j.optString("t")) {
            "tap" -> svc.tap(j.optDouble("x").toFloat(), j.optDouble("y").toFloat())
            "swipe" -> svc.swipe(
                j.optDouble("x1").toFloat(), j.optDouble("y1").toFloat(),
                j.optDouble("x2").toFloat(), j.optDouble("y2").toFloat(),
                j.optLong("d", 300)
            )
            "nav" -> svc.nav(j.optString("k"))
            "ts" -> svc.touchStart(j.optDouble("x").toFloat(), j.optDouble("y").toFloat())
            "tm" -> svc.touchMove(j.optDouble("x").toFloat(), j.optDouble("y").toFloat())
            "te" -> svc.touchEnd(j.optDouble("x", -1.0).toFloat(), j.optDouble("y", -1.0).toFloat())
            "type" -> {
                val ok = svc.typeText(j.optString("s"), j.optBoolean("clear"), j.optBoolean("enter"))
                if (!ok) sendCtl(JSONObject().put("t", "err").put("m", "No text box is selected on the other phone. Tap a text box first, then type again."))
            }
        }
    }

    // ---------------------------------------------------------------- quality

    /** Reorders the video codecs so hardware H.264 is tried first (falls back to VP8 automatically). */
    private fun preferCodec(sdp: String, codec: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val mIdx = lines.indexOfFirst { it.startsWith("m=video") }
        if (mIdx < 0) return sdp
        val h264 = lines.filter { it.startsWith("a=rtpmap:") && it.contains(" $codec/") }
            .map { it.substringAfter("a=rtpmap:").substringBefore(" ") }
        if (h264.isEmpty()) return sdp
        val parts = lines[mIdx].split(" ")
        if (parts.size <= 3) return sdp
        val pts = parts.drop(3)
        val ordered = pts.filter { it in h264 } + pts.filter { it !in h264 }
        lines[mIdx] = (parts.take(3) + ordered).joinToString(" ")
        return lines.joinToString("\r\n")
    }

    /** Size of the shared picture for a quality level (always the full screen shape). */
    private fun captureSize(level: Int): Pair<Int, Int> {
        val l = level.coerceIn(0, Config.Q_NAMES.size - 1)
        val (sw, sh) = realScreenSize(ctx)
        val scale = minOf(1f, Config.Q_SHORT_SIDE[l].toFloat() / minOf(sw, sh))
        return Pair((sw * scale).toInt() / 2 * 2, (sh * scale).toInt() / 2 * 2)
    }

    /** Owner: switch to another quality level while the session runs. */
    fun applyQuality(level: Int) {
        if (role != Role.OWNER) return
        val l = level.coerceIn(0, Config.Q_NAMES.size - 1)
        AppState.quality = l
        tuneVideo()
        AppState.changed()
    }

    /** Helper: ask the other phone to change the picture quality. */
    fun setQuality(level: Int) {
        val l = level.coerceIn(0, Config.Q_NAMES.size - 1)
        AppState.quality = l
        sendCtl(JSONObject().put("t", "q").put("v", l))
        AppState.changed()
    }

    /** Sets the bitrate limits and tells WebRTC to keep the motion smooth (it lowers sharpness first). */
    private fun tuneVideo() {
        if (role != Role.OWNER) return
        val maxBr = Config.Q_MAX_BITRATE[AppState.quality.coerceIn(0, Config.Q_NAMES.size - 1)]
        try {
            val s = videoSender
            if (s != null) {
                val p = s.parameters
                for (e in p.encodings) {
                    e.maxBitrateBps = maxBr
                    e.minBitrateBps = Config.MIN_BITRATE
                    e.maxFramerate = Config.FPS
                    e.scaleResolutionDownBy = SCALES[scaleStep.coerceIn(0, SCALES.size - 1)]
                }
                // Keep the picture sharp; the step ladder below lowers the size first when the network is weak.
                p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                s.setParameters(p)
            }
        } catch (_: Exception) {}
        try { pc?.setBitrate(Config.MIN_BITRATE, maxBr / 2, maxBr) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- actions

    // ----------------------------------------------- owner: automatic picture size

    private val SCALES = doubleArrayOf(1.0, 1.35, 1.7)

    private fun startOwnerPoll() {
        if (role != Role.OWNER || ownerPoll != null) return
        val r = object : Runnable {
            override fun run() {
                if (closed) return
                pollOwner()
                main.postDelayed(this, 2000L)
            }
        }
        ownerPoll = r
        main.postDelayed(r, 3000L)
    }

    /** Reads how much the connection can carry right now, then picks the sharpest picture size it can keep smooth. */
    private fun pollOwner() {
        val p = pc ?: return
        p.getStats { report ->
            var est = -1.0
            try {
                val map = report.statsMap
                val tr = map.values.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
                var pair = if (tr != null) map[tr.members["selectedCandidatePairId"] as? String ?: ""] else null
                if (pair == null) {
                    pair = map.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
                }
                est = (pair?.members?.get("availableOutgoingBitrate") as? Number)?.toDouble() ?: -1.0
            } catch (_: Exception) {
            }
            if (est > 0) main.post { adaptScale(est) }
        }
    }

    private fun stepFor(est: Double): Int = if (est >= 1_500_000) 0 else if (est >= 850_000) 1 else 2

    private fun adaptScale(est: Double) {
        if (closed) return
        val want = stepFor(est)
        if (want > scaleStep) {
            // weaker connection: shrink the picture quickly so it stays smooth
            lowCount++
            highCount = 0
            if (lowCount >= 2) { scaleStep = want; lowCount = 0; tuneVideo() }
        } else if (want < scaleStep && est >= (if (scaleStep == 1) 1_900_000.0 else 1_100_000.0)) {
            // clearly stronger connection: grow back slowly, one step at a time
            highCount++
            lowCount = 0
            if (highCount >= 4) { scaleStep -= 1; highCount = 0; tuneVideo() }
        } else {
            lowCount = 0
            highCount = 0
        }
    }

    // ----------------------------------------------- connection info (helper side)

    /** Shows "Direct" or "Relay" plus the round-trip time, so a slow link is easy to diagnose. */
    private fun startNetPoll() {
        if (role != Role.HELPER || netPoll != null) return
        val r = object : Runnable {
            override fun run() {
                if (closed) return
                pollNet()
                main.postDelayed(this, 2000L)
            }
        }
        netPoll = r
        main.postDelayed(r, 1500L)
    }

    private fun pollNet() {
        val p = pc ?: return
        p.getStats { report ->
            var text = ""
            try {
                val map = report.statsMap
                val tr = map.values.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
                var pair = if (tr != null) map[tr.members["selectedCandidatePairId"] as? String ?: ""] else null
                if (pair == null) {
                    pair = map.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
                }
                var link = ""
                if (pair != null) {
                    val lc = map[pair.members["localCandidateId"] as? String ?: ""]
                    val lt = lc?.members?.get("candidateType") as? String
                    val rt = map[pair.members["remoteCandidateId"] as? String ?: ""]?.members?.get("candidateType") as? String
                    val rtt = (pair.members["currentRoundTripTime"] as? Number)?.toDouble()
                    val relay = lt == "relay" || rt == "relay"
                    val proto = (lc?.members?.get("relayProtocol") as? String) ?: (lc?.members?.get("protocol") as? String) ?: ""
                    link = (if (relay) "Relay" else "Direct") + (if (relay && proto.isNotEmpty()) "/$proto" else "")
                    if (rtt != null) link += " ${(rtt * 1000).toInt()} ms"
                }
                // picture: frames per second, size and speed actually received
                var pic = ""
                val inb = map.values.firstOrNull { it.type == "inbound-rtp" && (it.members["kind"] == "video" || it.members["mediaType"] == "video") }
                if (inb != null) {
                    val frames = (inb.members["framesDecoded"] as? Number)?.toDouble()
                    val bytes = (inb.members["bytesReceived"] as? Number)?.toDouble()
                    val now = System.currentTimeMillis()
                    var fps = -1
                    var mbps = -1.0
                    if (frames != null) {
                        if (lastFrames >= 0 && now > lastFramesT) fps = Math.round((frames - lastFrames) * 1000.0 / (now - lastFramesT)).toInt()
                        lastFrames = frames
                        lastFramesT = now
                    }
                    if (bytes != null) {
                        if (lastBytes >= 0 && now > lastBytesT) mbps = (bytes - lastBytes) * 8.0 / ((now - lastBytesT) * 1000.0)
                        lastBytes = bytes
                        lastBytesT = now
                    }
                    val w = (inb.members["frameWidth"] as? Number)?.toInt()
                    val h = (inb.members["frameHeight"] as? Number)?.toInt()
                    if (fps >= 0) pic += " \u00b7 $fps fps"
                    if (w != null && h != null && w > 0) pic += " \u00b7 ${minOf(w, h)}p"
                    if (mbps >= 0) pic += " \u00b7 " + String.format(java.util.Locale.US, "%.1f", mbps) + " Mb"
                }
                text = link + pic
            } catch (_: Exception) {
            }
            if (text.isNotEmpty()) {
                main.post {
                    if (!closed && AppState.net != text) {
                        AppState.net = text
                        AppState.changed()
                    }
                }
            }
        }
    }

    /** Owner: let the helper see the screen. */
    fun approveView() {
        videoTrack?.setEnabled(true)
        audioTrack?.setEnabled(!AppState.micMuted)
        sendCtl(JSONObject().put("t", "view").put("on", true))
        AppState.phase = Phase.LIVE
        AppState.changed()
    }

    /** Owner: give or take back control. */
    fun setControl(on: Boolean) {
        AppState.controlGranted = on
        AppState.controlRequested = false
        if (!on) ControlService.instance?.touchCancel()
        sendCtl(JSONObject().put("t", "grant").put("on", on))
        AppState.changed()
    }

    /** Helper: ask the owner for control. */
    fun requestControl() {
        sendCtl(JSONObject().put("t", "req"))
        AppState.message = "Request sent. Waiting for the other person to allow it…"
        AppState.changed()
    }

    fun setMic(on: Boolean) {
        AppState.micMuted = !on
        audioTrack?.setEnabled(on)
        AppState.changed()
    }

    fun setSpeaker(on: Boolean) {
        AppState.speakerMuted = !on
        remoteAudio?.setEnabled(on)
        AppState.changed()
    }

    fun setSink(s: VideoSink?) {
        sink?.let { remoteVideo?.removeSink(it) }
        sink = s
        if (s != null) remoteVideo?.addSink(s)
    }

    private var savedMode = AudioManager.MODE_NORMAL
    private fun useSpeaker(on: Boolean) {
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (on) {
                if (!audioSaved) { savedMode = am.mode; audioSaved = true }
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            } else if (audioSaved) {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
                am.mode = savedMode
                audioSaved = false
            }
        } catch (_: Exception) {}
    }

    // ----------------------------------------------------------------- end

    fun end(reason: String, tellPeer: Boolean = true) {
        if (closed) return
        if (tellPeer) sendCtl(JSONObject().put("t", "bye"))
        closed = true
        AppState.message = reason
        AppState.phase = Phase.ENDED
        AppState.controlGranted = false
        AppState.controlRequested = false
        AppState.changed()
        main.postDelayed({ release() }, if (tellPeer) 500L else 0L)
    }

    private fun release() {
        waitTimeout?.let { main.removeCallbacks(it) }
        connectTimeout?.let { main.removeCallbacks(it) }
        lostTimeout?.let { main.removeCallbacks(it) }
        netPoll?.let { main.removeCallbacks(it) }
        ownerPoll?.let { main.removeCallbacks(it) }
        useSpeaker(false)
        try { signaling?.stop() } catch (_: Exception) {}
        try { setSink(null) } catch (_: Exception) {}
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { texHelper?.dispose() } catch (_: Exception) {}
        try { dc?.close() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
        try { videoTrack?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { audioTrack?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { pc?.dispose() } catch (_: Exception) {}
        pc = null
    }
}
