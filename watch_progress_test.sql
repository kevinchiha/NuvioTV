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
