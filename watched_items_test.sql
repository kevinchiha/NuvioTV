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
