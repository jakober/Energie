package com.jakober.energie.core.backup

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Das Backup ist eine gewoehnliche ZIP-Datei:
 *
 * ```
 * META.json            Version, Zeitpunkt, Anzahl Tage
 * einstellungen.json   alles ohne Geheimnisse (Preise, Regeln, Zuhause, ...)
 * zugangsdaten.enc     mit Passwort verschluesselte Geheimnisse (siehe BackupCrypto)
 * verlauf/2026-09-04.jsonl ...
 * ```
 *
 * Laesst sich mit jedem Dateimanager oeffnen; nur die Zugangsdaten bleiben verschlossen.
 */
object BackupArchive {
    const val META = "META.json"
    const val SETTINGS = "einstellungen.json"
    const val SECRETS = "zugangsdaten.enc"
    const val HISTORY_DIR = "verlauf/"
    const val FORMAT_VERSION = 1

    /** Dateiname fuer den Tag, etwa `energie-backup-2026-09-04.zip`. */
    fun fileName(date: String): String = "energie-backup-$date.zip"

    class Entry(val name: String, val open: () -> InputStream)

    fun write(out: OutputStream, entries: Sequence<Entry>) {
        ZipOutputStream(out).use { zip ->
            for (e in entries) {
                zip.putNextEntry(ZipEntry(e.name))
                e.open().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Liest alle Eintraege; `onEntry` bekommt Name und Inhalt. */
    fun read(input: InputStream, onEntry: (name: String, bytes: ByteArray) -> Unit) {
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) onEntry(entry.name, zip.readBytes())
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /** Tagesdatum aus einem Verlaufs-Eintrag, sonst null. */
    fun historyDate(entryName: String): String? {
        if (!entryName.startsWith(HISTORY_DIR)) return null
        val base = entryName.removePrefix(HISTORY_DIR)
        if (base.contains('/') || !base.endsWith(".jsonl")) return null
        val date = base.removeSuffix(".jsonl")
        return date.takeIf { DATE.matches(it) }
    }

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
}
