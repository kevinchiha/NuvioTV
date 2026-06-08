# Plan: Remote enable/disable kill-switch for KevBox TV

## Context

Today, removing a member from Supabase does **not** stop them using KevBox TV: local
state (DataStore) keeps working offline, streaming goes direct to the debrid provider
via baked-in addon URLs, the current JWT stays valid (~1h), and every sync call is
wrapped in try/catch that silently **falls back to local state** on failure. So there is
no way to actually cut someone off.

This adds a proper **remote enable/disable switch**: one `active` flag per member in
Supabase. Flip it off → within a couple of minutes the member's app shows a full-screen
"Access disabled" page that blocks all navigation. Flip it back on → next check restores
normal use. We **disable, never delete** (reversible, preserves their config, and the
member's own valid session can still read back the "disabled" verdict — which is exactly
why disable beats delete here).

Decisions already made:
- **Lockout behavior:** blocking screen only — no sign-out, no local wipe. Fully reversible.
- **Grace window:** a few minutes. Online → locks within one check interval (~2 min);
  offline → keeps working until ~5 min since the last *successful* check, then fails closed.
- **Missing row = allowed** (fail-open): only an explicit `active=false` locks out, so a
  legacy/unseeded member is never bricked. Deletion is not the lockout path; disabling is.

This is **standalone** — it does not depend on the (still-unbuilt) member-addon feature in
`MEMBER-CONFIG-PLAN.md`, though it composes cleanly with it. Lives in `src/main`, so it
covers all build flavors.

## Server — Supabase (run in SQL editor)

New table, one row per member:

```sql
create table public.member_access (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  active     boolean not null default true,
  updated_at timestamptz not null default now()
);
alter table public.member_access enable row level security;

-- Member can READ only their own row; nobody but service_role can change `active`.
create policy "read own access" on public.member_access
  for select using (auth.uid() = user_id);

-- Auto-seed every new sign-up as active. SECURITY DEFINER + pinned search_path
-- (avoids the function_search_path_mutable advisor warning).
create or replace function public.seed_member_access()
  returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.member_access (user_id) values (new.id) on conflict do nothing;
  return new;
end $$;
create trigger on_auth_user_created_seed_access
  after insert on auth.users for each row execute function public.seed_member_access();

-- One-time backfill for existing members.
insert into public.member_access (user_id) select id from auth.users on conflict do nothing;
```

**Admin operations (you, in SQL editor):**
- Disable: `update public.member_access set active=false, updated_at=now() where user_id='<uuid>';`
- Re-enable: `update public.member_access set active=true,  updated_at=now() where user_id='<uuid>';`

## Client — Android

Mirror the existing Supabase-read pattern in
`app/src/main/java/com/nuvio/tv/core/sync/AddonSyncService.kt` (inject `Postgrest` +
`AuthManager`, wrap the query in `withJwtRefreshRetry`, resolve the member via
`authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)`).

1. **Model** — add to `app/src/main/java/com/nuvio/tv/data/remote/supabase/SupabaseModels.kt`:
   `@Serializable data class MemberAccessRow(@SerialName("user_id") val userId: String, val active: Boolean)`.
   Add R8 keep rules for it + its `$$serializer` in `app/proguard-rules.pro` (mirror the
   existing serializable-model keeps; obfuscation can blank the field otherwise).

2. **`AccessControlDataStore`** — `app/src/main/java/com/nuvio/tv/data/local/AccessControlDataStore.kt`,
   modeled on existing DataStores (`LastSignInDataStore.kt`, `StartupSyncPreferences`).
   Persists `lastOkAtMs: Long` + `lockedOut: Boolean` and exposes `lockedOut` as a Flow.
   Persisting means the grace/lockout survives a force-quit (can't be bypassed by relaunch).

3. **`AccessControlService`** — `app/src/main/java/com/nuvio/tv/core/access/AccessControlService.kt`,
   `@Singleton @Inject(postgrest, authManager, accessControlDataStore)`:
   - `suspend fun refreshAccess()`:
     - resolve `uid = getEffectiveUserId(fallbackToOwnIdOnFailure=false)`; if null → UNKNOWN (grace).
     - `withJwtRefreshRetry { postgrest.from("member_access").select { filter { eq("user_id", uid) } }.decodeList<MemberAccessRow>() }`.
       - `active == true` → ALLOWED: `lastOkAtMs = now`, `lockedOut = false`.
       - `active == false` → LOCKED: `lockedOut = true`.
       - empty list (no row) → ALLOWED (fail-open), `lastOkAtMs = now`.
     - on exception (network/JWT) → UNKNOWN: if `now - lastOkAtMs > GRACE_MS` → `lockedOut = true`; else leave as-is.
   - `val lockedOut: StateFlow<Boolean>` (backed by the DataStore).
   - Constants: `GRACE_MS = 5 * 60 * 1000L`, `CHECK_INTERVAL_MS = 2 * 60 * 1000L`,
     and a `FEATURE_ACCESS_CONTROL = true` flag so the whole thing can be turned off fast
     if it misbehaves (a kill-switch for the kill-switch).

4. **Wire the check + gate** in `app/src/main/java/com/nuvio/tv/MainActivity.kt`
   (already injects services and collects `authManager.authState` at L296; `startDestination`
   computed at L544):
   - Inject `AccessControlService`.
   - Startup + periodic re-check: in a `LaunchedEffect(authState)` that runs only when
     `authState is AuthState.FullAccount`, launch on `lifecycleScope`:
     `accessControlService.refreshAccess()` immediately, then
     `while (isActive) { delay(CHECK_INTERVAL_MS); refreshAccess() }`.
     (Not `StartupSyncService` — its pull is TTL-gated at 6h, too coarse for a few-min grace.)
   - UI gate: `val lockedOut by accessControlService.lockedOut.collectAsState()`, then wrap the
     rendered content — `if (lockedOut) LockedOutScreen(onRetry = { scope.launch { accessControlService.refreshAccess() } }) else { /* existing NavHost block */ }`.
     Because `lockedOut` is a StateFlow, flipping the server flag flips the screen **mid-session**
     on the next check — no restart needed.

5. **`LockedOutScreen`** — `app/src/main/java/com/nuvio/tv/ui/screens/account/LockedOutScreen.kt`,
   styled like `AuthSignInScreen.kt`. Full-screen, TV-remote focusable: "Access disabled —
   contact the administrator" + a focused **Retry** button (calls `refreshAccess()`).
   `BackHandler {}` no-op so back can't escape it.

## Optional, no code — instant streaming kill (Option 2)

Each member has their own debrid key baked into their addon URLs. To cut streaming
*immediately* (even mid-session, independent of this switch), revoke/rotate that member's
API key at the debrid provider (Real-Debrid / AllDebrid / Torbox). Belt-and-suspenders;
not required for the kill-switch to work.

## Verification (end-to-end)

1. **Server:** run SQL; `select * from member_access`; RLS on; with the member's access
   token + `apikey`, `curl .../rest/v1/member_access` returns only their row; an UPDATE
   attempt with that token is denied.
2. **Seed/backfill:** a fresh sign-up gets an `active=true` row (trigger); backfill covered
   existing members.
3. **Happy path:** member with `active=true` uses the app normally.
4. **Lockout (online):** `update ... set active=false` → the *running* app switches to
   LockedOutScreen within ~`CHECK_INTERVAL_MS` (no restart); relaunch is also locked.
5. **Re-enable:** set `active=true` → Retry (or next check) → app returns to normal, addons intact.
6. **Grace (offline):** disable the flag, then take the TV offline → app keeps working until
   ~`GRACE_MS` since the last good check, then locks (fail-closed). Back online with
   `active=true` → unlocks.
7. **Fail-open:** delete the member's `member_access` row → app stays usable (not locked),
   confirming unseeded/legacy members aren't bricked.
8. **R8:** assemble a signed release; confirm `MemberAccessRow` deserializes (keep rules hold)
   and LockedOutScreen renders in the minified build.
