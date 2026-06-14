-- ============================================================================
-- sync_canary_members allowlist GATE (spec §8 option a) — TDD spec.
-- The gate lives in get_sync_owner(): it returns auth.uid()::text ONLY when the
-- caller is in public.sync_canary_members, else NULL (CLOSED BY DEFAULT). Because
-- EVERY sync RPC resolves its owner via nullif(get_sync_owner(),'')::uuid, a NULL
-- owner makes every pull empty, every push a no-op, and sync_pull_profiles() EMPTY
-- (no synth default to wipe local profiles via the client's replaceAllProfiles).
--
-- WHY NULL (not ''): the client AuthManager.getEffectiveUserId calls get_sync_owner
-- directly and does result.decodeAs<String>() into a NON-nullable String. A NULL
-- body throws there (caught → same fallback as today's RPC-absent 42883), so NULL
-- reproduces today's behavior EXACTLY. '' would decode to a non-null "" and flow
-- into SyncRepositoryImpl/AccountViewModel as a bogus owner id (new, untested state).
-- Downstream nullif(get_sync_owner(),'')::uuid collapses NULL→NULL owner identically.
--
-- Run order: AFTER all *_setup.sql (this calls every broad-restore RPC), as the
-- FIRST *_test.sql. Isolated by run_sync_tests.sh's per-file begin..rollback.
-- ============================================================================

-- ============ T-GATE-OFF: logged-in but NOT allowlisted = sync fully inert ============
-- Simulate the JWT claim DIRECTLY (NOT test_login — that would allowlist the member).
do $$
declare g uuid := 'ca0a0000-0000-4000-8000-000000000001';   -- gated-off member
        v text; n int; ov jsonb;
        before_rows bigint; after_rows bigint;
begin
  insert into auth.users(id) values (g) on conflict do nothing;
  perform set_config('request.jwt.claims', json_build_object('sub', g::text)::text, true);

  -- this member is NOT in the allowlist...
  assert not exists (select 1 from public.sync_canary_members where user_id = g),
    'precondition: gated-off member must not be allowlisted';
  -- ...so the shared resolver returns NULL (gate closed). NULL — not '' — see header.
  select public.get_sync_owner() into v;
  assert v is null, format('gated-off member must resolve owner NULL (gate closed), got %s', v);

  -- Every broad-restore PULL returns 0 rows (NULL owner => owner predicate matches nothing).
  assert (select count(*) from public.sync_pull_collections(1))                 = 0, 'gated: collections pull must be empty';
  assert (select count(*) from public.sync_pull_home_catalog_settings(1,'tv'))  = 0, 'gated: home_catalog pull must be empty';
  assert (select count(*) from public.sync_pull_profile_settings_blob(1,'tv'))  = 0, 'gated: profile_settings_blob pull must be empty';
  assert (select count(*) from public.sync_pull_library(1, 10, 0))              = 0, 'gated: library pull must be empty';
  assert (select count(*) from public.sync_pull_watch_progress(1, null, null))  = 0, 'gated: watch_progress pull must be empty';
  assert (select count(*) from public.sync_pull_watched_items(1, 1, 10))        = 0, 'gated: watched_items pull must be empty';

  -- CRITICAL no-wipe guarantee: sync_pull_profiles() returns 0 rows (never a synth default).
  -- A non-empty pull here would make the client replaceAllProfiles() and WIPE local profiles.
  select count(*) into n from public.sync_pull_profiles();
  assert n = 0, format('gated: sync_pull_profiles MUST be 0 rows (no synth default wipe), got %s', n);
  assert (select count(*) from public.sync_pull_profile_locks()) = 0, 'gated: profile_locks pull must be empty';

  -- Every PUSH is a no-op (R4 NULL-owner early return). Measure total rows across all data tables.
  select (select count(*) from public.collections)            + (select count(*) from public.home_catalog_settings)
       + (select count(*) from public.profile_settings_blob)  + (select count(*) from public.library)
       + (select count(*) from public.watch_progress)         + (select count(*) from public.watched_items)
       + (select count(*) from public.profiles)               + (select count(*) from public.profile_locks)
    into before_rows;

  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','g')));
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object('k','v'), 'tv');
  perform public.sync_push_profile_settings_blob(1, jsonb_build_object('version',1,'features',jsonb_build_object()), 'tv');
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','x','content_type','movie','name','X','poster_shape','POSTER','genres',jsonb_build_array(),'added_at',1)), 1);
  perform public.sync_push_watch_progress(jsonb_build_array(jsonb_build_object(
    'content_id','wp','content_type','movie','video_id','v','position',1,'duration',10,'last_watched',1,'progress_key','wp:1')), 1);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','wi','content_type','movie','title','W','season',null,'episode',null,'watched_at',1)), 1);
  perform public.sync_push_profiles(5, jsonb_build_array(jsonb_build_object(
    'profile_index',2,'name','x','avatar_color_hex','#1','uses_primary_addons',false,'uses_primary_plugins',false)));

  select (select count(*) from public.collections)            + (select count(*) from public.home_catalog_settings)
       + (select count(*) from public.profile_settings_blob)  + (select count(*) from public.library)
       + (select count(*) from public.watch_progress)         + (select count(*) from public.watched_items)
       + (select count(*) from public.profiles)               + (select count(*) from public.profile_locks)
    into after_rows;
  assert after_rows = before_rows,
    format('gated: all pushes must no-op (NULL owner); rows changed %s -> %s', before_rows, after_rows);
  assert not exists (select 1 from public.profiles where user_id = g), 'gated: no profile row may be written for a gated-off member';

  -- get_sync_overview is all-empty (NULL owner => every count object collapses to {}).
  select public.get_sync_overview() into ov;
  assert ov->'library_items'  = '{}'::jsonb, format('gated overview library_items not empty: %s', ov);
  assert ov->'watch_progress' = '{}'::jsonb, format('gated overview watch_progress not empty: %s', ov);
  assert ov->'watched_items'  = '{}'::jsonb, format('gated overview watched_items not empty: %s', ov);
  assert ov->'profiles'       = '{}'::jsonb, format('gated overview profiles not empty: %s', ov);

  -- sync_delete_profile_data is a no-op for a gated-off member (NULL owner) and must NEVER error.
  perform public.sync_delete_profile_data(2);

  raise notice 'T-GATE-OFF OK — gated-off member is fully inert (no pull/push/wipe)';
end $$;

-- ============ T-GATE-ON: allowlisted member round-trips normally ============
do $$
declare a uuid := 'ca0a0000-0000-4000-8000-0000000000a1';   -- allowlisted member
        n int;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);   -- sets JWT claim AND inserts into sync_canary_members

  -- gate open: resolver returns the real uid.
  assert public.get_sync_owner() = a::text, 'allowlisted member must resolve own uid (gate open)';
  assert exists (select 1 from public.sync_canary_members where user_id = a), 'test_login must allowlist the member';

  -- push -> pull round-trips for a representative snapshot subsystem...
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','c1')));
  select count(*) into n from public.sync_pull_collections(1);
  assert n = 1, format('allowlisted: collections push->pull must round-trip, got %s rows', n);

  -- ...and for profiles (the wipe-sensitive path): stored rows DO come back.
  perform public.sync_push_profiles(5, jsonb_build_array(jsonb_build_object(
    'profile_index',2,'name','On','avatar_color_hex','#1','uses_primary_addons',false,'uses_primary_plugins',false)));
  select count(*) into n from public.sync_pull_profiles();
  assert n = 1, format('allowlisted: profiles push->pull must round-trip, got %s rows', n);

  raise notice 'T-GATE-ON OK — allowlisted member syncs normally';
end $$;

-- ============ ACL: sync_canary_members is definer/admin-managed, never member-facing ============
do $$
begin
  assert to_regclass('public.sync_canary_members') is not null, 'sync_canary_members table missing';
  assert (select relrowsecurity from pg_class where oid='public.sync_canary_members'::regclass),
    'RLS must be enabled on sync_canary_members';
  -- No grants to app roles: the SECURITY DEFINER get_sync_owner reads it as the definer, not the caller.
  assert not has_table_privilege('authenticated','public.sync_canary_members','SELECT'), 'authenticated must NOT SELECT sync_canary_members';
  assert not has_table_privilege('authenticated','public.sync_canary_members','INSERT'), 'authenticated must NOT INSERT sync_canary_members (self-allowlist)';
  assert not has_table_privilege('authenticated','public.sync_canary_members','UPDATE'), 'authenticated must NOT UPDATE sync_canary_members';
  assert not has_table_privilege('anon','public.sync_canary_members','SELECT'),          'anon must NOT SELECT sync_canary_members';
  assert not has_table_privilege('anon','public.sync_canary_members','INSERT'),          'anon must NOT INSERT sync_canary_members';
  raise notice 'sync_canary_members ACL OK — no app-role grants (admin/definer only)';
end $$;
