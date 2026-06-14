-- ============ prune_sync_events: retention (R10) ============
do $$
declare a uuid := '11111111-9999-9999-9999-111111111111';
        n_old int; n_new int; v_msg text;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);

  -- create one watch_progress event + one watched_items event (recent).
  perform public.sync_push_watch_progress(jsonb_build_array(jsonb_build_object(
    'content_id','wp','content_type','movie','video_id','v','position',1,'duration',10,
    'last_watched',1,'progress_key','wp:1')), 1);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','wi','content_type','movie','title','W','season',null,'episode',null,'watched_at',1)), 1);

  -- backdate the watch_progress event well past the window (the test connection is table owner).
  update public.watch_progress_events set created_at = now() - interval '400 days' where user_id=a;

  -- prune events older than 180 days: the backdated wp event goes; the recent wi event stays.
  select public.prune_sync_events(180) into v_msg;
  select count(*) into n_old from public.watch_progress_events where user_id=a;
  select count(*) into n_new from public.watched_items_events  where user_id=a;
  assert n_old = 0, format('old watch_progress event must be pruned, got %s', n_old);
  assert n_new = 1, format('recent watched_items event must survive, got %s', n_new);
  assert v_msg like 'pruned %', format('prune must return a summary string; got %s', v_msg);

  raise notice 'prune_sync_events OK';
end $$;

-- ============ prune_sync_events: ACLs (admin/cron-only — NOT member-facing) ============
do $$
begin
  assert not has_function_privilege('authenticated', 'public.prune_sync_events(int)', 'EXECUTE'),
    'prune must NOT be executable by authenticated';
  assert not has_function_privilege('anon', 'public.prune_sync_events(int)', 'EXECUTE'),
    'prune must NOT be executable by anon';
  raise notice 'prune_sync_events ACLs OK';
end $$;

-- ============ get_sync_overview: owner-scoped per-profile counts (§7) ============
do $$
declare a uuid := '22222222-9999-9999-9999-222222222222';
        b uuid := '33333333-9999-9999-9999-333333333333';
        ov jsonb;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  perform public.test_login(a);
  perform public.sync_push_library(jsonb_build_array(
    jsonb_build_object('content_id','l1','content_type','movie','name','L1','poster_shape','POSTER','genres',jsonb_build_array(),'added_at',1),
    jsonb_build_object('content_id','l2','content_type','movie','name','L2','poster_shape','POSTER','genres',jsonb_build_array(),'added_at',1)), 1);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','wi','content_type','movie','title','W','season',null,'episode',null,'watched_at',1)), 1);
  perform public.sync_push_profiles(5, jsonb_build_array(jsonb_build_object(
    'profile_index',1,'name','Main','avatar_color_hex','#abc','uses_primary_addons',false,'uses_primary_plugins',false)));

  -- member B has different data (must not bleed into A's overview).
  perform public.test_login(b);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','bl','content_type','movie','name','BL','poster_shape','POSTER','genres',jsonb_build_array(),'added_at',1)), 1);

  perform public.test_login(a);
  select public.get_sync_overview() into ov;

  -- shape: all 6 SyncOverviewResponse keys present (maps default-safe, but assert structure).
  assert ov ? 'library_items' and ov ? 'watch_progress' and ov ? 'watched_items'
     and ov ? 'profiles' and ov ? 'addons' and ov ? 'plugins',
    format('overview must carry all SyncOverviewResponse keys; got %s', ov::text);
  -- A's profile-1 counts (R1: only A's data).
  assert (ov #>> '{library_items,1}')::int = 2, format('library_items[1] must be 2; got %s', ov #>> '{library_items,1}');
  assert (ov #>> '{watched_items,1}')::int = 1, format('watched_items[1] must be 1; got %s', ov #>> '{watched_items,1}');
  assert ov #>> '{profiles,1,name}' = 'Main', format('profiles[1].name must be Main; got %s', ov #>> '{profiles,1,name}');
  assert (ov #>> '{library_items,1}')::int <> 3, 'must not include member B''s library row';
  assert jsonb_typeof(ov->'addons') = 'object' and jsonb_typeof(ov->'plugins') = 'object', 'addons/plugins must be (empty) objects';

  raise notice 'get_sync_overview OK';
end $$;

-- ============ get_sync_overview: ACL + anon safety ============
do $$
declare ov jsonb;
begin
  assert has_function_privilege('authenticated', 'public.get_sync_overview()', 'EXECUTE'),
    'get_sync_overview must be executable by authenticated';
  -- anon: never errors; all maps empty (no data leak).
  perform public.test_logout();
  select public.get_sync_overview() into ov;
  assert ov ? 'library_items' and jsonb_typeof(ov->'library_items') = 'object', 'anon overview must be a valid object';
  assert ov #>> '{library_items,1}' is null, 'anon overview must contain no data';
  raise notice 'get_sync_overview anon OK';
end $$;
