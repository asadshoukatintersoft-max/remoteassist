package com.remoteassist.fyp

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The session code is the shared secret: it names the room and encrypts the handshake. */
object Crypto {
    private const val FLAGS = Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING

    private fun key(code: String): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(code.toCharArray(), "remoteassist-v1-salt".toByteArray(), 20000, 256)
        return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    fun topic(code: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("topic:$code".toByteArray())
        val hex = d.joinToString("") { "%02x".format(it) }
        return "ra" + hex.take(30)
    }

    fun seal(code: String, plain: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(plain.toByteArray()) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        val out = iv + c.doFinal(bos.toByteArray())
        return Base64.encodeToString(out, FLAGS)
    }

    /** Returns null if the text is not valid for this code (wrong code or garbage). */
    fun open(code: String, text: String): String? {
        return try {
            val raw = Base64.decode(text, FLAGS)
            val iv = raw.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, iv))
            val gz = c.doFinal(raw, 12, raw.size - 12)
            GZIPInputStream(ByteArrayInputStream(gz)).use { String(it.readBytes()) }
        } catch (e: Exception) {
            null
        }
    }

    fun newCode(): String {
        val r = SecureRandom()
        return (0 until 8).joinToString("") { r.nextInt(10).toString() }
    }
}
