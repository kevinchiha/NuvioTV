-- ============ watched_items: schema ============
do $$
begin
  assert to_regclass('public.watched_items') is not null, 'watched_items table missing';
  assert to_regclass('public.watched_items_events') is not null, 'watched_items_events table missing';

  -- R3/R6: NULL-aware composite unique key = the ON CONFLICT upsert target.
  assert exists (
    select 1 from pg_constraint
    where conrelid = 'public.watched_items'::regclass and contype = 'u'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute
           where attrelid = 'public.watched_items'::regclass and attnum = any(conkey))
          = array['content_id','episode','profile_id','season','user_id']
  ), 'watched_items needs UNIQUE(user_id, profile_id, content_id, season, episode)';
  -- The unique index must be NULLS NOT DISTINCT (else movies with NULL season/episode duplicate).
  assert exists (
    select 1 from pg_index i join pg_constraint c on c.conindid = i.indexrelid
    where c.conrelid = 'public.watched_items'::regclass and c.contype = 'u'
      and i.indnullsnotdistinct
  ), 'watched_items unique key must be NULLS NOT DISTINCT';

  -- RLS on both tables.
  assert (select relrowsecurity from pg_class where oid='public.watched_items'::regclass), 'RLS off on watched_items';
  assert (select relrowsecurity from pg_class where oid='public.watched_items_events'::regclass), 'RLS off on watched_items_events';

  -- Writes are RPC-only; SELECT scoped to authenticated (RLS), none for anon.
  assert not has_table_privilege('authenticated','public.watched_items','INSERT'), 'authenticated can INSERT watched_items';
  assert not has_table_privilege('authenticated','public.watched_items','UPDATE'), 'authenticated can UPDATE watched_items';
  assert not has_table_privilege('authenticated','public.watched_items','DELETE'), 'authenticated can DELETE watched_items';
  assert has_table_privilege('authenticated','public.watched_items','SELECT'), 'authenticated needs RLS-scoped SELECT';
  assert not has_table_privilege('anon','public.watched_items','SELECT'), 'anon must not SELECT watched_items';

  raise notice 'watched_items schema OK';
end $$;

-- ============ watched_items: push (R2 watched_at guard, R3/R6 NULL-aware dedup) ============
do $$
declare a uuid := '22222222-aaaa-2222-2222-222222222222';
        v_at bigint; v_events int;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);

  -- R3/R6 CRITICAL: a movie pushed twice (season/episode = explicit NULL) must DEDUPE to ONE row.
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','mov1','content_type','movie','title','M',
    'season', null, 'episode', null, 'watched_at', 1000)), 1);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','mov1','content_type','movie','title','M2',
    'season', null, 'episode', null, 'watched_at', 2000)), 1);
  assert (select count(*) from public.watched_items where user_id=a and content_id='mov1') = 1,
    'movie (NULL season/episode) must dedupe to ONE row via nulls-not-distinct key';
  select watched_at into v_at from public.watched_items where user_id=a and content_id='mov1';
  assert v_at = 2000, format('newer watched_at must win; got %s', v_at);

  -- Distinct episodes of the same series are DISTINCT rows.
  perform public.sync_push_watched_items(jsonb_build_array(
    jsonb_build_object('content_id','s1','content_type','series','title','S',
      'season',1,'episode',1,'watched_at',10),
    jsonb_build_object('content_id','s1','content_type','series','title','S',
      'season',1,'episode',2,'watched_at',10)), 1);
  assert (select count(*) from public.watched_items where user_id=a and content_id='s1') = 2,
    'distinct episodes must be distinct rows';

  -- R2: a STALE watched_at push must NOT regress and must NOT append an event.
  select count(*) into v_events from public.watched_items_events where user_id=a;
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','mov1','content_type','movie','title','OLD',
    'season', null, 'episode', null, 'watched_at', 1)), 1);
  select watched_at into v_at from public.watched_items where user_id=a and content_id='mov1';
  assert v_at = 2000, format('stale push must not regress; got %s', v_at);
  assert (select count(*) from public.watched_items_events where user_id=a) = v_events,
    'stale push must not append an event';

  -- R2 boundary: an EQUAL watched_at push must NOT update or append (guard is ">", not ">=").
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','mov1','content_type','movie','title','EQUAL',
    'season', null, 'episode', null, 'watched_at', 2000)), 1);
  assert (select title from public.watched_items where user_id=a and content_id='mov1') = 'M2',
    'equal-watched_at push must not update the row';
  assert (select count(*) from public.watched_items_events where user_id=a) = v_events,
    'equal-watched_at push must not append an event';

  raise notice 'watched_items push OK';
end $$;

-- ============ watched_items: pull (R1 isolation, R7 exact shape, R8 1-based paging) ============
do $$
declare a uuid := '33333333-aaaa-3333-3333-333333333333';
        b uuid := '44444444-aaaa-4444-4444-444444444444';
        v_count int; v_shape text; v_keys text;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  perform public.test_login(a);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','wa','content_type','movie','title','A','season',null,'episode',null,'watched_at',1000)), 1);
  perform public.test_login(b);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','wb','content_type','movie','title','B','season',null,'episode',null,'watched_at',1000)), 1);

  -- R1: member B pulls only B's rows, never A's. (page 1, page_size 900)
  select count(*), string_agg(content_id, ',' order by content_id) into v_count, v_keys
    from public.sync_pull_watched_items(1, 1, 900);
  assert v_count = 1, format('B should pull 1 row, got %s', v_count);
  assert v_keys = 'wb', format('B must not see A''s rows; got %s', v_keys);

  -- R7 wire-shape guard: pulled row JSON keys must EXACTLY match SupabaseWatchedItem's emitted set
  -- (no id, no updated_at). LIMIT the ROW first, THEN expand keys.
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_watched_items(1, 1, 900) limit 1 ) t ) s;
  assert v_shape = 'content_id,content_type,episode,profile_id,season,title,user_id,watched_at',
    format('pull row JSON keys must match SupabaseWatchedItem exactly; got: %s', v_shape);
end $$;

-- R8 1-based paging: page 2 continues where page 1 stopped, deterministic order, no dup/drop.
do $$
declare d uuid := '55555555-aaaa-5555-5555-555555555555';
        p1 text; p2 text; tot int;
begin
  insert into auth.users(id) values (d) on conflict do nothing;
  perform public.test_login(d);
  for i in 1..5 loop
    perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
      'content_id','p'||i,'content_type','series','title','P','season',1,'episode',i,'watched_at',i)), 1);
  end loop;
  -- page_size 2: page 1 = 2 rows, page 2 = next 2 rows, disjoint, ordered.
  select string_agg(content_id||':'||episode, ',' order by watched_at, content_id) into p1
    from public.sync_pull_watched_items(1, 1, 2);
  select string_agg(content_id||':'||episode, ',' order by watched_at, content_id) into p2
    from public.sync_pull_watched_items(1, 2, 2);
  assert (select count(*) from public.sync_pull_watched_items(1, 1, 2)) = 2, 'page 1 must have 2 rows';
  assert (select count(*) from public.sync_pull_watched_items(1, 2, 2)) = 2, 'page 2 must have 2 rows';
  assert p1 <> p2, 'pages must be disjoint';
  assert not exists (
    select content_id from public.sync_pull_watched_items(1, 1, 2)
    intersect
    select content_id from public.sync_pull_watched_items(1, 2, 2)
  ), 'paging must not overlap (R8 stable order)';
  raise notice 'watched_items pull OK';
end $$;

-- ============ watched_items: delta cursor (R5 coalesce, never NULL — client is UNWRAPPED) ============
do $$
declare z uuid := '66666666-aaaa-6666-6666-666666666666';
        c1 bigint; c2 bigint; v_max bigint;
begin
  insert into auth.users(id) values (z) on conflict do nothing;
  perform public.test_login(z);

  -- Brand-new member, zero events: MUST return 0 (the client decodes a non-null Long and does NOT
  -- wrap this call — a NULL breaks watched-history restore outright, spec §5.2/R5).
  select public.sync_get_watched_items_delta_cursor(1) into c1;
  assert c1 = 0, format('empty cursor must be 0, got %s', c1);

  -- After a push, the cursor equals this owner's own max event_id.
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','cc','content_type','movie','title','C','season',null,'episode',null,'watched_at',5)), 1);
  select public.sync_get_watched_items_delta_cursor(1) into c2;
  select max(event_id) into v_max from public.watched_items_events where user_id=z;
  assert c2 = v_max, format('cursor must equal owner max event_id %s, got %s', v_max, c2);

  raise notice 'watched_items delta cursor OK';
end $$;

-- ============ watched_items: delta pull (R1 owner, R7 shape, R8 asc+limit) ============
do $$
declare d uuid := '77777777-aaaa-7777-7777-777777777777';
        e uuid := '88888888-aaaa-8888-8888-888888888888';
        n int; first_id bigint; last_id bigint; v_shape text;
begin
  insert into auth.users(id) values (d),(e) on conflict do nothing;

  perform public.test_login(e);   -- noise from another member must never appear in d's delta
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','noise','content_type','movie','title','N','season',null,'episode',null,'watched_at',1)), 1);

  perform public.test_login(d);
  for i in 1..3 loop
    perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
      'content_id','k'||i,'content_type','series','title','K','season',1,'episode',i,'watched_at',i)), 1);
  end loop;

  -- From cursor 0, limit 2: exactly 2 of d's events, ascending, none of e's.
  select count(*), min(event_id), max(event_id) into n, first_id, last_id
    from public.sync_pull_watched_items_delta(1, 0, 2);
  assert n = 2, format('expected 2 delta rows, got %s', n);
  assert first_id < last_id, 'delta rows must be ascending by event_id';
  assert not exists (
    select 1 from public.sync_pull_watched_items_delta(1, 0, 100) where content_id='noise'
  ), 'd must never see member e''s events';

  -- R7 delta wire-shape: exact SupabaseWatchedItemEvent key set.
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_watched_items_delta(1, 0, 1) limit 1 ) t ) s;
  assert v_shape = 'content_id,content_type,episode,event_id,operation,season,title,watched_at',
    format('delta row JSON keys must match SupabaseWatchedItemEvent exactly; got: %s', v_shape);

  raise notice 'watched_items delta pull OK';
end $$;

-- ============ watched_items: delete (R6 object keys + IS NOT DISTINCT FROM; delete events) ============
do $$
declare g uuid := '99999999-aaaa-9999-9999-999999999999';
        n int; del_ct text; del_at bigint; del_events int;
begin
  insert into auth.users(id) values (g) on conflict do nothing;
  perform public.test_login(g);

  -- one movie (NULL season/episode) + two episodes of a series.
  perform public.sync_push_watched_items(jsonb_build_array(
    jsonb_build_object('content_id','dm','content_type','movie','title','DM','season',null,'episode',null,'watched_at',1),
    jsonb_build_object('content_id','ds','content_type','series','title','DS','season',1,'episode',1,'watched_at',1),
    jsonb_build_object('content_id','ds','content_type','series','title','DS','season',1,'episode',2,'watched_at',1)), 1);

  -- R6: delete the MOVIE with a content_id-ONLY key (season/episode omitted => NULL =>
  --     IS NOT DISTINCT FROM matches the NULL-season movie row). p_profile_id is FIRST.
  perform public.sync_delete_watched_items(1, jsonb_build_array(jsonb_build_object('content_id','dm')));
  assert not exists (select 1 from public.watched_items where user_id=g and content_id='dm'),
    'movie must be deleted via content_id-only key (IS NOT DISTINCT FROM NULL)';

  -- The delete event carries the deleted row's real content_type and zeroed watched_at (R7).
  select content_type, watched_at into del_ct, del_at
    from public.watched_items_events
    where user_id=g and operation='delete' and content_id='dm' order by event_id desc limit 1;
  assert del_ct = 'movie', format('delete event content_type must be the deleted row''s; got %s', del_ct);
  assert del_at = 0, format('delete event watched_at must be zeroed; got %s', del_at);

  -- R6: delete ONE episode by {content_id, season, episode}; the other episode survives.
  perform public.sync_delete_watched_items(1,
    jsonb_build_array(jsonb_build_object('content_id','ds','season',1,'episode',1)));
  select count(*) into n from public.watched_items where user_id=g and content_id='ds';
  assert n = 1, format('only s1e1 deleted; expected 1 episode left, got %s', n);
  assert exists (select 1 from public.watched_items where user_id=g and content_id='ds' and episode=2),
    's1e2 must survive';

  -- A pull must not resurrect the deleted rows.
  assert not exists (select 1 from public.sync_pull_watched_items(1,1,900) where content_id='dm'),
    'deleted movie must not reappear in a pull';

  select count(*) into del_events from public.watched_items_events where user_id=g and operation='delete';
  assert del_events = 2, format('expected 2 delete events (movie + s1e1), got %s', del_events);

  raise notice 'watched_items delete OK';
end $$;

-- ============ watched_items: function ACLs ============
do $$
begin
  assert not has_function_privilege('authenticated',
    'public.sync_push_watched_items_for(uuid,int,jsonb)', 'EXECUTE'),
    'push _for must NOT be executable by authenticated';
  assert not has_function_privilege('authenticated',
    'public.sync_delete_watched_items_for(uuid,int,jsonb)', 'EXECUTE'),
    'delete _for must NOT be executable by authenticated';

  assert has_function_privilege('authenticated', 'public.sync_push_watched_items(jsonb,int)', 'EXECUTE'),
    'push wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_watched_items(int,int,int)', 'EXECUTE'),
    'pull wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_get_watched_items_delta_cursor(int)', 'EXECUTE'),
    'cursor wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_watched_items_delta(int,bigint,int)', 'EXECUTE'),
    'delta wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_delete_watched_items(int,jsonb)', 'EXECUTE'),
    'delete wrapper must be executable by authenticated';

  raise notice 'watched_items ACLs OK';
end $$;

-- ============ watched_items: RLS read-own (D — defense-in-depth) ============
do $$
declare a uuid := 'aaaaaaaa-bbbb-aaaa-aaaa-aaaaaaaaaaaa';
        b uuid := 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;
  perform public.test_login(a);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','rls_a','content_type','movie','title','A','season',null,'episode',null,'watched_at',1)), 1);
  perform public.test_login(b);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','rls_b','content_type','movie','title','B','season',null,'episode',null,'watched_at',1)), 1);
  perform public.test_login(a);
end $$;

set local role authenticated;
do $$
declare foreign_n int; own_n int;
begin
  select count(*) into foreign_n from public.watched_items where content_id='rls_b';
  assert foreign_n = 0, 'RLS must hide member B''s rows from A on a direct table read';
  select count(*) into own_n from public.watched_items where content_id='rls_a';
  assert own_n >= 1, 'A must see its OWN row under RLS';
  raise notice 'watched_items RLS read-own OK';
end $$;
reset role;

-- ============ watched_items: NULL-owner safety (R4) ============
do $$
declare before_rows int; after_rows int; cur bigint;
begin
  perform public.test_logout();   -- no JWT => get_sync_owner() is NULL
  select count(*) into before_rows from public.watched_items;

  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','n','content_type','movie','title','N','season',null,'episode',null,'watched_at',1)), 1);
  perform public.sync_delete_watched_items(1, jsonb_build_array(jsonb_build_object('content_id','n')));

  select count(*) into after_rows from public.watched_items;
  assert after_rows = before_rows, 'anon push/delete must not change row count';
  assert not exists (select 1 from public.watched_items where user_id is null), 'no NULL-user_id rows';

  assert (select count(*) from public.sync_pull_watched_items(1, 1, 900)) = 0, 'anon pull must be empty';
  select public.sync_get_watched_items_delta_cursor(1) into cur;
  assert cur = 0, format('anon cursor must be 0, got %s', cur);

  raise notice 'watched_items NULL-owner OK';
end $$;

-- ============ watched_items: idempotent re-apply preserves data ============
do $$
declare s uuid := 'ffffffff-aaaa-ffff-ffff-ffffffffffff';
begin
  insert into auth.users(id) values (s) on conflict do nothing;
  perform public.test_login(s);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','idemp','content_type','movie','title','SENT','season',null,'episode',null,'watched_at',7)), 1);
end $$;

-- re-apply the whole setup mid-test (create-if-not-exists / or-replace).
-- NOTE: keep the comment off the \i line — psql parses a trailing inline comment as extra
-- \i arguments and emits noisy "extra argument ignored" warnings.
\i watched_items_setup.sql

do $$
declare s uuid := 'ffffffff-aaaa-ffff-ffff-ffffffffffff';
begin
  perform public.test_login(s);
  assert to_regclass('public.watched_items') is not null, 're-apply dropped the table';
  assert (select count(*) from public.watched_items where user_id=s and content_id='idemp') = 1,
    're-applying setup must PRESERVE existing rows (no drop-then-create)';
  assert (select count(*) from public.watched_items_events where user_id=s and content_id='idemp') = 1,
    're-applying setup must preserve existing events';
  raise notice 'watched_items idempotency OK';
end $$;
