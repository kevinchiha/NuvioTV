-- KevBox TV — one-device-per-member limit setup. Run ONCE against the KevBox Supabase project
-- (scmqdptagksltnwiveyh). Idempotent — safe to re-run. No secrets here.
-- Plan: plans/MEMBER-ACCESS-PLAN.md (Extension). Sibling: member_access_setup.sql (kill-switch).
--
-- Model: KevBox uses ONE account per member; in-app profiles live within an account and do NOT consume
-- device slots. The limit is account-wide, keyed on auth.uid(). Default max_devices = 1 (one TV per
-- account). member_device is populated lazily on first claim — no backfill. Writes happen ONLY via the
-- claim_device() RPC; members have read-only RLS access to their own rows.

-- 1. Device registry — one row per (member, device). Populated lazily by claim_device().
create table if not exists public.member_device (
  user_id     uuid not null references auth.users(id) on delete cascade,
  device_id   text not null,
  device_name text,
  first_seen  timestamptz not null default now(),
  last_seen   timestamptz not null default now(),
  primary key (user_id, device_id)
);
alter table public.member_device enable row level security;
drop policy if exists "read own devices" on public.member_device;
create policy "read own devices"
  on public.member_device for select
  using (auth.uid() = user_id);   -- writes happen only via claim_device()
revoke insert, update, delete, truncate, references, trigger
  on public.member_device from anon, authenticated;

-- 2. Per-member device cap. Missing row => limit of 1.
create table if not exists public.member_device_policy (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  max_devices int not null default 1,
  updated_at  timestamptz not null default now()
);
alter table public.member_device_policy enable row level security;
drop policy if exists "read own policy" on public.member_device_policy;
create policy "read own policy"
  on public.member_device_policy for select
  using (auth.uid() = user_id);
revoke insert, update, delete, truncate, references, trigger
  on public.member_device_policy from anon, authenticated;

-- 3. Atomic claim-or-deny RPC -----------------------------------------------------
--    SECURITY DEFINER + pinned search_path; uid taken from the JWT (tamper-proof). The advisory lock
--    serializes concurrent claims for the same member so two devices can't both pass the count check
--    at once (TOCTOU). Idempotent: re-claiming an already-bound device returns true and refreshes it.
create or replace function public.claim_device(p_device_id text, p_device_name text default null)
  returns boolean language plpgsql security definer set search_path = '' as $$
declare
  v_uid   uuid := auth.uid();
  v_limit int;
  v_count int;
begin
  if v_uid is null then
    return false;                                   -- unauthenticated: client treats as grace/unknown
  end if;

  perform pg_advisory_xact_lock(hashtextextended(v_uid::text, 0));  -- auto-released at txn end

  -- Already this member's device? refresh and allow.
  update public.member_device
     set last_seen = now(), device_name = coalesce(p_device_name, device_name)
   where user_id = v_uid and device_id = p_device_id;
  if found then return true; end if;

  select coalesce((select max_devices from public.member_device_policy where user_id = v_uid), 1)
    into v_limit;
  select count(*) into v_count from public.member_device where user_id = v_uid;
  if v_count >= v_limit then
    return false;                                   -- over limit: deny this new device
  end if;

  insert into public.member_device (user_id, device_id, device_name)
       values (v_uid, p_device_id, p_device_name)
  on conflict (user_id, device_id) do update set last_seen = now();
  return true;
end $$;
revoke all on function public.claim_device(text, text) from public, anon;
grant execute on function public.claim_device(text, text) to authenticated;

-- Sanity check
select count(*) as member_device_rows from public.member_device;

-- ============================================================================================
-- Admin operations (interim — SQL editor; integrate into kevbox-admin):
--   See a member's devices:
--     select * from public.member_device where user_id='<uuid>';
--     (identical KevBox models share device_name; use first_seen/last_seen to spot the stale one.)
--   Allow a 2nd device:
--     insert into public.member_device_policy(user_id,max_devices) values('<uuid>',2)
--       on conflict (user_id) do update set max_devices=2, updated_at=now();
--   Approve a replacement TV (free the slot so the new TV can re-claim):
--     delete from public.member_device where user_id='<uuid>' and device_id='<old>';
-- ============================================================================================

-- ============================================================================================
-- ROLLBACK — globally disable the device limit instantly (next client check), no app re-sideload:
--   create or replace function public.claim_device(text, text) returns boolean language sql as $$ select true $$;
--   -- Restore the real body (re-run section 3 above) to re-enable enforcement.
-- ============================================================================================
