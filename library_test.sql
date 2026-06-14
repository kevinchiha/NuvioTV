-- ============ library: schema ============
do $$
begin
  assert to_regclass('public.library') is not null, 'library table missing';

  -- R3: PK = the ON CONFLICT upsert target (user_id, profile_id, content_id).
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.library'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.library'::regclass and attnum=any(conkey))
          = array['content_id','profile_id','user_id']
  ), 'library PK must be (user_id, profile_id, content_id)';

  -- genres is text[]; imdb_rating is nullable real.
  assert (select atttypid::regtype::text from pg_attribute
          where attrelid='public.library'::regclass and attname='genres') = 'text[]',
    'genres must be text[]';
  assert (select atttypid::regtype::text from pg_attribute
          where attrelid='public.library'::regclass and attname='imdb_rating') = 'real',
    'imdb_rating must be real (float4)';

  assert (select relrowsecurity from pg_class where oid='public.library'::regclass), 'RLS off on library';
  assert not has_table_privilege('authenticated','public.library','INSERT'), 'authenticated can INSERT library';
  assert not has_table_privilege('authenticated','public.library','UPDATE'), 'authenticated can UPDATE library';
  assert not has_table_privilege('authenticated','public.library','DELETE'), 'authenticated can DELETE library';
  assert has_table_privilege('authenticated','public.library','SELECT'), 'authenticated needs RLS-scoped SELECT';
  assert not has_table_privilege('anon','public.library','SELECT'), 'anon must not SELECT library';

  raise notice 'library schema OK';
end $$;
