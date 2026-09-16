package com.jakober.energie.core.backup

/** Welche alten Sicherungen im Zielordner geloescht werden duerfen. */
object BackupRetention {
    private val NAME = Regex("""energie-backup-(\d{4}-\d{2}-\d{2})\.zip""")

    /** Datum aus dem Dateinamen, sonst null (fremde Dateien werden nie angefasst). */
    fun dateOf(fileName: String): String? = NAME.matchEntire(fileName)?.groupValues?.get(1)

    /** Alles ausser den `keep` neuesten eigenen Sicherungen. */
    fun filesToDelete(names: List<String>, keep: Int = 14): List<String> {
        val own = names.mapNotNull { n -> dateOf(n)?.let { it to n } }.sortedByDescending { it.first }
        return own.drop(keep.coerceAtLeast(1)).map { it.second }
    }
}
