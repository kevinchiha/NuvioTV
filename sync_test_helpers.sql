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

-- Simulate a logged-in member for the current transaction (set_config local=true).
create or replace function public.test_login(p_user uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', p_user::text)::text, true)
$$;

create or replace function public.test_logout() returns void language sql as $$
  select set_config('request.jwt.claims', '', true)
$$;
