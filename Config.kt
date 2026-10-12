package com.remoteassist.fyp

/**
 * The only settings you may ever want to change.
 *
 * SIGNAL_BASE: free public message relay (ntfy.sh) used ONLY to exchange the
 *   connection handshake. It is encrypted with the session code, so ntfy.sh
 *   cannot read it. Screen and voice never pass through it.
 *
 * TURN_*: a relay used when two phones cannot connect directly (strict mobile
 *   networks). Replace with your own free credentials from https://www.metered.ca
 *   (free tier) if connections fail on mobile data.
 */
object Config {
    const val SIGNAL_BASE = "https://ntfy.sh"

    val STUN = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302"
    )

    val TURN_URLS = listOf(
        "turn:openrelay.metered.ca:80",
        "turn:openrelay.metered.ca:443",
        "turn:openrelay.metered.ca:443?transport=tcp"
    )
    const val TURN_USER = "openrelayproject"
    const val TURN_PASS = "openrelayproject"

    /** Remote tap/swipe control. Off = view + voice only (installs with less friction). */
    const val CONTROL_ENABLED = true

    /**
     * Free relay credentials fetched fresh for every session (needed when the two phones are on
     * different networks, e.g. Wi-Fi + mobile data). Leave "" to use the static TURN list above.
     * Example (Metered free account):
     *   "https://YOURAPP.metered.live/api/v1/turn/credentials?apiKey=YOURKEY"
     */
    const val TURN_API_URL = ""

    // ---- Video quality. Level 2 (Sharp) is always used; the lower levels are kept only for experiments.
    //      Levels:            Fast     Normal     Sharp
    val Q_NAMES = listOf("Fast", "Normal", "Sharp")
    val Q_SHORT_SIDE = intArrayOf(540, 720, 1080)           // pixels on the short side of the shared screen
    val Q_MAX_BITRATE = intArrayOf(1_200_000, 2_500_000, 4_000_000)
    const val DEFAULT_QUALITY = 2   // full-HD: the best picture; WebRTC lowers it automatically if the network or phone cannot keep up
    const val FPS = 30
    const val MIN_BITRATE = 100_000
    /** VP8 works smoothly on every phone. Hardware H.264 can stutter on some phones, so it is off. */
    const val PREFER_H264 = false

    /** Digits in the session code. 4 is easy to read out; 6 is safer. */
    const val CODE_LENGTH = 4

    /** How long a code stays valid while waiting for the helper. */
    const val CODE_VALID_MIN = 30

    /** After a session has started it only ends by button. If the link drops, wait this long to recover. */
    const val RECONNECT_MIN = 30
}
