package com.jakober.energie.core.alerts

import kotlinx.datetime.Instant

/** Seit wann die Cloud ablehnt oder nicht antwortet, und ob das schon gemeldet ist. */
data class CloudWatchState(
    /** Unix-Sekunden des ersten Fehlschlags in Folge, null = zuletzt ging es. */
    val failingSince: Long? = null,
    val reported: Boolean = false,
)

/**
 * Meldet, wenn der Abgleich mit der Cloud laenger scheitert, und wenn er wieder geht.
 *
 * Der Hinweis entsteht auf dem Geraet selbst. Ueber die Cloud kann er nicht kommen: Sie ist
 * es ja, die nicht antwortet. Ein einzelner Fehlschlag meldet nichts, sonst schluege jede
 * Funkluecke an; erst wenn es [limitMinutes] am Stueck nicht ging.
 */
object CloudWatch {

    /** [error] ist die Meldung des letzten Versuchs, null wenn er gelang. */
    fun step(
        now: Instant,
        error: String?,
        state: CloudWatchState,
        limitMinutes: Int,
        hub: Boolean,
    ): Pair<Alert?, CloudWatchState> {
        if (error == null) {
            val back = if (state.reported) {
                Alert(
                    AlertKind.CLOUD_BACK, "Cloud geht wieder",
                    if (hub) "Der Abgleich läuft wieder. Die Messpunkte aus der Zwischenzeit werden nachgeliefert."
                    else "Der Abgleich läuft wieder. Die Anzeige holt die Messpunkte aus der Zwischenzeit nach.",
                )
            } else null
            return back to CloudWatchState()
        }
        val since = state.failingSince ?: now.epochSeconds
        val minutes = (now.epochSeconds - since) / 60
        if (state.reported || minutes < limitMinutes) return null to state.copy(failingSince = since)
        val what = if (hub) "Die Zentrale kann seit ${label(minutes)} nichts hochladen. Sie misst weiter und liefert nach, sobald es wieder geht."
        else "Die Anzeige bekommt seit ${label(minutes)} keine Daten. Was sie zeigt, ist veraltet."
        return Alert(AlertKind.CLOUD_DOWN, "Cloud nicht erreichbar", "$what Meldung: ${error.take(120)}") to
            CloudWatchState(failingSince = since, reported = true)
    }

    private fun label(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }
}
