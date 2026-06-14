-- ============ home_catalog_settings: schema ============
do $$
begin
  assert to_regclass('public.home_catalog_settings') is not null, 'home_catalog_settings table missing';
  -- R3: PK = ON CONFLICT target (user_id, profile_id, platform).
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.home_catalog_settings'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.home_catalog_settings'::regclass and attnum=any(conkey))
          = array['platform','profile_id','user_id']
  ), 'home_catalog_settings PK must be (user_id, profile_id, platform)';
  assert (select atttypid::regtype::text from pg_attribute
          where attrelid='public.home_catalog_settings'::regclass and attname='settings_json') = 'jsonb',
    'settings_json must be jsonb';
  assert (select relrowsecurity from pg_class where oid='public.home_catalog_settings'::regclass), 'RLS off';
  assert not has_table_privilege('authenticated','public.home_catalog_settings','INSERT'), 'authenticated can INSERT';
  assert has_table_privilege('authenticated','public.home_catalog_settings','SELECT'), 'authenticated needs SELECT';
  assert not has_table_privilege('anon','public.home_catalog_settings','SELECT'), 'anon must not SELECT';
  raise notice 'home_catalog_settings schema OK';
end $$;

-- ============ home_catalog_settings: push/pull (R1, R7 shape, JSON object, multi-platform, empty-set) ============
do $$
declare a uuid := '11111111-1111-dddd-1111-111111111111';
        b uuid := '22222222-2222-dddd-2222-222222222222';
        v_shape text; v_json jsonb; n int;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  -- absent platform must return ZERO rows (the client pulls 3 platforms; missing must NOT error).
  perform public.test_login(a);
  assert (select count(*) from public.sync_pull_home_catalog_settings(1,'tv')) = 0, 'absent platform must be 0 rows';

  -- push the shared platform; pull it back verbatim (JSON OBJECT).
  perform public.sync_push_home_catalog_settings(1,
    jsonb_build_object('hide_unreleased_content', true, 'items', jsonb_build_array('x','y')),
    'home_catalog_shared');
  -- a DIFFERENT platform for the SAME profile is a DISTINCT row.
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object('items', jsonb_build_array('m')), 'mobile');

  perform public.test_login(b);
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object('items', jsonb_build_array('bonly')), 'home_catalog_shared');

  -- R1 + per-platform isolation: A pulls only A's shared row; A's two platforms are distinct.
  perform public.test_login(a);
  select count(*) into n from public.sync_pull_home_catalog_settings(1,'home_catalog_shared');
  assert n = 1, format('A shared pull should be 1 row, got %s', n);
  select settings_json into v_json from public.sync_pull_home_catalog_settings(1,'home_catalog_shared');
  assert v_json = jsonb_build_object('hide_unreleased_content', true, 'items', jsonb_build_array('x','y')),
    format('A shared blob mismatch; got %s', v_json::text);
  assert jsonb_typeof(v_json) = 'object', 'settings_json must serialize as a JSON object';
  assert (select settings_json from public.sync_pull_home_catalog_settings(1,'mobile'))
         = jsonb_build_object('items', jsonb_build_array('m')), 'mobile platform must be its own row';

  -- R3: re-push shared overwrites the SAME (user,profile,platform) row.
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object('items', jsonb_build_array('z')), 'home_catalog_shared');
  assert (select count(*) from public.sync_pull_home_catalog_settings(1,'home_catalog_shared')) = 1, 're-push must not duplicate';
  assert (select settings_json from public.sync_pull_home_catalog_settings(1,'home_catalog_shared'))
         = jsonb_build_object('items', jsonb_build_array('z')), 're-push must overwrite';

  -- R7 wire-shape: exact SupabaseHomeCatalogSettingsBlob keys (no user_id, no platform).
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_home_catalog_settings(1,'home_catalog_shared') limit 1 ) t ) s;
  assert v_shape = 'profile_id,settings_json,updated_at',
    format('pull row JSON keys must match SupabaseHomeCatalogSettingsBlob exactly; got: %s', v_shape);

  raise notice 'home_catalog_settings push/pull OK';
end $$;
