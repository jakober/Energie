-- Firebase-Push: Geraete-Tokens je Nutzer. Die Anzeige traegt ihr Token ein,
-- die Edge Function "push" liest sie mit dem service_role-Schluessel.
create table if not exists public.devices (
  token text primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text,
  updated_at timestamptz not null default now()
);
create index if not exists devices_user on public.devices (user_id);
alter table public.devices enable row level security;
drop policy if exists "eigene Zeilen" on public.devices;
create policy "eigene Zeilen" on public.devices for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
