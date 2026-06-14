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

-- ============ library: pull (R1 isolation, R7 exact 14-key shape, genres JSON array, R8 offset) ============
do $$
declare a uuid := '22222222-bbbb-2222-2222-222222222222';
        b uuid := '33333333-bbbb-3333-3333-333333333333';
        v_count int; v_keys text; v_shape text; v_genres_json text; v_rating_json jsonb;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  perform public.test_login(a);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','la','content_type','movie','name','LA','poster_shape','POSTER',
    'imdb_rating', 8.1, 'genres', jsonb_build_array('Sci-Fi','Thriller'), 'added_at', 10)), 1);
  perform public.test_login(b);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','lb','content_type','movie','name','LB','poster_shape','POSTER',
    'genres', jsonb_build_array(), 'added_at', 10)), 1);

  -- R1: member B pulls only B's rows.
  select count(*), string_agg(content_id, ',') into v_count, v_keys
    from public.sync_pull_library(1, 500, 0);
  assert v_count = 1, format('B should pull 1 row, got %s', v_count);
  assert v_keys = 'lb', format('B must not see A''s rows; got %s', v_keys);

  -- R7 wire-shape guard: exact 14-key SupabaseLibraryItem emitted set (id omitted).
  perform public.test_login(a);
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_library(1, 500, 0) limit 1 ) t ) s;
  assert v_shape = 'added_at,addon_base_url,background,content_id,content_type,description,genres,imdb_rating,name,poster,poster_shape,profile_id,release_info,user_id',
    format('pull row JSON keys must match SupabaseLibraryItem exactly; got: %s', v_shape);

  -- genres must serialize as a JSON ARRAY of strings (decodes to List<String>), not a Postgres
  -- array literal string; imdb_rating must serialize as a JSON number (decodes to Float?).
  select to_jsonb(t)->>'genres', to_jsonb(t)->'imdb_rating' into v_genres_json, v_rating_json
  from ( select * from public.sync_pull_library(1, 500, 0) where content_id='la' limit 1 ) t;
  assert v_genres_json = '["Sci-Fi", "Thriller"]',
    format('genres must be a JSON array; got: %s', v_genres_json);
  assert v_rating_json = '8.1'::jsonb, format('imdb_rating must be a JSON number; got: %s', v_rating_json);

  raise notice 'library pull OK';
end $$;

-- R8 offset paging: two pages of size 1 are disjoint, deterministic.
do $$
declare d uuid := '44444444-bbbb-4444-4444-444444444444';
begin
  insert into auth.users(id) values (d) on conflict do nothing;
  perform public.test_login(d);
  for i in 1..3 loop
    perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
      'content_id','pg'||i,'content_type','movie','name','PG','poster_shape','POSTER',
      'genres', jsonb_build_array(), 'added_at', i)), 1);
  end loop;
  assert (select count(*) from public.sync_pull_library(1, 2, 0)) = 2, 'limit 2 offset 0 => 2 rows';
  assert (select count(*) from public.sync_pull_library(1, 2, 2)) = 1, 'limit 2 offset 2 => 1 row';
  assert not exists (
    select content_id from public.sync_pull_library(1, 2, 0)
    intersect
    select content_id from public.sync_pull_library(1, 2, 2)
  ), 'offset paging must not overlap (R8 stable order)';
  raise notice 'library paging OK';
end $$;

-- ============ library: function ACLs + NULL-owner + RLS read-own ============
do $$
begin
  assert not has_function_privilege('authenticated', 'public.sync_push_library_for(uuid,int,jsonb)', 'EXECUTE'),
    'push _for must NOT be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_push_library(jsonb,int)', 'EXECUTE'),
    'push wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_library(int,int,int)', 'EXECUTE'),
    'pull wrapper must be executable by authenticated';
  raise notice 'library ACLs OK';
end $$;

-- RLS read-own.
do $$
declare a uuid := 'aaaaaaaa-cccc-aaaa-aaaa-aaaaaaaaaaaa';
        b uuid := 'bbbbbbbb-cccc-bbbb-bbbb-bbbbbbbbbbbb';
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;
  perform public.test_login(a);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','rls_la','content_type','movie','name','A','poster_shape','POSTER',
    'genres', jsonb_build_array(), 'added_at', 1)), 1);
  perform public.test_login(b);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','rls_lb','content_type','movie','name','B','poster_shape','POSTER',
    'genres', jsonb_build_array(), 'added_at', 1)), 1);
  perform public.test_login(a);
end $$;
set local role authenticated;
do $$
begin
  assert (select count(*) from public.library where content_id='rls_lb') = 0,
    'RLS must hide member B''s library from A';
  assert (select count(*) from public.library where content_id='rls_la') >= 1, 'A must see its own library';
  raise notice 'library RLS read-own OK';
end $$;
reset role;

-- NULL-owner safety (R4).
do $$
declare before_rows int; after_rows int;
begin
  perform public.test_logout();
  select count(*) into before_rows from public.library;
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','n','content_type','movie','name','N','poster_shape','POSTER',
    'genres', jsonb_build_array(), 'added_at', 1)), 1);
  select count(*) into after_rows from public.library;
  assert after_rows = before_rows, 'anon push must not change row count';
  assert not exists (select 1 from public.library where user_id is null), 'no NULL-user_id rows';
  assert (select count(*) from public.sync_pull_library(1, 500, 0)) = 0, 'anon pull must be empty';
  raise notice 'library NULL-owner OK';
end $$;

-- ============ library: idempotent re-apply preserves data ============
do $$
declare s uuid := 'ffffffff-cccc-ffff-ffff-ffffffffffff';
begin
  insert into auth.users(id) values (s) on conflict do nothing;
  perform public.test_login(s);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','idemp','content_type','movie','name','SENT','poster_shape','POSTER',
    'genres', jsonb_build_array('G'), 'added_at', 9)), 1);
end $$;

-- re-apply the whole setup mid-test (comment kept off the \i line — see Task 8 note).
\i library_setup.sql

do $$
declare s uuid := 'ffffffff-cccc-ffff-ffff-ffffffffffff';
begin
  perform public.test_login(s);
  assert (select count(*) from public.library where user_id=s and content_id='idemp') = 1,
    're-applying setup must PRESERVE existing rows';
  raise notice 'library idempotency OK';
end $$;
