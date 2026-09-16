package com.jakober.energie.core.fritz

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Berechnet die Antwort auf die Login-Challenge der FRITZ!Box.
 *
 * Seit FRITZ!OS 7.24 schickt die Box eine Challenge der Form
 * `2$<iter1>$<salt1>$<iter2>$<salt2>` und erwartet zwei hintereinander
 * ausgefuehrte PBKDF2-HMAC-SHA256-Ableitungen. Aeltere Boxen liefern eine
 * kurze Zeichenkette und erwarten MD5 ueber `challenge-passwort` in UTF-16LE.
 * Beides ist in AVMs Dokument "Login-Verfahren" beschrieben.
 */
object FritzChallenge {

    fun response(challenge: String, password: String): String =
        if (challenge.startsWith("2$")) pbkdf2Response(challenge, password)
        else md5Response(challenge, password)

    internal fun pbkdf2Response(challenge: String, password: String): String {
        val parts = challenge.split("$")
        require(parts.size == 5) { "Unerwartetes Challenge-Format: $challenge" }
        val iter1 = parts[1].toInt()
        val salt1 = parts[2].hexToBytes()
        val iter2 = parts[3].toInt()
        val salt2 = parts[4].hexToBytes()

        val hash1 = pbkdf2(password.toByteArray(Charsets.UTF_8), salt1, iter1)
        // Die zweite Runde nimmt das erste Ergebnis als rohe Bytes. Javas
        // PBKDF2WithHmacSHA256 wuerde die Eingabe als Text kodieren, daher
        // eine eigene, kleine PBKDF2-Implementierung ueber HMAC.
        val hash2 = pbkdf2(hash1, salt2, iter2)
        return parts[4] + "$" + hash2.toHex()
    }

    internal fun md5Response(challenge: String, password: String): String {
        val md5 = MessageDigest.getInstance("MD5")
        val digest = md5.digest("$challenge-$password".toByteArray(Charsets.UTF_16LE))
        return "$challenge-${digest.toHex()}"
    }

    /** PBKDF2-HMAC-SHA256 mit 32 Byte Ausgabe (genau ein Block). */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val out = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in out.indices) out[j] = (out[j].toInt() xor u[j].toInt()).toByte()
        }
        return out
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Ungerade Hex-Laenge: $this" }
        return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
