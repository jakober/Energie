-- Energie: Schema fuer den Betrieb mit Zentrale (misst zu Hause) und Anzeige (unterwegs).
-- Einmal im Supabase SQL-Editor ausfuehren. Laesst sich gefahrlos wiederholen.

-- Messpunkte: eine Zeile je Minute, Inhalt als JSON so, wie die App ihn lokal ablegt.
create table if not exists public.samples (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  at timestamptz not null,
  data jsonb not null,
  primary key (user_id, at)
);
create index if not exists samples_user_at_desc on public.samples (user_id, at desc);

-- Einstellungen ohne Geheimnisse (Preise, Regeln, Steckernamen), fuer beide Handys gleich.
create table if not exists public.settings (
  user_id uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  plain jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

-- Momentaufnahme der Zentrale: Autozustand, Automatik-Status, Fehler je Quelle.
create table if not exists public.status (
  user_id uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  live jsonb not null default '{}'::jsonb,
  hub_seen_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Auftraege der Anzeige an die Zentrale (Laden pausieren, Auto abschliessen ...).
create table if not exists public.commands (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  kind text not null,
  payload jsonb not null default '{}'::jsonb,
  done_at timestamptz,
  result text
);
create index if not exists commands_open on public.commands (user_id, created_at) where done_at is null;

-- Hinweise der Zentrale, die die Anzeige als Benachrichtigung zeigt.
create table if not exists public.alerts (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  kind text not null,
  title text not null,
  body text not null,
  offer_charge boolean not null default false,
  delivered_at timestamptz
);
create index if not exists alerts_open on public.alerts (user_id, created_at) where delivered_at is null;

-- Zugriff: jedes Konto sieht und schreibt nur seine eigenen Zeilen.
alter table public.samples  enable row level security;
alter table public.settings enable row level security;
alter table public.status   enable row level security;
alter table public.commands enable row level security;
alter table public.alerts   enable row level security;

drop policy if exists "eigene Zeilen" on public.samples;
create policy "eigene Zeilen" on public.samples  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "eigene Zeilen" on public.settings;
create policy "eigene Zeilen" on public.settings for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "eigene Zeilen" on public.status;
create policy "eigene Zeilen" on public.status   for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "eigene Zeilen" on public.commands;
create policy "eigene Zeilen" on public.commands for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "eigene Zeilen" on public.alerts;
create policy "eigene Zeilen" on public.alerts   for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Ausduennen: aelter als 90 Tage bleiben nur volle Viertelstunden. Braucht die Erweiterung pg_cron
-- (Database -> Extensions -> pg_cron einschalten). Ohne pg_cron diesen Block einfach weglassen.
do $$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.unschedule(jobid) from cron.job where jobname = 'energie-ausduennen';
    perform cron.schedule(
      'energie-ausduennen', '30 3 * * *',
      $job$ delete from public.samples where at < now() - interval '90 days' and (extract(minute from at)::int % 15) <> 0 $job$
    );
  end if;
end $$;
