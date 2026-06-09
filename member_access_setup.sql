-- KevBox TV — member_access (remote enable/disable kill-switch) setup. Run ONCE against the
-- KevBox Supabase project (scmqdptagksltnwiveyh). Idempotent — safe to re-run. No secrets here.
-- Plan: plans/MEMBER-ACCESS-PLAN.md. Sibling: member_device_setup.sql (one-device-per-member limit).
--
-- Model: one row per member, `active boolean`. Disable (active=false) to lock a member out within a
-- few minutes; re-enable to restore. We DISABLE, never delete — a deleted row reads as NO_ROW which
-- the client treats as fail-open (allowed). The app reads the verdict ONLY via get_access_verdict().

-- 1. Table -----------------------------------------------------------------------
create table if not exists public.member_access (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  active     boolean not null default true,
  updated_at timestamptz not null default now()
);
alter table public.member_access enable row level security;

-- 2. RLS: a member may READ only their own row. No write policy → dashboard/service_role only.
drop policy if exists "read own access" on public.member_access;
create policy "read own access"
  on public.member_access for select
  using (auth.uid() = user_id);

-- 2b. Least-privilege grants. Supabase grants ALL DML to anon/authenticated by default; RLS gates
--     SELECT to own rows, but revoke the write/truncate grants so the table can't be mutated via the
--     REST API regardless of any future policy mistake (mirrors member_addon_setup.sql).
revoke insert, update, delete, truncate, references, trigger
  on public.member_access from anon, authenticated;

-- 2c. Admin role grant. The kevbox-admin app connects as role kevbox_admin (BYPASSRLS), which skips
--     RLS POLICIES but NOT table-level GRANTs — so the explicit grant below is required for the admin
--     to manage the kill-switch (mirrors member_addon_setup.sql). Idempotent; safe to re-run.
grant select, insert, update, delete on public.member_access to kevbox_admin;

-- 3. Authoritative verdict RPC — the ONLY read path the app uses ------------------
--    SECURITY DEFINER + pinned search_path. Resolves auth.uid() from the JWT (tamper-proof) and
--    returns a 3-state result. RAISES on an unauthenticated caller so the client routes it to
--    grace/UNKNOWN instead of fail-open. (A raw table SELECT returns an empty list on an
--    expired/missing JWT — indistinguishable from "legacy member, no row" — which would fail open
--    and never lock the member exactly when you disable them. The RPC closes that hole.)
create or replace function public.get_access_verdict()
  returns text language plpgsql security definer set search_path = '' as $$
declare
  v_uid    uuid := auth.uid();
  v_active boolean;
begin
  if v_uid is null then
    raise exception 'not authenticated';   -- expired/missing JWT → client treats as UNKNOWN (grace)
  end if;
  select active into v_active from public.member_access where user_id = v_uid;
  if not found then
    return 'NO_ROW';                        -- legacy/unseeded but signed-in → client fail-open (allowed)
  elsif v_active then
    return 'ALLOWED';
  else
    return 'LOCKED';
  end if;
end $$;
revoke all on function public.get_access_verdict() from public, anon;
grant execute on function public.get_access_verdict() to authenticated;

-- 4. Auto-seed every new sign-up as active. SECURITY DEFINER pins search_path.
--    NOTE: member_addon_setup.sql already adds an AFTER INSERT trigger on auth.users
--    (on_member_addon_seed). Both fire on sign-up; both are SECURITY DEFINER with
--    `on conflict do nothing`, so an error in either rolls back the sign-up — verify sign-up still
--    works after applying this (see Rollback below if it does not).
create or replace function public.seed_member_access()
  returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.member_access (user_id) values (new.id) on conflict do nothing;
  return new;
end $$;
drop trigger if exists on_auth_user_created_seed_access on auth.users;
create trigger on_auth_user_created_seed_access
  after insert on auth.users for each row execute function public.seed_member_access();

-- 5. One-time back-fill for existing members.
insert into public.member_access (user_id) select id from auth.users on conflict do nothing;

-- Sanity check
select count(*) as member_access_rows from public.member_access;

-- ============================================================================================
-- Admin operations (interim — SQL editor; integrate into kevbox-admin):
--   Disable:   update public.member_access set active=false, updated_at=now() where user_id='<uuid>';
--   Re-enable: update public.member_access set active=true,  updated_at=now() where user_id='<uuid>';
--   Always DISABLE rather than delete — a deleted row reads as NO_ROW → fail-open (allowed).
--
-- ⚠️ Do NOT expose member_access through a plain view: a view bypasses RLS and Supabase grants ALL to
--   anon/authenticated by default, leaking every member's flag. If you ever must, create it
--   `with (security_invoker = on)` and `revoke all ... from anon, authenticated`.
-- ============================================================================================

-- ============================================================================================
-- ROLLBACK — "kill the kill-switch" (paste the relevant statement; no app re-sideload needed):
--
--   -- Globally unlock everyone instantly (next client check) by overriding the RPC to always allow:
--   create or replace function public.get_access_verdict() returns text language sql as $$ select 'ALLOWED' $$;
--   -- Restore the real verdict body (re-run section 3 above) to re-enable enforcement.
--
--   -- Unlock a stuck fleet's flags:
--   update public.member_access set active=true, updated_at=now();
--
--   -- Stop the seed trigger if it is blocking new sign-ups:
--   drop trigger if exists on_auth_user_created_seed_access on auth.users;
-- ============================================================================================
