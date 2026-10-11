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

    // ---- Video quality (raise for sharper, lower for slower networks / weaker phones)
    const val MAX_SHORT_SIDE = 1080      // pixels on the short side of the shared screen
    const val FPS = 24
    const val START_BITRATE = 2_000_000  // bits per second at start
    const val MIN_BITRATE = 600_000
    const val MAX_BITRATE = 5_000_000
    /** Hardware H.264 is much faster than the default software VP8 on most phones. */
    const val PREFER_H264 = true

    /** Digits in the session code. 4 is easy to read out; 6 is safer. */
    const val CODE_LENGTH = 4

    /** How long a code stays valid while waiting for the helper. */
    const val CODE_VALID_MIN = 5
}
