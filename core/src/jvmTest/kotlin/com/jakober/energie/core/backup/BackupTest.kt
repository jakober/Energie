package com.jakober.energie.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupTest {

    @Test
    fun `verschluesseln und entschluesseln liefert den Klartext`() {
        val plain = """{"senecKey":"abc","fritzPassword":"geheim"}""".toByteArray()
        val enc = BackupCrypto.encrypt(plain, "Hm7kRq4TzNvBs9Xd")
        assertEquals("ENB1", String(enc.copyOfRange(0, 4)))
        assertContentEquals(plain, BackupCrypto.decrypt(enc, "Hm7kRq4TzNvBs9Xd"))
    }

    @Test
    fun `falsches Passwort wird erkannt`() {
        val enc = BackupCrypto.encrypt("x".toByteArray(), "richtig")
        assertFailsWith<BackupCrypto.WrongPasswordException> { BackupCrypto.decrypt(enc, "falsch") }
    }

    @Test
    fun `manipulierte Datei wird erkannt`() {
        val enc = BackupCrypto.encrypt("hallo welt".toByteArray(), "pw")
        enc[enc.size - 1] = (enc[enc.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<BackupCrypto.WrongPasswordException> { BackupCrypto.decrypt(enc, "pw") }
    }

    @Test
    fun `zwei Verschluesselungen desselben Texts unterscheiden sich`() {
        val a = BackupCrypto.encrypt("gleich".toByteArray(), "pw")
        val b = BackupCrypto.encrypt("gleich".toByteArray(), "pw")
        assert(!a.contentEquals(b))
    }

    @Test
    fun `archiv schreibt und liest alle Eintraege`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(
            out,
            sequenceOf(
                BackupArchive.Entry(BackupArchive.META) { ByteArrayInputStream("{\"version\":1}".toByteArray()) },
                BackupArchive.Entry("verlauf/2026-09-04.jsonl") { ByteArrayInputStream("{\"at\":\"x\"}\n".toByteArray()) },
            ),
        )
        val seen = LinkedHashMap<String, String>()
        BackupArchive.read(ByteArrayInputStream(out.toByteArray())) { n, b -> seen[n] = String(b) }
        assertEquals(listOf(BackupArchive.META, "verlauf/2026-09-04.jsonl"), seen.keys.toList())
        assertEquals("{\"at\":\"x\"}\n", seen["verlauf/2026-09-04.jsonl"])
    }

    @Test
    fun `verlaufsdatum nur fuer gueltige Namen`() {
        assertEquals("2026-09-04", BackupArchive.historyDate("verlauf/2026-09-04.jsonl"))
        assertNull(BackupArchive.historyDate("verlauf/../evil.jsonl"))
        assertNull(BackupArchive.historyDate("verlauf/x/2026-09-04.jsonl"))
        assertNull(BackupArchive.historyDate("einstellungen.json"))
    }

    @Test
    fun `retention behaelt die neuesten eigenen Dateien`() {
        val names = listOf(
            "energie-backup-2026-09-01.zip", "energie-backup-2026-09-03.zip", "energie-backup-2026-09-02.zip",
            "urlaub.jpg", "energie-backup-2026-08-30.zip",
        )
        assertEquals(listOf("energie-backup-2026-09-01.zip", "energie-backup-2026-08-30.zip"), BackupRetention.filesToDelete(names, keep = 2))
        assertEquals(emptyList(), BackupRetention.filesToDelete(names, keep = 10))
        assertNull(BackupRetention.dateOf("urlaub.jpg"))
    }
}
