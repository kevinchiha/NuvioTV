-- ============ collections: schema ============
do $$
begin
  assert to_regclass('public.collections') is not null, 'collections table missing';
  -- R3: PK = ON CONFLICT target (user_id, profile_id).
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.collections'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.collections'::regclass and attnum=any(conkey))
          = array['profile_id','user_id']
  ), 'collections PK must be (user_id, profile_id)';
  assert (select atttypid::regtype::text from pg_attribute
          where attrelid='public.collections'::regclass and attname='collections_json') = 'jsonb',
    'collections_json must be jsonb (stringified blob breaks decode)';
  assert (select relrowsecurity from pg_class where oid='public.collections'::regclass), 'RLS off on collections';
  assert not has_table_privilege('authenticated','public.collections','INSERT'), 'authenticated can INSERT collections';
  assert not has_table_privilege('authenticated','public.collections','UPDATE'), 'authenticated can UPDATE collections';
  assert not has_table_privilege('authenticated','public.collections','DELETE'), 'authenticated can DELETE collections';
  assert has_table_privilege('authenticated','public.collections','SELECT'), 'authenticated needs RLS-scoped SELECT';
  assert not has_table_privilege('anon','public.collections','SELECT'), 'anon must not SELECT collections';
  raise notice 'collections schema OK';
end $$;

-- ============ collections: push/pull (R1 isolation, R7 shape, JSON array verbatim, empty-set) ============
do $$
declare a uuid := '11111111-1111-cccc-1111-111111111111';
        b uuid := '22222222-2222-cccc-2222-222222222222';
        v_shape text; v_json jsonb; n int;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  -- empty pull before any push must return ZERO rows (client preserves local), not error.
  perform public.test_login(a);
  assert (select count(*) from public.sync_pull_collections(1)) = 0, 'empty collections pull must be 0 rows';

  -- push a JSON ARRAY blob; pull it back verbatim.
  perform public.sync_push_collections(1, jsonb_build_array(
    jsonb_build_object('id','c1','name','Faves'),
    jsonb_build_object('id','c2','name','Later')));
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','b1','name','B')));  -- noise as... still a
  perform public.test_login(b);
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','bonly','name','Bonly')));

  -- R1: member B pulls only B's row.
  perform public.test_login(b);
  select count(*) into n from public.sync_pull_collections(1);
  assert n = 1, format('B should pull 1 row, got %s', n);
  select collections_json into v_json from public.sync_pull_collections(1);
  assert v_json = jsonb_build_array(jsonb_build_object('id','bonly','name','Bonly')),
    format('B must see only its own collections; got %s', v_json::text);

  -- R3: re-push for A overwrites the SAME row (snapshot last-write-wins).
  perform public.test_login(a);
  select count(*) into n from public.sync_pull_collections(1);
  assert n = 1, format('A must have exactly 1 row after re-push, got %s', n);
  select collections_json into v_json from public.sync_pull_collections(1);
  assert v_json = jsonb_build_array(jsonb_build_object('id','b1','name','B')),
    format('A re-push must overwrite; got %s', v_json::text);
  -- the root must stay a JSON ARRAY (decodes to JsonElement/JsonArray).
  assert jsonb_typeof(v_json) = 'array', format('collections_json must serialize as a JSON array; got %s', jsonb_typeof(v_json));

  -- R7 wire-shape: pulled row JSON keys = exactly SupabaseCollectionBlob's emitted set (no user_id).
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_collections(1) limit 1 ) t ) s;
  assert v_shape = 'collections_json,profile_id,updated_at',
    format('pull row JSON keys must match SupabaseCollectionBlob exactly; got: %s', v_shape);

  raise notice 'collections push/pull OK';
end $$;

-- ============ collections: function ACLs + NULL-owner + RLS read-own ============
do $$
begin
  assert not has_function_privilege('authenticated', 'public.sync_push_collections_for(uuid,int,jsonb)', 'EXECUTE'),
    'push _for must NOT be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_push_collections(int,jsonb)', 'EXECUTE'),
    'push wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_collections(int)', 'EXECUTE'),
    'pull wrapper must be executable by authenticated';
  raise notice 'collections ACLs OK';
end $$;

-- RLS read-own.
do $$
declare a uuid := 'aaaaaaaa-1111-cccc-aaaa-aaaaaaaaaaaa';
        b uuid := 'bbbbbbbb-1111-cccc-bbbb-bbbbbbbbbbbb';
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;
  perform public.test_login(a);
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','rls_a')));
  perform public.test_login(b);
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','rls_b')));
  perform public.test_login(a);
end $$;
set local role authenticated;
do $$
begin
  assert (select count(*) from public.collections where collections_json @> jsonb_build_array(jsonb_build_object('id','rls_b'))) = 0,
    'RLS must hide member B''s collections from A';
  assert (select count(*) from public.collections where collections_json @> jsonb_build_array(jsonb_build_object('id','rls_a'))) >= 1,
    'A must see its own collections';
  raise notice 'collections RLS read-own OK';
end $$;
reset role;

-- NULL-owner safety (R4).
do $$
declare before_rows int; after_rows int;
begin
  perform public.test_logout();
  select count(*) into before_rows from public.collections;
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','n')));
  select count(*) into after_rows from public.collections;
  assert after_rows = before_rows, 'anon push must not change row count';
  assert not exists (select 1 from public.collections where user_id is null), 'no NULL-user_id rows';
  assert (select count(*) from public.sync_pull_collections(1)) = 0, 'anon pull must be empty';
  raise notice 'collections NULL-owner OK';
end $$;

-- ============ collections: idempotent re-apply preserves data ============
do $$
declare s uuid := 'ffffffff-1111-cccc-ffff-ffffffffffff';
begin
  insert into auth.users(id) values (s) on conflict do nothing;
  perform public.test_login(s);
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','idemp')));
end $$;

-- re-apply the whole setup mid-test (comment kept off the \i line — psql parses a trailing
-- inline comment as extra \i arguments and emits noisy "extra argument ignored" warnings).
\ir collections_setup.sql

do $$
declare s uuid := 'ffffffff-1111-cccc-ffff-ffffffffffff';
begin
  perform public.test_login(s);
  assert (select count(*) from public.collections where user_id=s and profile_id=1) = 1,
    're-applying setup must PRESERVE existing rows';
  raise notice 'collections idempotency OK';
end $$;
