// Supabase Edge Function "push": wird per Database-Webhook bei jedem neuen
// Eintrag in public.alerts aufgerufen und schickt ihn an alle registrierten
// Geraete des Nutzers (Tabelle public.devices).
//
// Android laeuft ueber Firebase, iPhone und iPad direkt ueber Apple (APNs).
// Die Spalte "platform" der Zeile entscheidet, "android" ist der Standard.
//
// Secrets (Supabase -> Edge Functions -> Secrets):
//   FCM_SERVICE_ACCOUNT  = Inhalt der Firebase-Dienstkonto-JSON (eine Zeile)
//   WEBHOOK_SECRET       = frei gewaehlt; der Webhook schickt ihn im Header x-webhook-secret
//   APNS_KEY             = Inhalt der .p8-Datei von Apple (mit BEGIN/END-Zeilen)
//   APNS_KEY_ID          = Kennung des Schluessels, zehn Zeichen
//   APNS_TEAM_ID         = Team-Kennung des Entwicklerkontos
//   APNS_TOPIC           = Bundle-Kennung der App, hier de.jakober.energie
// SUPABASE_URL und SUPABASE_SERVICE_ROLE_KEY stellt Supabase automatisch bereit.
// Fehlt ein APNS-Secret, werden iPhone-Geraete uebersprungen; Android laeuft weiter.

import { createClient } from "npm:@supabase/supabase-js@2";
import { JWT } from "npm:google-auth-library@9";

type AlertRow = { id: number; user_id: string; kind: string; title: string; body: string; offer_charge: boolean };
type Device = { token: string; platform: string | null };

// Apple erlaubt hoechstens alle 20 Minuten ein neues Token und weist haeufigere ab.
let apnsJwt: { value: string; at: number } | null = null;

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Den privaten Schluessel aus der .p8-Datei lesen (PKCS#8, Base64 zwischen den Randzeilen). */
async function apnsKey(pem: string): Promise<CryptoKey> {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const raw = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return await crypto.subtle.importKey("pkcs8", raw, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
}

async function apnsToken(): Promise<string | null> {
  const pem = Deno.env.get("APNS_KEY");
  const keyId = Deno.env.get("APNS_KEY_ID");
  const teamId = Deno.env.get("APNS_TEAM_ID");
  if (!pem || !keyId || !teamId) return null;
  const now = Math.floor(Date.now() / 1000);
  if (apnsJwt && now - apnsJwt.at < 40 * 60) return apnsJwt.value;
  const header = base64Url(new TextEncoder().encode(JSON.stringify({ alg: "ES256", kid: keyId })));
  const claims = base64Url(new TextEncoder().encode(JSON.stringify({ iss: teamId, iat: now })));
  const signed = `${header}.${claims}`;
  const sig = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    await apnsKey(pem),
    new TextEncoder().encode(signed),
  );
  const value = `${signed}.${base64Url(new Uint8Array(sig))}`;
  apnsJwt = { value, at: now };
  return value;
}

Deno.serve(async (req) => {
  if (req.headers.get("x-webhook-secret") !== Deno.env.get("WEBHOOK_SECRET")) {
    return new Response("verboten", { status: 401 });
  }
  const payload = await req.json();
  const row: AlertRow | undefined = payload?.record;
  if (!row) return new Response("kein Datensatz", { status: 400 });

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data: devices, error } = await admin.from("devices").select("token, platform").eq("user_id", row.user_id);
  if (error) return new Response(`devices: ${error.message}`, { status: 500 });
  if (!devices || devices.length === 0) return new Response("keine Geraete", { status: 200 });

  const android = (devices as Device[]).filter((d) => (d.platform ?? "android") !== "ios");
  const apple = (devices as Device[]).filter((d) => d.platform === "ios");
  const results: string[] = [];

  if (android.length > 0) {
    const sa = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT")!);
    const jwt = new JWT({ email: sa.client_email, key: sa.private_key, scopes: ["https://www.googleapis.com/auth/firebase.messaging"] });
    const { access_token } = await jwt.authorize();
    for (const d of android) {
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
      results.push(`android ${res.status}`);
      // Abgemeldete oder geloeschte Geraete aufraeumen.
      if (res.status === 404 || res.status === 410) await admin.from("devices").delete().eq("token", d.token);
    }
  }

  if (apple.length > 0) {
    const jwt = await apnsToken();
    const topic = Deno.env.get("APNS_TOPIC");
    if (!jwt || !topic) {
      results.push("apple uebersprungen: Schluessel fehlt");
    } else {
      for (const d of apple) {
        // Anders als bei Android baut iOS die Meldung selbst, deshalb "alert".
        const res = await fetch(`https://api.push.apple.com/3/device/${d.token}`, {
          method: "POST",
          headers: {
            authorization: `bearer ${jwt}`,
            "apns-topic": topic,
            "apns-push-type": "alert",
            "apns-priority": "10",
            "content-type": "application/json",
          },
          body: JSON.stringify({
            aps: { alert: { title: row.title, body: row.body }, sound: "default" },
            id: String(row.id),
            kind: row.kind,
            offer_charge: String(row.offer_charge),
          }),
        });
        results.push(`apple ${res.status}`);
        // 410 heisst: Das Geraet nimmt nichts mehr an, Token austragen.
        if (res.status === 410) await admin.from("devices").delete().eq("token", d.token);
        else if (res.status >= 400) results.push(await res.text());
      }
    }
  }

  return new Response(`gesendet: ${results.join(", ")}`, { status: 200 });
});
