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

-- ============ watch_progress: pull (R1 isolation, R7 exact shape, R8 stable order) ============
do $$
declare a uuid := '33333333-3333-3333-3333-333333333333';
        b uuid := '44444444-4444-4444-4444-444444444444';
        v_count int; v_keys text; v_shape text;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  perform public.test_login(a);
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','m1','content_type','movie','video_id','m1',
      'position',5,'duration',50,'last_watched',1000,'progress_key','m1')), 1);

  perform public.test_login(b);
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','m2','content_type','movie','video_id','m2',
      'position',5,'duration',50,'last_watched',1000,'progress_key','m2')), 1);

  -- R1: member B (current session) pulls only B's rows, never A's.
  select count(*), string_agg(progress_key, ',' order by progress_key)
    into v_count, v_keys
    from public.sync_pull_watch_progress(1, null, null);
  assert v_count = 1, format('B should pull 1 row, got %s', v_count);
  assert v_keys = 'm2', format('B must not see A''s rows; got keys %s', v_keys);

  -- R7 wire-shape guard: a pulled row, as JSON, must have EXACTLY the 11 client-model keys —
  -- no updated_at, no extra columns. LIMIT the ROW first, THEN expand keys (key-expansion is
  -- set-returning, so limiting after it would truncate to a single key).
  select string_agg(k, ',' order by k) into v_shape
  from (
    select jsonb_object_keys(to_jsonb(t)) as k
    from ( select * from public.sync_pull_watch_progress(1, null, null) limit 1 ) t
  ) s;
  assert v_shape = 'content_id,content_type,duration,episode,last_watched,position,profile_id,progress_key,season,user_id,video_id',
    format('pull row JSON keys must match SupabaseWatchProgress exactly; got: %s', v_shape);

  -- F3: p_since_last_watched is INCLUSIVE (>=). Add a newer row for B and pin the boundary.
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object('content_id','m3','content_type','movie','video_id','m3',
      'position',1,'duration',10,'last_watched',3000,'progress_key','m3')), 1);
  assert (select count(*) from public.sync_pull_watch_progress(1, 3000, null)) = 1,
    'since=3000 (inclusive) must return exactly the m3 row';
  assert (select count(*) from public.sync_pull_watch_progress(1, 3001, null)) = 0,
    'since=3001 must exclude m3 (boundary is >=)';

  raise notice 'watch_progress pull OK';
end $$;
