-- KevBox TV — cloud-restore sync owner resolver. Run ONCE against the KevBox Supabase project
-- (scmqdptagksltnwiveyh). Idempotent. No secrets. Shared foundation for ALL sync subsystems.
-- KevBox is one-device-per-member, so the "sync owner" collapses to auth.uid() (spec §3).

create or replace function public.get_sync_owner()
  returns text
  language sql
  security definer
  set search_path = ''
as $$ select auth.uid()::text $$;

-- The client calls this directly as `authenticated` (AuthManager.getEffectiveUserId). Lock it
-- to authenticated only; the SECURITY DEFINER sync wrappers call it as their owner regardless.
revoke all on function public.get_sync_owner() from public, anon;
grant execute on function public.get_sync_owner() to authenticated;
