// Oeffentliche Zugangsdaten zur Supabase-Datenbank. Der anon-Schluessel ist kein Geheimnis:
// ohne Anmeldung mit E-Mail und Passwort gibt die Datenbank nichts heraus (Row Level Security).
window.ENERGIE_CONFIG = {
  supabaseUrl: "https://mnqcosmyewntcdsfujdm.supabase.co",
  supabaseAnonKey: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1ucWNvc215ZXdudGNkc2Z1amRtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODg3MTA1MzksImV4cCI6MjEwNDI4NjUzOX0.rdMBBfPdenCYtgH-8bJM_LJ3YA9lQb-vA87KsaFYnhQ",
  // Abgleich in Sekunden, solange die Seite sichtbar ist.
  refreshSeconds: 60,
  // Nach so vielen Minuten ohne Lebenszeichen gilt die Zentrale als stumm.
  hubSilentMinutes: 30,
};
