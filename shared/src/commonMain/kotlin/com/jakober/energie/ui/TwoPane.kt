package com.jakober.energie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Nebeneinander nur, wenn wirklich Platz da ist. Das aufgeklappte Falt-Handy von Samsung im
 * Hochformat ist zwar breit, aber noch hoeher: dort bleibt alles untereinander, sonst werden
 * die Spalten schmal und die Kacheln brechen um.
 */
private val TwoPaneFrom = 640.dp

/**
 * Das Falt-iPhone ist die Ausnahme von dieser Regel. Sein innerer Schirm meldet gar keine
 * Lage - Apple rat ausdruecklich davon ab, dort nach Hoch- und Querformat zu entscheiden -
 * und er ist fast quadratisch, also nie "breiter als hoch". Zu erkennen ist das Geraet an
 * der Spalte, die es sich am rechten Rand nimmt; gibt es die, zaehlt allein die Breite.
 */
private val SideColumnFrom = 40.dp

/**
 * Eine Liste, die sich im Querformat in zwei Spalten teilt: links das Bewegte (Energiefluss,
 * Verlauf), rechts die Zahlen. Beide Spalten rollen eigenstaendig, damit das Diagramm stehen
 * bleibt, waehrend man rechts weiterliest.
 *
 * [detail] ist die Karte, die zu einem angetippten Knoten gehoert. Untereinander steht sie
 * direkt unter dem Diagramm, nebeneinander oben in der rechten Spalte, also neben dem
 * Diagramm statt darunter. Wechselt [detailKey], rollt die rechte Spalte dafuer nach oben.
 */
@Composable
fun TwoPane(
    contentPadding: PaddingValues,
    left: LazyListScope.() -> Unit,
    right: LazyListScope.() -> Unit,
    detail: LazyListScope.() -> Unit = {},
    detailKey: Any? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = contentPadding.calculateTopPadding() + 8.dp
        val bottom = contentPadding.calculateBottomPadding() + 24.dp
        val sideColumn = WindowInsets.safeDrawing.asPaddingValues()
            .calculateEndPadding(LocalLayoutDirection.current)
        val sideBySide = maxWidth >= TwoPaneFrom &&
            (maxWidth > maxHeight || sideColumn >= SideColumnFrom)
        if (!sideBySide) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                left()
                detail()
                right()
            }
        } else {
            val rightState = rememberLazyListState()
            LaunchedEffect(detailKey) { if (detailKey != null) rightState.animateScrollToItem(0) }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, top = top, bottom = bottom),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { left() }
                LazyColumn(
                    Modifier.weight(1f),
                    state = rightState,
                    contentPadding = PaddingValues(end = 16.dp, top = top, bottom = bottom),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    detail()
                    right()
                }
            }
        }
    }
}
