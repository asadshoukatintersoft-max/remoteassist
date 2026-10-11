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

    /** How long a code stays valid while waiting for the helper. */
    const val CODE_VALID_MIN = 15
}
