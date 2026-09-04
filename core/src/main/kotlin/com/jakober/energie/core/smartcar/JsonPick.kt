package com.jakober.energie.core.smartcar

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Sucht Werte in einem JSON-Baum, dessen genaue Form wir nicht kennen.
 * Smartcars Antworten sind im JSON:API-Stil verschachtelt (`data`,
 * `attributes`, ...); statt die Struktur festzuschreiben, suchen wir die
 * erste passende Kennung in Tiefensuche. Reihenfolge der Kennungen = Vorrang.
 */
object JsonPick {

    fun number(root: JsonElement, vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { k -> find(root, k)?.let { (it as? JsonPrimitive)?.doubleOrNull } }

    fun boolean(root: JsonElement, vararg keys: String): Boolean? =
        keys.firstNotNullOfOrNull { k ->
            find(root, k)?.let { e ->
                val p = e as? JsonPrimitive ?: return@let null
                p.booleanOrNull ?: p.contentOrNull?.lowercase()?.let { s ->
                    when (s) { "true" -> true; "false" -> false; else -> null }
                }
            }
        }

    fun string(root: JsonElement, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> find(root, k)?.let { (it as? JsonPrimitive)?.contentOrNull } }

    /** Alle Objekte im Baum, die eine bestimmte Kennung tragen. */
    fun objectsWith(root: JsonElement, key: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    if (e.containsKey(key)) out += e
                    e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> {}
            }
        }
        walk(root)
        return out
    }

    private fun find(e: JsonElement, key: String): JsonElement? {
        when (e) {
            is JsonObject -> {
                e[key]?.let { if (it !is JsonNull) return it }
                for (v in e.values) find(v, key)?.let { return it }
            }
            is JsonArray -> for (v in e) find(v, key)?.let { return it }
            else -> {}
        }
        return null
    }
}
