#!/usr/bin/env python3
"""Prueft den Stand in Supabase: Messpunkte, Zentrale, Geraete, Auftraege, Hinweise.
Zugangsdaten kommen aus der Umgebung (GitHub-Secrets), nichts davon wird ausgegeben."""
import json, os, sys, urllib.request, urllib.parse, datetime

URL = os.environ["CLOUD_URL"].rstrip("/")
KEY = os.environ["CLOUD_ANON_KEY"]
EMAIL = os.environ["CLOUD_EMAIL"]
PW = os.environ["CLOUD_PASSWORD"]
PUSH_TEST = os.environ.get("PUSH_TEST", "false").lower() == "true"


def call(path, method="GET", body=None, token=None, headers=None):
    h = {"apikey": KEY, "Content-Type": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    if headers:
        h.update(headers)
    req = urllib.request.Request(URL + path, method=method, headers=h, data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode()
            return r.status, (json.loads(text) if text else None), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300], e.headers


status, login, _ = call("/auth/v1/token?grant_type=password", "POST", {"email": EMAIL, "password": PW})
if status != 200:
    print(f"Anmeldung fehlgeschlagen: HTTP {status} {login}")
    sys.exit(1)
tok = login["access_token"]
print("Anmeldung: ok")


def count(table, extra=""):
    st, _, hdr = call(f"/rest/v1/{table}?select=at&limit=1{extra}", token=tok, headers={"Prefer": "count=exact", "Range-Unit": "items"})
    cr = hdr.get("Content-Range", "") if hasattr(hdr, "get") else ""
    return cr.split("/")[-1] if "/" in cr else "?"


now = datetime.datetime.now(datetime.timezone.utc)
today = now.strftime("%Y-%m-%dT00:00:00Z")
print()
print("Messpunkte gesamt:", count("samples"))
print("Messpunkte heute (UTC):", count("samples", f"&at=gte.{today}"))
for order, label in (("at.asc", "aeltester"), ("at.desc", "neuester")):
    st, rows, _ = call(f"/rest/v1/samples?select=at&order={order}&limit=1", token=tok)
    print(f"  {label} Messpunkt:", rows[0]["at"] if rows else "keiner")
st, rows, _ = call("/rest/v1/samples?select=at&order=at.desc&limit=1", token=tok)
if rows:
    latest = datetime.datetime.fromisoformat(rows[0]["at"].replace("Z", "+00:00"))
    age = (now - latest).total_seconds() / 60
    print(f"  Alter des neuesten: {age:.0f} min", "(Zentrale liefert)" if age < 5 else "(ALT: Zentrale misst gerade nicht?)")

# Dichte der letzten 24 h: wie viele Punkte je Stunde
st, rows, _ = call(f"/rest/v1/samples?select=at&at=gte.{(now - datetime.timedelta(hours=24)).strftime('%Y-%m-%dT%H:%M:%SZ')}&order=at.asc&limit=5000", token=tok)
if isinstance(rows, list) and rows:
    per_hour = {}
    for r in rows:
        h = r["at"][:13]
        per_hour[h] = per_hour.get(h, 0) + 1
    print("  Punkte je Stunde, letzte 24 h:", " ".join(f"{k[11:13]}:{v}" for k, v in sorted(per_hour.items())))
    gaps = []
    prev = None
    for r in rows:
        t = datetime.datetime.fromisoformat(r["at"].replace("Z", "+00:00"))
        if prev and (t - prev).total_seconds() > 30 * 60:
            gaps.append(f"{prev.strftime('%H:%M')}-{t.strftime('%H:%M')}")
        prev = t
    print("  Luecken ueber 30 min:", ", ".join(gaps) if gaps else "keine")

print()
st, rows, _ = call("/rest/v1/status?select=hub_seen_at,updated_at,live", token=tok)
if rows:
    seen = datetime.datetime.fromisoformat(rows[0]["hub_seen_at"].replace("Z", "+00:00"))
    live = rows[0].get("live") or {}
    print("Zentrale zuletzt gesehen:", rows[0]["hub_seen_at"], f"({(now - seen).total_seconds() / 60:.0f} min her)")
    print("  Automatik:", live.get("automationStatus"))
    for k in ("senecError", "fritzError", "carError"):
        if live.get(k):
            print(f"  {k}:", live[k])
    car = live.get("car") or {}
    if car:
        print("  Auto:", f"{car.get('socPercent')} %", "laedt" if car.get("isCharging") else "laedt nicht", "steckt" if car.get("isPluggedIn") else "nicht angeschlossen", car.get("lockState"))
else:
    print("Zentrale: noch kein Status in der Cloud (Rolle Zentrale noch nicht aktiv?)")

st, rows, _ = call("/rest/v1/settings?select=updated_at&limit=1", token=tok)
print("Einstellungen in der Cloud:", rows[0]["updated_at"] if rows else "keine")

st, rows, _ = call("/rest/v1/devices?select=token,name,updated_at", token=tok)
print()
print("Push-Geraete:", len(rows) if isinstance(rows, list) else rows)
for d in rows or []:
    print("  ", d.get("name"), d["token"][:12] + "…", d["updated_at"])

st, rows, _ = call("/rest/v1/commands?select=id,kind,created_at,done_at,result&order=created_at.desc&limit=5", token=tok)
print()
print("Letzte Auftraege:")
for c in rows or []:
    print("  ", c["id"], c["kind"], c["created_at"][:19], "->", c["result"] or "OFFEN")
st, rows, _ = call("/rest/v1/alerts?select=id,kind,title,created_at,delivered_at&order=created_at.desc&limit=5", token=tok)
print("Letzte Hinweise:")
for a in rows or []:
    print("  ", a["id"], a["kind"], a["title"], a["created_at"][:19], "zugestellt" if a["delivered_at"] else "OFFEN")

if PUSH_TEST:
    print()
    st, res, _ = call("/rest/v1/alerts", "POST", {"kind": "AUTOMATION_ACTED", "title": "Push-Test", "body": f"Ausgeloest ueber GitHub Actions um {now.strftime('%H:%M')} UTC."}, token=tok, headers={"Prefer": "return=representation"})
    print("Test-Hinweis geschrieben:", "ok" if st in (200, 201) else f"HTTP {st} {res}")
    print("Antwort der Push-Funktion: in Supabase unter Edge Functions -> push -> Logs nachsehen.")
