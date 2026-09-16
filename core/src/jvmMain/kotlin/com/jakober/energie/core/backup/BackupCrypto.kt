package com.jakober.energie.core.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verschluesselt die Zugangsdaten im Backup mit einem Passwort.
 *
 * Aufbau der Ausgabe: Kennung `ENB1`, 16 Byte Salz, 12 Byte Nonce, dann
 * AES-256-GCM-Chiffrat samt Pruefsumme. Der Schluessel entsteht per
 * PBKDF2-HMAC-SHA256 mit 200 000 Runden aus dem Passwort - das bremst
 * Durchprobieren, ohne auf dem Handy spuerbar zu dauern (unter einer Sekunde).
 * Nur javax.crypto, laeuft daher auch in den JVM-Tests.
 */
object BackupCrypto {
    private val MAGIC = "ENB1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    const val ITERATIONS = 200_000

    class WrongPasswordException : Exception("Falsches Passwort oder beschaedigte Datei")

    fun encrypt(plain: ByteArray, password: String, random: SecureRandom = SecureRandom()): ByteArray {
        require(password.isNotEmpty()) { "Passwort darf nicht leer sein" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce))
        val body = cipher.doFinal(plain)
        return MAGIC + salt + nonce + body
    }

    fun decrypt(data: ByteArray, password: String): ByteArray {
        if (data.size < MAGIC.size + SALT_BYTES + NONCE_BYTES + TAG_BITS / 8) throw WrongPasswordException()
        if (!data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IllegalArgumentException("Keine Energie-Sicherung (Kennung fehlt)")
        var o = MAGIC.size
        val salt = data.copyOfRange(o, o + SALT_BYTES); o += SALT_BYTES
        val nonce = data.copyOfRange(o, o + NONCE_BYTES); o += NONCE_BYTES
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce))
        return try {
            cipher.doFinal(data, o, data.size - o)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
