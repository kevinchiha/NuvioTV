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
