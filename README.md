# Energie

Android-App für den eigenen Stromhaushalt: Was liefert die PV-Anlage, was
steckt im SENEC-Speicher, was geht durch den Stromzähler. Die Werte kommen
aus zwei Quellen und landen lokal in einem Verlauf, aus dem die App
Tages-, Wochen- und Monatsstatistiken rechnet.

## Quellen

| Quelle | Weg | Liefert |
| --- | --- | --- |
| SENEC.Home 4 | Offizielle **SENEC.Connect**-API (developer.senec.com), Abonnementschlüssel im Header `Ocp-Apim-Subscription-Key` | Ladezustand und Leistung des Speichers, PV-Erzeugung, Hausverbrauch, Netzleistung, Wallbox |
| Stromzähler | **FRITZ!Smart Energy 250** am Zähler, ausgelesen über die AHA-HTTP-Schnittstelle der FRITZ!Box (nur im Heimnetz) | Momentanleistung am Netzanschluss, Zählerstände Bezug (1.8.0) und Einspeisung (2.8.0) |

SENEC.Connect liefert nur Momentaufnahmen, keine Historie. Deshalb fragt die
App regelmäßig ab (im Vordergrund einstellbar, im Hintergrund alle 15 Minuten
per WorkManager) und speichert jeden Messpunkt als JSON-Zeile in einer Datei
pro Tag unter `files/verlauf/`. Alles Weitere rechnet sie daraus.

## Was die App zeigt

- **Übersicht:** Energiefluss-Diagramm (PV, Haus, Netz, Speicher) mit
  Bewegung in Flussrichtung, Ladezustand als Ring, Netzbezug oder
  Einspeisung, Tagesbilanz mit Autarkie und Eigenverbrauch, Verbrauchsspitze
  mit Uhrzeit, Grundlast, Zählerstände, Kosten.
- **Statistik:** Tag, Woche oder Monat. Bilanz, Stundenprofil, alle Spitzen
  mit Uhrzeit (Verbrauch, Erzeugung, Bezug, Einspeisung, Laden, Entladen),
  Ladezustandsverlauf, Zählerstände zu Beginn und Ende, bester PV-Tag,
  verbrauchsstärkster Tag, Stromkosten und Einspeisevergütung.
- **Einstellungen:** Zugangsdaten mit Verbindungstest, Abfrageabstand,
  Strompreise, Aufbewahrung des Verlaufs, Rohdaten-Ansicht.

## Aufbau

```
core/   reiner Kotlin/JVM-Code, ohne Android: FRITZ!Box-Client (Login mit PBKDF2,
        Geräteliste, Statistik), SENEC.Connect-Client, Verlaufsspeicher,
        Tages-/Zeitraumstatistik - und die Tests dazu
app/    Android-App: Jetpack Compose, Material 3, WorkManager, DataStore
```

Der Kern lässt sich ohne Android-SDK bauen und testen:

```
./gradlew -PcoreOnly :core:test
```

Die App selbst braucht das Android-SDK (Android Studio) oder läuft über die
GitHub-Action, die bei jedem Push das Debug-APK baut und als Artefakt ablegt.

## Einrichtung

1. Auf https://developer.senec.com/ mit dem SENEC-Konto anmelden, unter
   *Benutzerprofil → Abonnements* den Primär- oder Sekundärschlüssel kopieren
   und in der App unter *SENEC.Connect* eintragen.
2. In der FRITZ!Box einen Benutzer mit dem Recht *Smart Home* anlegen (oder
   den vorhandenen nehmen). Der FRITZ!Smart Energy 250 muss in der Box
   eingerichtet sein und die INFO-Schnittstelle des Zählers freigeschaltet
   (PIN vom Netzbetreiber). Adresse, Benutzer und Passwort in der App unter
   *FRITZ!Box* eintragen.
3. Beide Quellen mit *prüfen* testen, dann *Speichern*.

Die Zugangsdaten bleiben im privaten App-Speicher des Geräts und werden
nirgendwo hochgeladen.

## Ausblick

- **Ford:** Das Auto soll nur laden, wenn der Speicher voll genug ist. Ford
  hat den Zugang zur FordPass-Schnittstelle für Einzelentwickler im Sommer
  2026 eingeschränkt; offen ist, ob FordConnect (developer.ford.com) oder ein
  Vermittler wie Smartcar der Weg wird. Die Wallbox-Werte aus SENEC.Connect
  sind schon im Modell, Steuerung liefert die API bisher nicht.
- **Verlauf der FRITZ!Box:** Die Box speichert selbst Tages- und
  Monatswerte je Zähler (`getbasicdevicestats`). Der Client kann sie schon
  lesen; sie könnten Tage füllen, an denen die App nicht lief.
