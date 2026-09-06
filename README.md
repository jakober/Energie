# Energie

Android-App für den eigenen Stromhaushalt: Was liefert die PV-Anlage, was
steckt im SENEC-Speicher, was geht durch den Stromzähler. Die Werte kommen
aus zwei Quellen und landen lokal in einem Verlauf, aus dem die App
Tages-, Wochen- und Monatsstatistiken rechnet.

## Quellen

| Quelle | Weg | Liefert |
| --- | --- | --- |
| SENEC.Home 4 | Offizielle **SENEC.Connect**-API (developer.senec.com), Abonnementschlüssel im Header `Ocp-Apim-Subscription-Key` | Ladezustand und Leistung des Speichers, PV-Erzeugung, Hausverbrauch, Netzleistung, Wallbox |
| Stromzähler | **FRITZ!Smart Energy 250** am Zähler, ausgelesen über die AHA-HTTP-Schnittstelle der FRITZ!Box (im Heimnetz oder von unterwegs über MyFRITZ!) | Momentanleistung am Netzanschluss, Zählerstände Bezug (1.8.0) und Einspeisung (2.8.0) |
| Auto | **FordPass** (inoffizielle App-Schnittstelle, Zweitkonto empfohlen) | Ladestand, Reichweite, Stecker- und Ladestatus, Ladeleistung, Standort, Verriegelung; Laden pausieren und fortsetzen |

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
  Dazu Ladevorgänge des Autos (Dauer, kWh, Sonnenanteil, Netzkosten),
  Ersparnis und Amortisation gegen die Anlagenkosten, Hochrechnung des
  laufenden Monats.
- **Ladeautomatik:** Das Auto lädt nur, wenn der Hausspeicher voll genug ist
  oder PV-Überschuss da ist (Schwellen mit Hysterese, Reserve, optionale
  Nachtsperre, Handschalter "jetzt voll laden"). Befehle gehen über FordPass.
- **Benachrichtigungen:** Auto steht zu Hause und ist nicht abgeschlossen;
  Speicher voll und Einspeisung hoch, aber das Auto lädt nicht (mit Knopf
  "Jetzt laden"); Ladestart und Ladeende des Autos mit Akkustand; Rückmeldung
  der Automatik; Quelle ausgefallen; Sicherung fehlgeschlagen.
- **Zentrale und Anzeige:** Ein Zweithandy zu Hause misst als Zentrale jede
  Minute (Vordergrund-Dienst) und schreibt Messpunkte, Autozustand und
  Hinweise nach Supabase; das Handy unterwegs holt alles von dort und schickt
  Befehle als Aufträge zurück. Schema unter `supabase/schema.sql`.
- **Steckdosen:** Shelly- und Tasmota-Messstecker im Heimnetz (lokal, ohne
  Cloud), je Stecker ein Verbraucher. Statistik zeigt, wer wie viel
  verbraucht, mit Anteil am Haus, Kosten und nicht gemessenem Rest.
- **Fahrten:** Kilometer je Tag aus dem Kilometerstand, Verbrauch aus dem
  Akkuinhalt, Herkunft des Fahrstroms (Sonne, Netz, unterwegs) über ein
  Tank-Modell des Akkus, Kosten je 100 km.
- **PV-Prognose:** Wetter von Open-Meteo (ohne Schlüssel) für den Standort
  Zuhause, Einstrahlung auf beide Dachseiten, daraus der Tagesertrag für
  sieben Tage als Wochenstreifen unter dem Flussdiagramm (Symbol,
  Höchsttemperatur, kWh). Die App lernt aus den echten Erträgen nach.
- **Homescreen-Widget:** Speicher, PV, Haus, Netz und Auto in 2x2 oder 4x2.
- **Sicherung:** Täglich nachts im WLAN eine ZIP-Datei mit Verlauf und
  Einstellungen in einen frei gewählten Ordner (auch Google Drive);
  Zugangsdaten darin mit Passwort verschlüsselt (AES-256-GCM, PBKDF2).
  Wiederherstellen aus der App.
- **Einstellungen:** Zugangsdaten mit Verbindungstest, Abfrageabstand,
  Strompreise und Anlagenkosten, Aufbewahrung des Verlaufs, Rohdaten-Ansicht.

## Aufbau

```
core/   reiner Kotlin/JVM-Code, ohne Android: FRITZ!Box-Client (Login mit PBKDF2,
        Geräteliste, Statistik), SENEC.Connect-Client, FordPass-Client,
        Verlaufsspeicher, Tages-/Zeitraumstatistik, Ladevorgänge, Ersparnis,
        Laderegeln, Hinweis-Engine, Backup-Verschlüsselung - und die Tests dazu
app/    Android-App: Jetpack Compose, Material 3, Glance-Widget, WorkManager,
        DataStore, Benachrichtigungen
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

Die Zugangsdaten bleiben im privaten App-Speicher des Geräts. Die App selbst
schickt sie nirgendwohin; Androids Auto Backup sichert den App-Speicher
(Einstellungen samt Zugangsdaten, Verlauf bis 25 MB) Ende-zu-Ende
verschlüsselt ins Google-Konto und stellt ihn bei einer Neuinstallation
wieder her. Für längere Verläufe und Geräte ohne Google-Konto gibt es die
ZIP-Sicherung in einen Ordner deiner Wahl (siehe oben).

## Ausblick

- **Prognosebasiertes Laden:** PV-Vorhersage (Open-Meteo) als Eingang für die
  Laderegel, Abfahrtszeit und Ziel-Ladestand.
- **FRITZ!DECT-Steckdosen** als Überschuss-Verbraucher schalten.
- **Verlauf der FRITZ!Box:** Die Box speichert selbst Tages- und
  Monatswerte je Zähler (`getbasicdevicestats`). Der Client kann sie schon
  lesen; sie könnten Tage füllen, an denen die App nicht lief.
