-- KevBox TV — member_addon setup. Run ONCE against the KevBox Supabase project
-- (scmqdptagksltnwiveyh). Idempotent — safe to re-run. No secrets in this file.
-- Per-member debrid (Torrentio/AIOStreams) is NOT here — see MEMBER-DEBRID-ONBOARDING.md.

-- 1. Table -----------------------------------------------------------------------
create table if not exists public.member_addon (
  id          bigint generated always as identity primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
  url         text not null,
  enabled     boolean not null default true,
  sort_order  int not null default 0,
  updated_at  timestamptz not null default now(),
  unique (user_id, url)
);
create index if not exists member_addon_user_order_idx
  on public.member_addon (user_id, sort_order, id);
alter table public.member_addon enable row level security;

-- 2. RLS: a member may READ only their own rows. No write policy → dashboard/service_role only.
drop policy if exists "member reads own addons" on public.member_addon;
create policy "member reads own addons"
  on public.member_addon for select
  using (auth.uid() = user_id);

-- 3. Dashboard view: filter by email without a stale denormalized column.
create or replace view public.member_addon_v as
  select m.*, u.email as auth_email
  from public.member_addon m
  join auth.users u on u.id = m.user_id;

-- 4. Universal defaults — single source of truth (mirror of the Kotlin DEFAULT_ADDON_URLS).
create or replace function public.default_member_addons()
  returns table (url text, sort_order int)
  language sql immutable as $$
  values
    ('https://v3-cinemeta.strem.io', 0),
    ('https://opensubtitlesv3-pro.dexter21767.com/eyJsYW5ncyI6WyJlbmdsaXNoIiwiZnJlbmNoIl0sInNvdXJjZSI6ImFsbCIsImFpVHJhbnNsYXRlZCI6dHJ1ZSwiYXV0b0FkanVzdG1lbnQiOnRydWV9/manifest.json', 1),
    ('https://opensubtitles-v3.strem.io', 2),
    ('https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/bmZ4LGRucCxhbXAsYXRwLGhibSxwY3AsaGx1LHBtcCxuZmssY3RzLG1nbCxjcnUsaGF5LGNsdixnb3AsamhzLHNzdCx2aWwsbmx6LHplZSxjcGQsc3R6LGRwZSxtYmksc29ueWxpdixzZ28sdmlrLHNoZCxiYm8sYWN0LG1wOSxpdHYsaXFpLGNyYyxhbDQsc2hhLGJiYzo6OjE3ODA5MjA3NDkwOTc6MDowOkxC/manifest.json', 3)
$$;

-- 5. Auto-seed new members with the universal defaults (opt-out). SECURITY DEFINER pins search_path.
create or replace function public.seed_member_addons() returns trigger
  language plpgsql security definer set search_path = '' as $$
begin
  insert into public.member_addon (user_id, url, enabled, sort_order)
  select new.id, url, true, sort_order from public.default_member_addons()
  on conflict (user_id, url) do nothing;
  return new;
end $$;
drop trigger if exists on_member_addon_seed on auth.users;
create trigger on_member_addon_seed after insert on auth.users
  for each row execute function public.seed_member_addons();

-- 6. One-time back-fill: seed the universal defaults for EXISTING members.
insert into public.member_addon (user_id, url, enabled, sort_order)
select u.id, d.url, true, d.sort_order
from auth.users u cross join public.default_member_addons() d
on conflict (user_id, url) do nothing;

-- Sanity checks
select count(*) as member_addon_rows from public.member_addon;
select auth_email, url, sort_order, enabled from public.member_addon_v order by auth_email, sort_order;
