-- KevBox TV cloud-restore — post-deploy RPC probe (spec §9). Calls every deployed sync RPC as a test
-- member and asserts none raise (a missing/renamed RPC raises 42883; a wrong return shape raises on the
-- aggregate). Run inside ONE rolled-back transaction so nothing commits — safe on branch OR live prod.
--   Branch:  psql "$SYNC_TEST_DB_URL" -v ON_ERROR_STOP=1 -f sync_test_helpers.sql -f probe_sync_rpcs.sql
--   Live:    replace the test_login(...) seed with a real member's JWT claims (runbook) and DROP the
--            `insert into auth.users` line (the member already exists).
begin;
do $$
declare m uuid := 'deadbeef-9999-4444-9999-deadbeefbeef';
        sink_b bigint; sink_j jsonb; sink_n int;
begin
  insert into auth.users(id) values (m) on conflict do nothing;   -- branch only; remove for live
  perform public.test_login(m);

  -- get_sync_owner
  perform public.get_sync_owner();

  -- watch_progress (5)
  perform public.sync_push_watch_progress(jsonb_build_array(jsonb_build_object(
    'content_id','p','content_type','movie','video_id','v','position',1,'duration',2,'last_watched',1,'progress_key','p:1')), 1);
  perform count(*) from public.sync_pull_watch_progress(1, null, null);
  select public.sync_get_watch_progress_delta_cursor(1) into sink_b;
  perform count(*) from public.sync_pull_watch_progress_delta(1, 0, 10);
  perform public.sync_delete_watch_progress(jsonb_build_array('p:1'), 1);

  -- watched_items (5)
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','w','content_type','movie','title','W','season',null,'episode',null,'watched_at',1)), 1);
  perform count(*) from public.sync_pull_watched_items(1, 1, 10);
  select public.sync_get_watched_items_delta_cursor(1) into sink_b;
  perform count(*) from public.sync_pull_watched_items_delta(1, 0, 10);
  perform public.sync_delete_watched_items(1, jsonb_build_array(jsonb_build_object('content_id','w')));

  -- library (2)
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','l','content_type','movie','name','L','poster_shape','POSTER','genres',jsonb_build_array(),'added_at',1)), 1);
  perform count(*) from public.sync_pull_library(1, 10, 0);

  -- collections (2)
  perform public.sync_push_collections(1, jsonb_build_array());
  perform count(*) from public.sync_pull_collections(1);

  -- home_catalog_settings (2)  — probe all three pulled platforms
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object(), 'home_catalog_shared');
  perform count(*) from public.sync_pull_home_catalog_settings(1, 'home_catalog_shared');
  perform count(*) from public.sync_pull_home_catalog_settings(1, 'tv');
  perform count(*) from public.sync_pull_home_catalog_settings(1, 'mobile');

  -- profile_settings_blob (2)
  perform public.sync_push_profile_settings_blob(1, jsonb_build_object('version',1,'features',jsonb_build_object()), 'tv');
  perform count(*) from public.sync_pull_profile_settings_blob(1, 'tv');

  -- profiles (4)
  select count(*) into sink_n from public.sync_pull_profiles();
  assert sink_n >= 1, 'sync_pull_profiles must return >=1 row (the un-guarded startup pull)';
  perform count(*) from public.sync_pull_profile_locks();
  perform public.sync_push_profiles(5, jsonb_build_array(jsonb_build_object(
    'profile_index',2,'name','probe','avatar_color_hex','#1','uses_primary_addons',false,'uses_primary_plugins',false)));
  perform public.sync_delete_profile_data(2);

  -- get_sync_overview (1)
  select public.get_sync_overview() into sink_j;

  raise notice 'PROBE OK — all sync RPCs resolved and returned without 42883/shape errors';
end $$;
rollback;
