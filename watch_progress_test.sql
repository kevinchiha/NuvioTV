-- ============ watch_progress: schema ============
do $$
begin
  assert to_regclass('public.watch_progress') is not null, 'watch_progress table missing';
  assert to_regclass('public.watch_progress_events') is not null, 'watch_progress_events table missing';

  -- R3: composite primary key = the ON CONFLICT upsert target. Cast attname (type "name") to text.
  assert exists (
    select 1 from pg_constraint
    where conrelid = 'public.watch_progress'::regclass and contype = 'p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute
           where attrelid = 'public.watch_progress'::regclass
             and attnum = any(conkey)) = array['profile_id','progress_key','user_id']
  ), 'watch_progress PK must be (user_id, profile_id, progress_key)';

  -- RLS enabled on both tables.
  assert (select relrowsecurity from pg_class where oid = 'public.watch_progress'::regclass), 'RLS off on watch_progress';
  assert (select relrowsecurity from pg_class where oid = 'public.watch_progress_events'::regclass), 'RLS off on watch_progress_events';

  -- Writes are RPC-only: authenticated has NO direct DML on the tables.
  assert not has_table_privilege('authenticated', 'public.watch_progress', 'INSERT'), 'authenticated can INSERT watch_progress';
  assert not has_table_privilege('authenticated', 'public.watch_progress', 'UPDATE'), 'authenticated can UPDATE watch_progress';
  assert not has_table_privilege('authenticated', 'public.watch_progress', 'DELETE'), 'authenticated can DELETE watch_progress';
  -- SELECT is granted to authenticated (RLS scopes it to own rows); anon has none.
  assert has_table_privilege('authenticated', 'public.watch_progress', 'SELECT'), 'authenticated needs RLS-scoped SELECT';
  assert not has_table_privilege('anon', 'public.watch_progress', 'SELECT'), 'anon must not SELECT watch_progress';

  raise notice 'watch_progress schema OK';
end $$;

-- ============ watch_progress: push (R2 last_watched guard, R3 dedup) ============
do $$
declare a uuid := '22222222-2222-2222-2222-222222222222';   -- throwaway test member
        v_pos bigint; v_events int; v_events2 int;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);

  -- Insert a fresh entry.
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','tt1','content_type','movie','video_id','tt1',
      'position',10,'duration',100,'last_watched',1000,'progress_key','tt1')), 1);
  select position into v_pos from public.watch_progress where user_id=a and progress_key='tt1';
  assert v_pos = 10, format('expected position 10, got %s', v_pos);

  -- Newer last_watched wins and updates position.
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','tt1','content_type','movie','video_id','tt1',
      'position',500,'duration',100,'last_watched',2000,'progress_key','tt1')), 1);
  select position into v_pos from public.watch_progress where user_id=a and progress_key='tt1';
  assert v_pos = 500, format('newer push should set position 500, got %s', v_pos);

  -- R2: a STALE push (older last_watched) must NOT clobber and must NOT append an event.
  select count(*) into v_events from public.watch_progress_events where user_id=a;
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','tt1','content_type','movie','video_id','tt1',
      'position',10,'duration',100,'last_watched',1000,'progress_key','tt1')), 1);
  select position into v_pos from public.watch_progress where user_id=a and progress_key='tt1';
  assert v_pos = 500, format('stale push must not regress; got %s', v_pos);
  assert (select count(*) from public.watch_progress_events where user_id=a) = v_events,
    'stale push must not append an event';

  -- R2 boundary: an EQUAL last_watched push also must NOT update or append (guard is ">", not ">=").
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','tt1','content_type','movie','video_id','tt1',
      'position',999,'duration',100,'last_watched',2000,'progress_key','tt1')), 1);
  select position into v_pos from public.watch_progress where user_id=a and progress_key='tt1';
  assert v_pos = 500, format('equal-timestamp push must not update; got %s', v_pos);
  assert (select count(*) from public.watch_progress_events where user_id=a) = v_events,
    'equal-timestamp push must not append an event';

  -- R3: repeated key updates the SAME row (no duplication).
  assert (select count(*) from public.watch_progress where user_id=a and progress_key='tt1') = 1,
    'progress_key must dedup to one row';

  raise notice 'watch_progress push OK';
end $$;

-- TV-07: a mixed batch (one stale + one fresh) appends EXACTLY one event.
do $$
declare a uuid := '22222222-2222-2222-2222-222222222222';
        v_before int; v_after int;
begin
  perform public.test_login(a);
  select count(*) into v_before from public.watch_progress_events where user_id=a;
  perform public.sync_push_watch_progress(
    jsonb_build_array(
      -- stale: tt1 is already at last_watched 2000, this one is older => guard rejects, no event
      jsonb_build_object('content_id','tt1','content_type','movie','video_id','tt1',
        'position',1,'duration',100,'last_watched',1,'progress_key','tt1'),
      -- fresh: brand-new key => inserted, one event
      jsonb_build_object('content_id','mix','content_type','movie','video_id','mix',
        'position',3,'duration',30,'last_watched',5000,'progress_key','mix_fresh')), 1);
  select count(*) into v_after from public.watch_progress_events where user_id=a;
  assert v_after = v_before + 1, format('mixed batch must append exactly 1 event, got %s', v_after - v_before);
  raise notice 'watch_progress mixed-batch OK';
end $$;
