-- BRANCH-DB TEST HELPERS ONLY. Apply to a DISPOSABLE Supabase branch DB.
-- Does NOT create/replace auth.uid(), auth.users, or roles — the branch already has the real
-- Supabase surface. These two helpers set the JWT-claim GUC so the branch's real auth.uid()
-- resolves to a chosen member. Left behind only on the throwaway branch (discarded with it).

-- Fail loud if pointed at a DB that is not a real Supabase target.
do $$
begin
  assert to_regprocedure('auth.uid()') is not null, 'target must have the real auth.uid() (run against a Supabase branch)';
  assert to_regclass('auth.users') is not null, 'target must have the real auth.users';
  assert exists (select 1 from pg_roles where rolname = 'authenticated'), 'target must have the authenticated role';
end $$;

-- Simulate a logged-in member for the current transaction (set_config local=true). ALSO allowlists
-- the member in sync_canary_members so the canary gate in get_sync_owner() resolves them to a real
-- owner — this keeps every existing assert block (and the §9 probe) green. The insert rides the test's
-- begin..rollback, so the branch allowlist stays empty between runs. (To exercise a GATED-OFF member,
-- set request.jwt.claims DIRECTLY instead of calling test_login — see sync_canary_test.sql.)
-- plpgsql (not sql) so the sync_canary_members reference resolves at CALL time, not CREATE time:
-- run_sync_tests.sh applies this helper file BEFORE get_sync_owner_setup.sql creates the table.
create or replace function public.test_login(p_user uuid) returns void language plpgsql as $$
begin
  -- the allowlist FK references auth.users(id); ensure the member exists first so tests that don't
  -- pre-seed auth.users (e.g. get_sync_owner_test.sql) still allowlist cleanly. on conflict => no-op
  -- for the common case where the caller already inserted the row. Rides the test's begin..rollback.
  insert into auth.users(id) values (p_user) on conflict do nothing;
  insert into public.sync_canary_members(user_id) values (p_user) on conflict do nothing;
  perform set_config('request.jwt.claims', json_build_object('sub', p_user::text)::text, true);
end $$;

create or replace function public.test_logout() returns void language sql as $$
  select set_config('request.jwt.claims', '', true)
$$;
