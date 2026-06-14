-- ============ profiles + profile_locks: schema ============
do $$
begin
  assert to_regclass('public.profiles') is not null, 'profiles table missing';
  assert to_regclass('public.profile_locks') is not null, 'profile_locks table missing';

  -- R3: profiles PK = (user_id, profile_index); profile_locks PK = (user_id, profile_index).
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.profiles'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.profiles'::regclass and attnum=any(conkey))
          = array['profile_index','user_id']
  ), 'profiles PK must be (user_id, profile_index)';
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.profile_locks'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.profile_locks'::regclass and attnum=any(conkey))
          = array['profile_index','user_id']
  ), 'profile_locks PK must be (user_id, profile_index)';

  -- profile_index is a non-null int (the one required model field).
  assert (select attnotnull from pg_attribute where attrelid='public.profiles'::regclass and attname='profile_index'),
    'profiles.profile_index must be NOT NULL';

  assert (select relrowsecurity from pg_class where oid='public.profiles'::regclass), 'RLS off on profiles';
  assert (select relrowsecurity from pg_class where oid='public.profile_locks'::regclass), 'RLS off on profile_locks';
  assert not has_table_privilege('authenticated','public.profiles','INSERT'), 'authenticated can INSERT profiles';
  assert has_table_privilege('authenticated','public.profiles','SELECT'), 'authenticated needs SELECT on profiles';
  assert not has_table_privilege('anon','public.profiles','SELECT'), 'anon must not SELECT profiles';
  raise notice 'profiles schema OK';
end $$;

-- ============ profiles: pull (no-arg, auto-default, never-error; R1; R7 non-null profile_index) ============
-- T-PROFILE (§5.5): a brand-new member with ZERO profiles rows must still get a decodable row with a
-- non-null profile_index, or the un-guarded startup pull aborts the entire broad restore.
do $$
declare nw uuid := '11111111-1111-aaaa-aaaa-111111111111';
        a  uuid := '22222222-2222-aaaa-aaaa-222222222222';
        b  uuid := '33333333-3333-aaaa-aaaa-333333333333';
        n int; v_idx int; v_shape text;
begin
  insert into auth.users(id) values (nw),(a),(b) on conflict do nothing;

  -- brand-new member: no stored rows => exactly ONE synthesized default profile_index=1.
  perform public.test_login(nw);
  select count(*) into n from public.sync_pull_profiles();
  assert n = 1, format('brand-new member must get 1 synthesized default profile, got %s', n);
  select profile_index into v_idx from public.sync_pull_profiles();
  assert v_idx = 1, format('synthesized default profile_index must be 1, got %s', v_idx);

  -- with stored rows present, returns them (no synthesized default). Seed via direct INSERT (the
  -- *_test.sql runs as the connection owner, which bypasses the authenticated-only DML revoke) so this
  -- task is independently red->green without depending on sync_push_profiles (added in Task 9).
  insert into public.profiles(user_id, profile_index, name, avatar_color_hex, uses_primary_addons, uses_primary_plugins)
  values (nw, 1, 'Main', '#111111', true, false),
         (nw, 2, 'Kids', '#222222', false, false);
  select count(*) into n from public.sync_pull_profiles();
  assert n = 2, format('with stored rows, must return 2 profiles (no synth default), got %s', n);
  assert exists (select 1 from public.sync_pull_profiles() where profile_index=2 and name='Kids'), 'stored profile must round-trip';

  -- R1: member B (no profiles) gets only its own synthesized default, never A's rows.
  perform public.test_login(b);
  select count(*) into n from public.sync_pull_profiles();
  assert n = 1, format('B must see only its own synth default, got %s', n);
  assert not exists (select 1 from public.sync_pull_profiles() where name='Kids'), 'B must not see A''s profiles';

  -- R7 wire-shape: exact SupabaseProfile emitted set (11 keys).
  perform public.test_login(a);
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_profiles() limit 1 ) t ) s;
  assert v_shape = 'avatar_color_hex,avatar_id,avatar_url,created_at,id,name,profile_index,updated_at,user_id,uses_primary_addons,uses_primary_plugins',
    format('pull row JSON keys must match SupabaseProfile exactly; got: %s', v_shape);

  raise notice 'profiles pull OK';
end $$;

-- ============ profile_locks: pull (no-arg; R1; non-null profile_index) ============
do $$
declare a uuid := '44444444-4444-aaaa-aaaa-444444444444';
        n int;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);
  -- empty is safe (fail-soft; only affects PIN state).
  assert (select count(*) from public.sync_pull_profile_locks()) = 0, 'empty profile_locks pull must be 0 rows';
  raise notice 'profile_locks pull OK';
end $$;

-- ============ profiles: push (upsert by profile_index; skip out-of-range) + delete_profile_data ============
do $$
declare a uuid := '55555555-5555-aaaa-aaaa-555555555555';
        n int;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);

  -- push two profiles; re-push index 1 updates the SAME row (no dup).
  perform public.sync_push_profiles(5, jsonb_build_array(
    jsonb_build_object('profile_index',1,'name','One','avatar_color_hex','#1','uses_primary_addons',false,'uses_primary_plugins',false),
    jsonb_build_object('profile_index',2,'name','Two','avatar_color_hex','#2','uses_primary_addons',false,'uses_primary_plugins',false)));
  perform public.sync_push_profiles(5, jsonb_build_array(
    jsonb_build_object('profile_index',1,'name','One v2','avatar_color_hex','#1','uses_primary_addons',true,'uses_primary_plugins',false)));
  select count(*) into n from public.profiles where user_id=a;
  assert n = 2, format('re-push must not duplicate; expected 2 profiles, got %s', n);
  assert (select name from public.profiles where user_id=a and profile_index=1) = 'One v2', 're-push must overwrite name';
  assert (select uses_primary_addons from public.profiles where user_id=a and profile_index=1) = true, 're-push must overwrite flag';

  -- out-of-range / malformed indices are skipped (defensive use of p_client_max_profiles).
  perform public.sync_push_profiles(5, jsonb_build_array(
    jsonb_build_object('profile_index',99,'name','TooBig','avatar_color_hex','#9','uses_primary_addons',false,'uses_primary_plugins',false),
    jsonb_build_object('profile_index',0,'name','Zero','avatar_color_hex','#0','uses_primary_addons',false,'uses_primary_plugins',false)));
  assert not exists (select 1 from public.profiles where user_id=a and profile_index in (0,99)),
    'out-of-range profile_index (>max or <1) must be skipped';

  raise notice 'profiles push OK';
end $$;

-- sync_delete_profile_data: wipes the owner's rows for a profile across ALL subsystems; default (1) is guarded.
do $$
declare a uuid := '66666666-6666-aaaa-aaaa-666666666666';
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);
  -- seed data for profile 2 across several subsystems.
  perform public.sync_push_profiles(5, jsonb_build_array(
    jsonb_build_object('profile_index',2,'name','P2','avatar_color_hex','#2','uses_primary_addons',false,'uses_primary_plugins',false)));
  perform public.sync_push_collections(2, jsonb_build_array(jsonb_build_object('id','c')));
  perform public.sync_push_home_catalog_settings(2, jsonb_build_object('k','v'), 'tv');
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','x','content_type','movie','name','X','poster_shape','POSTER','genres', jsonb_build_array(),'added_at',1)), 2);
  perform public.sync_push_watch_progress(jsonb_build_array(jsonb_build_object(
    'content_id','wp','content_type','movie','video_id','v','position',1,'duration',10,'last_watched',1,'progress_key','wp:1')), 2);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','wi','content_type','movie','title','W','season',null,'episode',null,'watched_at',1)), 2);
  perform public.sync_push_profile_settings_blob(2, jsonb_build_object('version',1,'features',jsonb_build_object()), 'tv');
  insert into public.profile_locks(user_id, profile_index, pin_enabled) values (a, 2, true) on conflict do nothing;

  -- delete everything for profile 2.
  perform public.sync_delete_profile_data(2);
  assert not exists (select 1 from public.profiles where user_id=a and profile_index=2), 'profile row must be deleted';
  assert not exists (select 1 from public.collections where user_id=a and profile_id=2), 'collections must be deleted';
  assert not exists (select 1 from public.home_catalog_settings where user_id=a and profile_id=2), 'home_catalog must be deleted';
  assert not exists (select 1 from public.library where user_id=a and profile_id=2), 'library must be deleted';
  assert not exists (select 1 from public.watch_progress where user_id=a and profile_id=2), 'watch_progress must be deleted';
  assert not exists (select 1 from public.watched_items where user_id=a and profile_id=2), 'watched_items must be deleted';
  assert not exists (select 1 from public.profile_settings_blob where user_id=a and profile_id=2), 'profile_settings_blob must be deleted';
  assert not exists (select 1 from public.profile_locks where user_id=a and profile_index=2), 'profile_locks must be deleted';

  -- default profile (1) is server-guarded (matches the client guard) — deleting it is a no-op.
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','keep')));
  perform public.sync_delete_profile_data(1);
  assert exists (select 1 from public.collections where user_id=a and profile_id=1),
    'default profile (1) data must survive delete (server-guarded)';

  raise notice 'sync_delete_profile_data OK';
end $$;
