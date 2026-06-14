-- KevBox TV — cloud-restore sync owner resolver. Run ONCE against the KevBox Supabase project
-- (scmqdptagksltnwiveyh). Idempotent. No secrets. Shared foundation for ALL sync subsystems.
-- KevBox is one-device-per-member, so the "sync owner" collapses to auth.uid() (spec §3).
--
-- CANARY GATE (spec §8 option a): the resolver is CLOSED BY DEFAULT. It returns auth.uid()::text
-- ONLY for callers in public.sync_canary_members; everyone else resolves to NULL. Because every
-- sync RPC derives its owner from nullif(get_sync_owner(),'')::uuid, a NULL owner makes every pull
-- empty, every push a R4 no-op, and sync_pull_profiles() EMPTY (no synth default => the client's
-- replaceAllProfiles() is skipped => local profiles preserved). So applying the whole cloud-restore
-- schema to live prod with an EMPTY allowlist has ZERO blast radius: nobody syncs until an operator
-- inserts their auth uid. Allowlist a member -> they (and only they) get the real owner = full sync.
-- Fleet-wide enable later = either insert every member uid, or replace this fn with the ungated
-- `select auth.uid()::text` (see CLOUD-RESTORE-RUNBOOK.md §canary).
--
-- WHY NULL, not '': the client AuthManager.getEffectiveUserId calls get_sync_owner directly and does
-- result.decodeAs<String>() into a NON-nullable String. A NULL body throws there (caught -> the same
-- fallback as today's RPC-absent 42883), so a gated-off member behaves EXACTLY as today. '' would
-- decode to a non-null "" and flow downstream as a bogus owner id. NULL is reproduced naturally by a
-- zero-row scalar SELECT. Downstream nullif(get_sync_owner(),'')::uuid maps NULL->NULL owner anyway.

-- 1. Allowlist table. Admin/cron/definer-managed rollout control — NOT member-facing. RLS on + no
--    grants to app roles; the SECURITY DEFINER resolver below reads it as the (table-owning) definer,
--    which bypasses RLS, so members can neither see nor self-insert into the allowlist.
create table if not exists public.sync_canary_members (
  user_id uuid primary key references auth.users(id) on delete cascade
);
alter table public.sync_canary_members enable row level security;
revoke all on table public.sync_canary_members from public, anon, authenticated;

-- 2. Gated resolver. Created AFTER the table (the SQL body references it at create time). Never errors
--    for anon (auth.uid() NULL => not in allowlist => zero rows => scalar NULL).
create or replace function public.get_sync_owner()
  returns text
  language sql
  security definer
  set search_path = ''
as $$
  select auth.uid()::text
  where auth.uid() in (select user_id from public.sync_canary_members)
$$;

-- The client calls this directly as `authenticated` (AuthManager.getEffectiveUserId). Lock it
-- to authenticated only; the SECURITY DEFINER sync wrappers call it as their owner regardless.
revoke all on function public.get_sync_owner() from public, anon;
grant execute on function public.get_sync_owner() to authenticated;
