-- ============ profiles + profile_locks: schema ============
do $$
begin
  assert to_regclass('public.profiles') is not null, 'profiles table missing';
  assert to_regclass('public.profile_locks') is not null, 'profile_locks table missing';

  -- R3: profiles PK = (user_id, profile_index); profile_locks PK = (user_id, profile_index).
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.profiles'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.profiles'::regclass and attnum=any(conkey))
          = array['profile_index','user_id']
  ), 'profiles PK must be (user_id, profile_index)';
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.profile_locks'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.profile_locks'::regclass and attnum=any(conkey))
          = array['profile_index','user_id']
  ), 'profile_locks PK must be (user_id, profile_index)';

  -- profile_index is a non-null int (the one required model field).
  assert (select attnotnull from pg_attribute where attrelid='public.profiles'::regclass and attname='profile_index'),
    'profiles.profile_index must be NOT NULL';

  assert (select relrowsecurity from pg_class where oid='public.profiles'::regclass), 'RLS off on profiles';
  assert (select relrowsecurity from pg_class where oid='public.profile_locks'::regclass), 'RLS off on profile_locks';
  assert not has_table_privilege('authenticated','public.profiles','INSERT'), 'authenticated can INSERT profiles';
  assert has_table_privilege('authenticated','public.profiles','SELECT'), 'authenticated needs SELECT on profiles';
  assert not has_table_privilege('anon','public.profiles','SELECT'), 'anon must not SELECT profiles';
  raise notice 'profiles schema OK';
end $$;
