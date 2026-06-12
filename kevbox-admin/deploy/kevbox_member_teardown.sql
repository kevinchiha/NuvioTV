-- KevBox member enrollment — PRODUCTION teardown (reverses kevbox_member_setup.sql).
-- Run as POSTGRES in the Supabase SQL editor (or psql with -v ON_ERROR_STOP=1).
--
-- Drops ONLY the three kevbox enrollment sidecar tables, along with their indexes, RLS
-- policies, and grants (removed automatically by CASCADE). Touches NOTHING pre-existing:
-- public.member_addon, auth.users, member_access, telemetry, etc. are all left intact.
--
-- WARNING: this destroys enrollment state — aiostreams names, encrypted Premiumize keys,
-- and the audit trail. The encrypted keys cannot be recovered afterwards.
drop table if exists public.kevbox_audit           cascade;
drop table if exists public.kevbox_member          cascade;
drop table if exists public.kevbox_allowlist_extra cascade;

-- ---------------------------------------------------------------------------------------
-- OPTIONAL — also undo the §11 migration's effect on the EXISTING member_addon table.
-- The migration (kevbox-migrate --apply) and dashboard enroll add one kevbox addon row per
-- member (the install URL). Dropping the tables above does NOT remove those rows. To fully
-- revert the cutover, ALSO run the line below (adjust the host if KEVBOX_STREAMS_BASE_URL
-- differs from the default). Left commented so the teardown never deletes member_addon rows
-- unless you explicitly opt in.
--
-- delete from public.member_addon where url like 'https://streams.kevbox.dev/stremio/k/%';
