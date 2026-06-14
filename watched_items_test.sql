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
