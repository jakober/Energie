# Energie Web-Anzeige

Reine Anzeige für iPhone, Android und PC. Liest, was die Zentrale nach Supabase
schreibt, und schickt zwei Dinge als Auftrag zurück: den Handschalter
„Jetzt voll laden" und Änderungen an den Laderegeln. Alles andere ist lesend.

Kein Build-Schritt, keine Abhängigkeiten: `index.html`, `style.css`, `app.js`,
`config.js`, `manifest.webmanifest`, zwei Icons.

## Bereitstellen

1. Den ganzen Ordner auf den Webserver kopieren, am besten in ein eigenes
   Verzeichnis, z. B. `https://example.org/energie/`.
2. HTTPS ist Pflicht: Safari legt Web-Apps sonst nicht als eigenständige App
   auf den Home-Bildschirm, und die Anmeldung soll nicht im Klartext laufen.
3. Auf dem iPhone die Adresse in Safari öffnen, Teilen → „Zum Home-Bildschirm".
   Die Seite startet dann ohne Browserleiste mit dem gelben Blitz als Icon.
4. Anmelden mit E-Mail und Passwort des Supabase-Kontos (dasselbe wie in der
   Zentrale). Die Anmeldung bleibt im Browser gespeichert.

`config.js` enthält die Adresse der Datenbank und den öffentlichen anon-Schlüssel.
Der ist kein Geheimnis: ohne Anmeldung gibt die Datenbank nichts heraus
(Row Level Security). Passwörter stehen nirgends in den Dateien.

## Was die Seite zeigt

- Energiefluss aus dem letzten Messpunkt (PV, Netz, Haus, Speicher, Auto).
- Auto-Karte mit Akku, Ladezustand, Alter des Ford-Ladestatus, Verriegelung,
  Standort; Knopf „Jetzt voll laden" bzw. „Voll laden beenden".
- Heute-Bilanz und Hinweise der Zentrale.
- Statistik je Tag: Stundenbalken (PV, Verbrauch, Auto) mit Speicherstand,
  Bilanz mit Zählerwerten und Kosten, Verbraucher an Messsteckern.
- Automatik: Status, Regeln zum Ändern, Aufträge mit Rückmeldung, Protokoll.

Die Zentrale arbeitet Aufträge einmal pro Minute ab. Nach einem Auftrag hält die
Seite ihr Formular drei Minuten fest und gleicht dann wieder mit der Zentrale ab.
