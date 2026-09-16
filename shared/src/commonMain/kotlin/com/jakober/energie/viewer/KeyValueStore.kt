package com.jakober.energie.viewer

/** Kleiner Schluessel-Wert-Speicher der Plattform (NSUserDefaults, SharedPreferences, Datei). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Nur im Speicher, fuer Tests und den Desktop-Uebersetzungslauf. */
class MemoryKeyValueStore : KeyValueStore {
    private val map = HashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/** Oeffentliche Zugangsdaten der Datenbank; Zugriff regeln die Policies, nicht der Schluessel. */
object CloudDefaults {
    const val URL = "https://mnqcosmyewntcdsfujdm.supabase.co"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1ucWNvc215ZXdudGNkc2Z1amRtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODg3MTA1MzksImV4cCI6MjEwNDI4NjUzOX0.rdMBBfPdenCYtgH-8bJM_LJ3YA9lQb-vA87KsaFYnhQ"
}
