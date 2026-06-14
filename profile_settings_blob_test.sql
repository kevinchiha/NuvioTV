-- ============ profile_settings_blob: schema ============
do $$
begin
  assert to_regclass('public.profile_settings_blob') is not null, 'profile_settings_blob table missing';
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.profile_settings_blob'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.profile_settings_blob'::regclass and attnum=any(conkey))
          = array['platform','profile_id','user_id']
  ), 'profile_settings_blob PK must be (user_id, profile_id, platform)';
  assert (select atttypid::regtype::text from pg_attribute
          where attrelid='public.profile_settings_blob'::regclass and attname='settings_json') = 'jsonb',
    'settings_json must be jsonb';
  assert (select relrowsecurity from pg_class where oid='public.profile_settings_blob'::regclass), 'RLS off';
  assert not has_table_privilege('authenticated','public.profile_settings_blob','INSERT'), 'authenticated can INSERT';
  assert has_table_privilege('authenticated','public.profile_settings_blob','SELECT'), 'authenticated needs SELECT';
  assert not has_table_privilege('anon','public.profile_settings_blob','SELECT'), 'anon must not SELECT';
  raise notice 'profile_settings_blob schema OK';
end $$;

-- ============ profile_settings_blob: push/pull (R1, R7 shape, nested features round-trip verbatim) ============
do $$
declare a uuid := '11111111-1111-eeee-1111-111111111111';
        b uuid := '22222222-2222-eeee-2222-222222222222';
        v_shape text; v_json jsonb; blob jsonb;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  -- the nested {version, features:{...}} blob the client expects.
  blob := jsonb_build_object(
    'version', 1,
    'features', jsonb_build_object(
      'player', jsonb_build_object(
        'forced_subs', jsonb_build_object('type','boolean','value', false),
        'sub_lang',    jsonb_build_object('type','string','value','en'))));

  perform public.test_login(a);
  assert (select count(*) from public.sync_pull_profile_settings_blob(1,'tv')) = 0, 'absent blob must be 0 rows';
  perform public.sync_push_profile_settings_blob(1, blob, 'tv');
  perform public.test_login(b);
  perform public.sync_push_profile_settings_blob(1, jsonb_build_object('version',1,'features',jsonb_build_object('x',jsonb_build_object())), 'tv');

  -- R1 + verbatim round-trip: A's features sub-object must come back byte-equal.
  perform public.test_login(a);
  assert (select count(*) from public.sync_pull_profile_settings_blob(1,'tv')) = 1, 'A must have 1 tv blob';
  select settings_json into v_json from public.sync_pull_profile_settings_blob(1,'tv');
  assert v_json = blob, format('blob must round-trip verbatim; got %s', v_json::text);
  assert v_json -> 'features' is not null, 'features sub-object must be present (client no-ops if absent)';
  assert v_json #> '{features,player,sub_lang,value}' = '"en"'::jsonb, 'nested feature value must survive verbatim';

  -- R3: re-push overwrites the SAME (user,profile,platform) row.
  perform public.sync_push_profile_settings_blob(1, jsonb_build_object('version',2,'features',jsonb_build_object()), 'tv');
  assert (select count(*) from public.sync_pull_profile_settings_blob(1,'tv')) = 1, 're-push must not duplicate';
  assert (select settings_json->>'version' from public.sync_pull_profile_settings_blob(1,'tv')) = '2', 're-push must overwrite';

  -- R7 wire-shape.
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_profile_settings_blob(1,'tv') limit 1 ) t ) s;
  assert v_shape = 'profile_id,settings_json,updated_at',
    format('pull row JSON keys must match SupabaseProfileSettingsBlob exactly; got: %s', v_shape);

  raise notice 'profile_settings_blob push/pull OK';
end $$;
