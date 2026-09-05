package com.jakober.energie.core.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlacesTest {
    private val work = NamedPlace("Arbeit", 48.4000, 10.1000)
    private val gym = NamedPlace("Sport", 48.4010, 10.1000) // ~111 m noerdlich

    @Test
    fun `naechster Ort im Umkreis gewinnt`() {
        assertEquals(work, Places.match(listOf(work, gym), 48.4001, 10.1000))
        assertEquals(gym, Places.match(listOf(work, gym), 48.4012, 10.1000))
    }

    @Test
    fun `ausserhalb von 200 m kein Treffer`() {
        assertNull(Places.match(listOf(work), 48.4030, 10.1000)) // ~333 m
        assertNull(Places.match(emptyList(), 48.4, 10.1))
    }

    @Test
    fun `upsert ersetzt gleichen Namen und trimmt`() {
        val list = Places.upsert(listOf(work), NamedPlace(" arbeit ", 48.5, 10.2))
        assertEquals(1, list.size)
        assertEquals(NamedPlace("arbeit", 48.5, 10.2), list.single())
        assertEquals(emptyList(), Places.remove(list, list.single()))
    }
}
