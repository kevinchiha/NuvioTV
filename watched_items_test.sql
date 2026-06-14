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
