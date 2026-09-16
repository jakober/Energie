package com.jakober.energie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Nebeneinander nur im Querformat und nur, wenn wirklich Platz da ist. Das aufgeklappte
 * Falt-Handy im Hochformat ist zwar breit, aber noch hoeher: dort bleibt alles untereinander,
 * sonst werden die Spalten schmal und die Kacheln brechen um.
 */
private val TwoPaneFrom = 640.dp

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
        val sideBySide = maxWidth >= TwoPaneFrom && maxWidth > maxHeight
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
