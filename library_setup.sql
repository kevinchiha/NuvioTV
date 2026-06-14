-- KevBox TV — library cloud restore (SNAPSHOT; no event log). Run ONCE against the KevBox Supabase
-- project (scmqdptagksltnwiveyh) AFTER get_sync_owner_setup.sql. Idempotent. No secrets. Spec §5.3.

-- 1. Table -----------------------------------------------------------------------
create table if not exists public.library (
  user_id        uuid   not null references auth.users(id) on delete cascade,
  profile_id     int    not null default 1,
  content_id     text   not null,
  content_type   text   not null,
  name           text   not null default '',
  poster         text,
  poster_shape   text   not null default 'POSTER',
  background     text,
  description    text,
  release_info   text,
  imdb_rating    real,                                -- nullable float4 (client decodes Float?)
  genres         text[] not null default '{}',
  addon_base_url text,
  added_at       bigint not null default 0,
  updated_at     timestamptz not null default now(),
  primary key (user_id, profile_id, content_id)       -- R3 upsert target
);

-- 2. RLS + grants.
alter table public.library enable row level security;
drop policy if exists "read own library" on public.library;
create policy "read own library" on public.library
  for select using (auth.uid() = user_id);
revoke insert, update, delete, truncate, references, trigger on public.library from anon, authenticated;
revoke select on public.library from anon;
grant  select on public.library to authenticated;

-- 3. Push: snapshot upsert (last-write-wins — library is a wholesale snapshot, no event log/guard).
--    genres stored as text[] (client pushes a JSON string array); imdb_rating omitted-when-null.
create or replace function public.sync_push_library_for(
  p_owner uuid, p_profile_id int, p_items jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare e jsonb;
begin
  if p_owner is null or p_items is null then return; end if;     -- R4
  for e in select value from jsonb_array_elements(p_items) as t(value) loop
    insert into public.library as lib(
      user_id, profile_id, content_id, content_type, name, poster, poster_shape,
      background, description, release_info, imdb_rating, genres, addon_base_url, added_at)
    values (
      p_owner, p_profile_id, e->>'content_id', e->>'content_type', coalesce(e->>'name',''),
      e->>'poster', coalesce(e->>'poster_shape','POSTER'), e->>'background', e->>'description',
      e->>'release_info', nullif(e->>'imdb_rating','')::real,
      -- genres: guard the type so an explicit JSON `"genres": null` can't raise
      -- "cannot extract elements from a scalar" (absent key already yields '{}'). Defense-in-depth —
      -- the verified client always sends a JSON array, but this makes the push total over any input.
      case when jsonb_typeof(e->'genres') = 'array'
           then coalesce((select array_agg(g) from jsonb_array_elements_text(e->'genres') as t(g)), '{}')
           else '{}' end,
      e->>'addon_base_url', coalesce((e->>'added_at')::bigint, 0))
    on conflict (user_id, profile_id, content_id) do update
      set content_type=excluded.content_type, name=excluded.name, poster=excluded.poster,
          poster_shape=excluded.poster_shape, background=excluded.background,
          description=excluded.description, release_info=excluded.release_info,
          imdb_rating=excluded.imdb_rating, genres=excluded.genres,
          addon_base_url=excluded.addon_base_url, added_at=excluded.added_at, updated_at=now();
  end loop;
end $$;

create or replace function public.sync_push_library(
  p_items jsonb, p_profile_id int
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_library_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_items)
$$;

-- 3b. Function ACLs.
revoke all on function public.sync_push_library_for(uuid, int, jsonb) from public, anon, authenticated;
revoke all     on function public.sync_push_library(jsonb, int) from public, anon;
grant  execute on function public.sync_push_library(jsonb, int) to authenticated;
