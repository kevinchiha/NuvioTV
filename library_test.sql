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

-- ============ library: push (upsert; genres text[]; imdb_rating real; omitted-null handling) ============
do $$
declare a uuid := '11111111-bbbb-1111-1111-111111111111';
        v_name text; v_rating real; v_genres text[]; v_rating2 real; n int;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);

  -- Full item with imdb_rating + genres array.
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','lib1','content_type','movie','name','Film','poster','p','poster_shape','POSTER',
    'background','bg','description','d','release_info','2024',
    'imdb_rating', 7.5, 'genres', jsonb_build_array('Action','Drama'),
    'addon_base_url','http://a','added_at', 100)), 1);
  select name, imdb_rating, genres into v_name, v_rating, v_genres
    from public.library where user_id=a and content_id='lib1';
  assert v_name = 'Film', format('name mismatch: %s', v_name);
  assert v_rating = 7.5::real, format('imdb_rating mismatch: %s', v_rating);
  assert v_genres = array['Action','Drama'], format('genres mismatch: %s', v_genres::text);

  -- Item with imdb_rating OMITTED (client drops the key when null) => stored NULL.
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','lib2','content_type','series','name','Show','poster_shape','POSTER',
    'genres', jsonb_build_array(), 'added_at', 50)), 1);
  select imdb_rating into v_rating2 from public.library where user_id=a and content_id='lib2';
  assert v_rating2 is null, format('omitted imdb_rating must store NULL; got %s', v_rating2);
  assert (select genres from public.library where user_id=a and content_id='lib2') = '{}'::text[],
    'empty genres array must store {}';

  -- R3: re-push same content_id updates the SAME row (snapshot last-write-wins).
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','lib1','content_type','movie','name','Film v2','poster_shape','POSTER',
    'genres', jsonb_build_array('Action'), 'added_at', 200)), 1);
  select count(*), max(name) into n, v_name from public.library where user_id=a and content_id='lib1';
  assert n = 1, format('re-push must not duplicate; got %s rows', n);
  assert v_name = 'Film v2', format('re-push must overwrite name; got %s', v_name);

  raise notice 'library push OK';
end $$;
