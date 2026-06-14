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
