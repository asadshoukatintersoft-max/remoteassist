package com.remoteassist.fyp

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny message pipe over ntfy.sh (free, no account). Messages are already encrypted
 * by Crypto before they get here. Large messages are split into small chunks.
 *
 * Wire format of one message:  KIND|ID|INDEX|TOTAL|DATA
 *   KIND = "O" (offer, from phone owner) or "A" (answer, from helper)
 */
class Signaling(
    private val code: String,
    private val listenKind: String,
    private val onPayload: (String) -> Unit
) {
    private val topic = Crypto.topic(code)
    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
    private val parts = HashMap<String, Array<String?>>()
    private val done = HashSet<String>()

    fun start() {
        running = true
        Thread { loop() }.start()
    }

    fun stop() {
        running = false
        try { conn?.disconnect() } catch (_: Exception) {}
    }

    fun send(kind: String, payload: String) {
        Thread {
            val id = Crypto.newId()
            val chunks = payload.chunked(3000)
            for ((i, c) in chunks.withIndex()) {
                val body = "$kind|$id|$i|${chunks.size}|$c"
                var ok = false
                var tries = 0
                while (!ok && tries < 4 && running) {
                    tries++
                    ok = post(body)
                    if (!ok) Thread.sleep(1500)
                }
            }
        }.start()
    }

    private fun post(body: String): Boolean {
        return try {
            val c = URL("${Config.SIGNAL_BASE}/$topic").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "text/plain")
            c.outputStream.use { it.write(body.toByteArray()) }
            val ok = c.responseCode in 200..299
            c.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun loop() {
        while (running) {
            try {
                val url = URL("${Config.SIGNAL_BASE}/$topic/json?since=${Config.CODE_VALID_MIN}m")
                val c = url.openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 15000
                c.readTimeout = 120000
                BufferedReader(InputStreamReader(c.inputStream)).use { r ->
                    while (running) {
                        val line = r.readLine() ?: break
                        if (line.isBlank()) continue
                        val j = try { JSONObject(line) } catch (_: Exception) { continue }
                        if (j.optString("event") == "message") handle(j.optString("message"))
                    }
                }
            } catch (_: Exception) {
                // network hiccup: retry below
            }
            if (running) try { Thread.sleep(2000) } catch (_: Exception) {}
        }
    }

    private fun handle(msg: String) {
        val p = msg.split("|", limit = 5)
        if (p.size < 5 || p[0] != listenKind) return
        val id = p[1]
        val idx = p[2].toIntOrNull() ?: return
        val total = p[3].toIntOrNull() ?: return
        if (total < 1 || idx !in 0 until total || id in done) return
        val arr = parts.getOrPut(id) { arrayOfNulls(total) }
        if (arr.size != total) return
        arr[idx] = p[4]
        if (arr.all { it != null }) {
            done.add(id)
            parts.remove(id)
            onPayload(arr.joinToString("") { it!! })
        }
    }
}
