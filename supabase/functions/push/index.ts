// Supabase Edge Function "push": wird per Database-Webhook bei jedem neuen
// Eintrag in public.alerts aufgerufen und schickt ihn als Firebase-Push an
// alle registrierten Geraete des Nutzers (Tabelle public.devices).
//
// Secrets (Supabase -> Edge Functions -> Secrets):
//   FCM_SERVICE_ACCOUNT  = Inhalt der Firebase-Dienstkonto-JSON (eine Zeile)
//   WEBHOOK_SECRET       = frei gewaehlt; der Webhook schickt ihn im Header x-webhook-secret
// SUPABASE_URL und SUPABASE_SERVICE_ROLE_KEY stellt Supabase automatisch bereit.

import { createClient } from "npm:@supabase/supabase-js@2";
import { JWT } from "npm:google-auth-library@9";

type AlertRow = { id: number; user_id: string; kind: string; title: string; body: string; offer_charge: boolean };

Deno.serve(async (req) => {
  if (req.headers.get("x-webhook-secret") !== Deno.env.get("WEBHOOK_SECRET")) {
    return new Response("verboten", { status: 401 });
  }
  const payload = await req.json();
  const row: AlertRow | undefined = payload?.record;
  if (!row) return new Response("kein Datensatz", { status: 400 });

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data: devices, error } = await admin.from("devices").select("token").eq("user_id", row.user_id);
  if (error) return new Response(`devices: ${error.message}`, { status: 500 });
  if (!devices || devices.length === 0) return new Response("keine Geraete", { status: 200 });

  const sa = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT")!);
  const jwt = new JWT({ email: sa.client_email, key: sa.private_key, scopes: ["https://www.googleapis.com/auth/firebase.messaging"] });
  const { access_token } = await jwt.authorize();

  const results: string[] = [];
  for (const d of devices) {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${access_token}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: {
          token: d.token,
          // Nur Daten, keine "notification": die App baut die Benachrichtigung selbst
          // (Kanal, Knopf "Jetzt laden") und laeuft dafuer auch im Hintergrund an.
          data: { id: String(row.id), kind: row.kind, title: row.title, body: row.body, offer_charge: String(row.offer_charge) },
          android: { priority: "high" },
        },
      }),
    });
    results.push(`${res.status}`);
    // Abgemeldete oder geloeschte Geraete aufraeumen.
    if (res.status === 404 || res.status === 410) await admin.from("devices").delete().eq("token", d.token);
  }
  return new Response(`gesendet: ${results.join(",")}`, { status: 200 });
});
