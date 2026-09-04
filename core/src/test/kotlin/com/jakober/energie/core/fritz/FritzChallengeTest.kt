package com.jakober.energie.core.fritz

import kotlin.test.Test
import kotlin.test.assertEquals

class FritzChallengeTest {
    // Beispiel aus AVMs Dokument "FRITZ!OS Login-Verfahren" (PBKDF2).
    @Test
    fun pbkdf2Beispiel() {
        assertEquals(
            "5A1722$1798a1672bca7c6463d6b245f82b53703b0f50813401b03e4045a5861e689adb",
            FritzChallenge.response("2$10000$5A1711$2000$5A1722", "1example!"),
        )
    }

    // Beispiel aus demselben Dokument fuer das alte MD5-Verfahren.
    @Test
    fun md5Beispiel() {
        assertEquals(
            "1234567z-9e224a41eeefa284df7bb0f26c2913e2",
            FritzChallenge.response("1234567z", "äbc"),
        )
    }
}
