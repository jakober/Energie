package com.jakober.energie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Ab dieser Breite steht die Anzeige nebeneinander: aufgeklapptes Falt-Handy, Tablet und
 * jedes Querformat. Darunter, also auf dem normalen Handy im Hochformat, bleibt alles
 * untereinander.
 */
private val TwoPaneFrom = 640.dp

/**
 * Eine Liste, die sich ab genug Breite in zwei Spalten teilt: links das Bewegte
 * (Energiefluss, Verlauf), rechts die Karten mit den Zahlen. Beide Spalten rollen
 * eigenstaendig, damit das Diagramm stehen bleibt, waehrend man rechts weiterliest.
 */
@Composable
fun TwoPane(
    contentPadding: PaddingValues,
    left: LazyListScope.() -> Unit,
    right: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = contentPadding.calculateTopPadding() + 8.dp
        val bottom = contentPadding.calculateBottomPadding() + 24.dp
        if (maxWidth < TwoPaneFrom) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                left()
                right()
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, top = top, bottom = bottom),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { left() }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(end = 16.dp, top = top, bottom = bottom),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { right() }
            }
        }
    }
}
