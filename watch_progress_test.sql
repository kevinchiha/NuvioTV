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

-- ============ watch_progress: delta cursor (R5 coalesce, never NULL) ============
do $$
declare z uuid := '55555555-5555-5555-5555-555555555555';
        c1 bigint; c2 bigint; v_max bigint;
begin
  insert into auth.users(id) values (z) on conflict do nothing;
  perform public.test_login(z);

  -- Brand-new member, zero events: MUST return 0 (not NULL — client decodes a non-null Long).
  select public.sync_get_watch_progress_delta_cursor(1) into c1;
  assert c1 = 0, format('empty cursor must be 0, got %s', c1);

  -- After a push, the cursor equals this owner's own max event_id (not a global/foreign value).
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','c','content_type','movie','video_id','c',
      'position',1,'duration',10,'last_watched',1,'progress_key','c')), 1);
  select public.sync_get_watch_progress_delta_cursor(1) into c2;
  select max(event_id) into v_max from public.watch_progress_events where user_id=z;
  assert c2 = v_max, format('cursor must equal owner max event_id %s, got %s', v_max, c2);

  raise notice 'watch_progress delta cursor OK';
end $$;

-- ============ watch_progress: delta pull (R1 owner, R8 asc+limit) ============
do $$
declare d uuid := '66666666-6666-6666-6666-666666666666';
        e uuid := '77777777-7777-7777-7777-777777777777';
        n int; first_id bigint; last_id bigint;
begin
  insert into auth.users(id) values (d),(e) on conflict do nothing;

  perform public.test_login(e);   -- noise from another member must never appear in d's delta
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object('content_id','x','content_type','movie',
      'video_id','x','position',1,'duration',9,'last_watched',1,'progress_key','x')), 1);

  perform public.test_login(d);
  for i in 1..3 loop
    perform public.sync_push_watch_progress(
      jsonb_build_array(jsonb_build_object('content_id','k'||i,'content_type','movie',
        'video_id','k'||i,'position',i,'duration',99,'last_watched',i,'progress_key','k'||i)), 1);
  end loop;

  -- From cursor 0, limit 2: exactly 2 of d's events, ascending, none of e's.
  select count(*), min(event_id), max(event_id)
    into n, first_id, last_id
    from public.sync_pull_watch_progress_delta(1, 0, 2);
  assert n = 2, format('expected 2 delta rows, got %s', n);
  assert first_id < last_id, 'delta rows must be ascending by event_id';
  assert not exists (
    select 1 from public.sync_pull_watch_progress_delta(1, 0, 100) where progress_key = 'x'
  ), 'd must never see member e''s events';

  raise notice 'watch_progress delta pull OK';
end $$;

-- ============ watch_progress: delete (R6 string-key array; delete events; no resurrection) ============
do $$
declare g uuid := '88888888-8888-8888-8888-888888888888';
        n int; del_events int;
begin
  insert into auth.users(id) values (g) on conflict do nothing;
  perform public.test_login(g);

  perform public.sync_push_watch_progress(
    jsonb_build_array(
      jsonb_build_object('content_id','d1','content_type','movie','video_id','d1',
        'position',1,'duration',9,'last_watched',1,'progress_key','d1'),
      jsonb_build_object('content_id','d2','content_type','movie','video_id','d2',
        'position',1,'duration',9,'last_watched',1,'progress_key','d2')), 1);

  -- p_keys is an array of PLAIN STRINGS (not objects) for watch_progress (R6).
  perform public.sync_delete_watch_progress(jsonb_build_array('d1'), 1);

  select count(*) into n from public.watch_progress where user_id=g;
  assert n = 1, format('after delete, expected 1 row, got %s', n);
  assert not exists (select 1 from public.watch_progress where user_id=g and progress_key='d1'),
    'deleted key must be gone';

  -- A delete event is appended so other devices converge (and the row does not resurrect on pull).
  select count(*) into del_events
    from public.watch_progress_events where user_id=g and operation='delete' and progress_key='d1';
  assert del_events = 1, format('expected 1 delete event, got %s', del_events);
  assert not exists (
    select 1 from public.sync_pull_watch_progress(1, null, null) where progress_key='d1'
  ), 'deleted key must not reappear in a pull';

  raise notice 'watch_progress delete OK';
end $$;

-- ============ watch_progress: function ACLs (MF-2/MF-3) ============
do $$
begin
  -- Inner _for fns must NOT be callable by members (else owner-arg forgery bypasses R1).
  assert not has_function_privilege('authenticated',
    'public.sync_push_watch_progress_for(uuid,int,jsonb)', 'EXECUTE'),
    'push _for must NOT be executable by authenticated';
  assert not has_function_privilege('authenticated',
    'public.sync_delete_watch_progress_for(uuid,int,jsonb)', 'EXECUTE'),
    'delete _for must NOT be executable by authenticated';

  -- The 5 client-facing wrappers MUST be callable by members.
  assert has_function_privilege('authenticated', 'public.sync_push_watch_progress(jsonb,int)', 'EXECUTE'),
    'push wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_watch_progress(int,bigint,int)', 'EXECUTE'),
    'pull wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_get_watch_progress_delta_cursor(int)', 'EXECUTE'),
    'cursor wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_watch_progress_delta(int,bigint,int)', 'EXECUTE'),
    'delta wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_delete_watch_progress(jsonb,int)', 'EXECUTE'),
    'delete wrapper must be executable by authenticated';

  raise notice 'watch_progress ACLs OK';
end $$;

-- ============ watch_progress: RLS read-own (D — defense-in-depth) ============
-- Seed two members' rows via the RPCs (run as the connection role = postgres), then drop to the
-- non-privileged authenticated role and confirm a DIRECT table read is RLS-scoped to auth.uid().
do $$
declare a uuid := 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
        b uuid := 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;
  perform public.test_login(a);
  perform public.sync_push_watch_progress(jsonb_build_array(jsonb_build_object(
    'content_id','ra','content_type','movie','video_id','ra',
    'position',1,'duration',9,'last_watched',1,'progress_key','rls_a')), 1);
  perform public.test_login(b);
  perform public.sync_push_watch_progress(jsonb_build_array(jsonb_build_object(
    'content_id','rb','content_type','movie','video_id','rb',
    'position',1,'duration',9,'last_watched',1,'progress_key','rls_b')), 1);
  perform public.test_login(a);   -- read as A
end $$;

set local role authenticated;     -- non-BYPASSRLS role => the read-own policy is enforced
do $$
declare foreign_n int; own_n int;
begin
  select count(*) into foreign_n from public.watch_progress where progress_key = 'rls_b';
  assert foreign_n = 0, 'RLS must hide member B''s rows from A on a direct table read';
  select count(*) into own_n from public.watch_progress where progress_key = 'rls_a';
  assert own_n >= 1, 'A must see its OWN row under RLS';
  raise notice 'watch_progress RLS read-own OK';
end $$;
reset role;                        -- back to postgres for the remaining blocks

-- ============ watch_progress: NULL-owner safety (R4) ============
do $$
declare before_rows int; after_rows int; cur bigint;
begin
  perform public.test_logout();   -- no JWT => get_sync_owner() is NULL

  select count(*) into before_rows from public.watch_progress;

  -- Push with no session writes NOTHING (no NULL-user_id rows, no exception).
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object('content_id','n','content_type','movie',
      'video_id','n','position',1,'duration',9,'last_watched',1,'progress_key','n')), 1);
  -- Delete with no session is a no-op.
  perform public.sync_delete_watch_progress(jsonb_build_array('n'), 1);

  select count(*) into after_rows from public.watch_progress;
  assert after_rows = before_rows, 'anon push/delete must not change row count';
  assert not exists (select 1 from public.watch_progress where user_id is null), 'no NULL-user_id rows';

  -- Anon pull/cursor return empty/0, never error.
  assert (select count(*) from public.sync_pull_watch_progress(1, null, null)) = 0, 'anon pull must be empty';
  select public.sync_get_watch_progress_delta_cursor(1) into cur;
  assert cur = 0, format('anon cursor must be 0, got %s', cur);

  raise notice 'watch_progress NULL-owner OK';
end $$;

-- ============ watch_progress: idempotent re-apply preserves data ============
do $$
declare s uuid := '99999999-9999-9999-9999-999999999999';
        v_rows int; v_events int;
begin
  insert into auth.users(id) values (s) on conflict do nothing;
  perform public.test_login(s);
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object('content_id','sent','content_type','movie',
      'video_id','sent','position',7,'duration',70,'last_watched',7,'progress_key','idemp_sentinel')), 1);
end $$;

-- re-apply the whole setup mid-test (create-if-not-exists / or-replace).
-- NOTE: keep the comment off the \i line — psql parses a trailing inline comment as extra
-- \i arguments and emits noisy "extra argument ignored" warnings.
\i watch_progress_setup.sql

do $$
declare s uuid := '99999999-9999-9999-9999-999999999999';
begin
  perform public.test_login(s);   -- re-establish session (set_config is txn-local; new block)
  assert to_regclass('public.watch_progress') is not null, 're-apply dropped the table';
  assert (select count(*) from public.watch_progress where user_id=s and progress_key='idemp_sentinel') = 1,
    're-applying setup must PRESERVE existing rows (no drop-then-create)';
  assert (select count(*) from public.watch_progress_events where user_id=s and progress_key='idemp_sentinel') = 1,
    're-applying setup must preserve existing events';
  raise notice 'watch_progress idempotency OK';
end $$;
