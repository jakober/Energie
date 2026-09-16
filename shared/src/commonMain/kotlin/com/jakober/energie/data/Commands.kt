package com.jakober.energie.data

enum class FordCommand(val label: String) {
    PAUSE("Laden pausieren"),
    RESUME("Laden fortsetzen"),
    CANCEL("Laden abbrechen"),
    TARGET_50("Ladeziel 50 %"),
    TARGET_100("Ladeziel 100 %"),
    LOCK("Abschließen"),
    UNLOCK("Aufschließen"),
    STATUS_REFRESH("Auto wecken"),
}

enum class CarCommand(val label: String) {
    LIMIT_50("Ladeziel 50 %"),
    LIMIT_100("Ladeziel 100 %"),
    STOP("Laden stoppen"),
    START("Laden starten"),
}
