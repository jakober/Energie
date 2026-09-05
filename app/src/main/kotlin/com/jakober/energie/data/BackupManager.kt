package com.jakober.energie.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.jakober.energie.core.backup.BackupArchive
import com.jakober.energie.core.backup.BackupCrypto
import com.jakober.energie.core.backup.BackupRetention
import com.jakober.energie.core.history.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream

/**
 * Schreibt die Sicherung in den vom Nutzer gewaehlten Ordner (Storage Access
 * Framework, daher auch Google Drive oder OneDrive ohne eigene Anmeldung) und
 * liest sie von dort wieder ein.
 */
class BackupManager(
    private val context: Context,
    private val settings: AppSettings,
    private val history: HistoryStore,
    private val repository: EnergyRepository,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { prettyPrint = true }

    class Summary(val fileName: String, val days: Int, val bytes: Long)

    /** Sichert jetzt; wirft bei Fehlern mit verstaendlicher Meldung. */
    suspend fun backupNow(): Summary = withContext(Dispatchers.IO) {
        val s = settings.current()
        check(s.backupTreeUri.isNotBlank()) { "Kein Zielordner gewaehlt." }
        check(s.backupPassword.length >= 8) { "Backup-Passwort fehlt oder ist kuerzer als 8 Zeichen." }
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(s.backupTreeUri))
            ?: error("Zielordner nicht mehr erreichbar. Bitte neu waehlen.")
        if (!tree.canWrite()) error("Kein Schreibrecht im Zielordner. Bitte neu waehlen.")

        val now = clock.now()
        val date = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
        val name = BackupArchive.fileName(date.toString())
        val files = history.files()

        val meta = JsonObject(
            mapOf(
                "format" to JsonPrimitive(BackupArchive.FORMAT_VERSION),
                "app" to JsonPrimitive("Energie"),
                "createdAt" to JsonPrimitive(now.toString()),
                "device" to JsonPrimitive("${Build.MANUFACTURER} ${Build.MODEL}"),
                "days" to JsonPrimitive(files.size),
            ),
        )
        val plain = JsonObject(settings.plainForBackup(s).mapValues { JsonPrimitive(it.value) })
        val secrets = JsonObject(settings.secretsForBackup(s).mapValues { JsonPrimitive(it.value) })
        val secretsEnc = BackupCrypto.encrypt(json.encodeToString(JsonObject.serializer(), secrets).toByteArray(), s.backupPassword)

        // Erst in eine temporaere Datei schreiben, dann umbenennen - so bleibt bei
        // Abbruch keine halbe Sicherung unter dem endgueltigen Namen liegen.
        val tmpName = "$name.part"
        tree.findFile(tmpName)?.delete()
        val doc = tree.createFile("application/zip", tmpName) ?: error("Datei konnte im Zielordner nicht angelegt werden.")
        try {
            context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
                BackupArchive.write(
                    out,
                    sequenceOf(
                        BackupArchive.Entry(BackupArchive.META) { ByteArrayInputStream(json.encodeToString(JsonObject.serializer(), meta).toByteArray()) },
                        BackupArchive.Entry(BackupArchive.SETTINGS) { ByteArrayInputStream(json.encodeToString(JsonObject.serializer(), plain).toByteArray()) },
                        BackupArchive.Entry(BackupArchive.SECRETS) { ByteArrayInputStream(secretsEnc) },
                    ) + files.asSequence().map { f -> BackupArchive.Entry(BackupArchive.HISTORY_DIR + f.name) { f.inputStream() } },
                )
            } ?: error("Zielordner liess sich nicht zum Schreiben oeffnen.")
        } catch (e: Exception) {
            doc.delete()
            throw e
        }
        // Heutige Sicherung ersetzen (mehrmals am Tag sichern ueberschreibt).
        tree.findFile(name)?.delete()
        // Manche Anbieter haengen beim Umbenennen die Endung an; darum Name ohne ".part" setzen.
        if (!doc.renameTo(name)) {
            // Umbenennen nicht unterstuetzt: Datei unter ".part" belassen waere unschoen -
            // dann als letzte Moeglichkeit noch einmal direkt schreiben.
            val direct = tree.createFile("application/zip", name) ?: error("Umbenennen fehlgeschlagen.")
            context.contentResolver.openInputStream(doc.uri)?.use { i -> context.contentResolver.openOutputStream(direct.uri, "w")?.use { o -> i.copyTo(o) } }
            doc.delete()
        }
        val written = tree.findFile(name) ?: tree.findFile("$name.zip")
        val size = written?.length() ?: 0L

        // Alte Sicherungen aufraeumen.
        val names = tree.listFiles().mapNotNull { it.name }
        for (old in BackupRetention.filesToDelete(names, keep = KEEP)) tree.findFile(old)?.delete()

        settings.noteBackup(now.epochSeconds, "OK: $name, ${files.size} Tage, ${size / 1024} kB")
        Summary(name, files.size, size)
    }

    class RestoreSummary(val days: Int, val settingsRestored: Boolean, val secretsRestored: Boolean)

    /**
     * Liest eine Sicherung ein. Ohne Passwort werden nur Verlauf und Einstellungen
     * uebernommen; mit Passwort auch die Zugangsdaten. Ein falsches Passwort
     * bricht ab, bevor irgendetwas geschrieben wird.
     */
    suspend fun restore(file: Uri, password: String?): RestoreSummary = withContext(Dispatchers.IO) {
        val days = LinkedHashMap<LocalDate, ByteArray>()
        var plain: Map<String, String>? = null
        var secretsEnc: ByteArray? = null
        context.contentResolver.openInputStream(file)?.use { input ->
            BackupArchive.read(input) { name, bytes ->
                when {
                    name == BackupArchive.SETTINGS -> plain = json.parseToJsonElement(String(bytes)).jsonObject
                        .mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }
                    name == BackupArchive.SECRETS -> secretsEnc = bytes
                    else -> BackupArchive.historyDate(name)?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { days[it] = bytes } }
                }
            }
        } ?: error("Datei liess sich nicht oeffnen.")
        if (plain == null && days.isEmpty()) error("Das ist keine Energie-Sicherung.")

        // Zuerst entschluesseln, damit ein falsches Passwort nichts halb ueberschreibt.
        val secrets: Map<String, String>? = if (!password.isNullOrEmpty() && secretsEnc != null) {
            val dec = BackupCrypto.decrypt(secretsEnc!!, password)
            json.parseToJsonElement(String(dec)).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }
        } else null

        for ((d, bytes) in days) history.importDay(d, bytes)
        plain?.let { settings.restore(it, secrets) }
        repository.historyChanged()
        RestoreSummary(days.size, plain != null, secrets != null)
    }

    companion object {
        const val KEEP = 14
    }
}
