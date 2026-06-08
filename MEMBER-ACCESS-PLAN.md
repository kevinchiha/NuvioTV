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
  Grace is measured with a tamper-resistant clock (client step 2) so rolling the device clock
  back can't extend it; a device that has *never* had a successful check yet is treated as
  in-grace (not locked), so a first launch while offline isn't a false lockout.
- **Missing row = allowed** (fail-open): only an explicit `active=false` locks out, so a
  legacy/unseeded member is never bricked. Deletion is not the lockout path; disabling is.
- **Identity for gating = the member's own `auth.uid()`, never the *effective* id.** Scoping is
  done by RLS, not a client-supplied `user_id` (see the Client section for why this matters).

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
-- NOTE: member_addon_setup.sql already adds an AFTER INSERT trigger on auth.users
-- (on_member_addon_seed). Both fire on sign-up; both are SECURITY DEFINER with
-- `on conflict do nothing`, so an error in either would roll back the sign-up —
-- verify sign-up still works after applying this (Verification step 2).
create trigger on_auth_user_created_seed_access
  after insert on auth.users for each row execute function public.seed_member_access();

-- One-time backfill for existing members.
insert into public.member_access (user_id) select id from auth.users on conflict do nothing;
```

> ⚠️ **Don't expose `member_access` through a plain view.** A view bypasses the table's RLS and
> Supabase grants `ALL` to `anon`/`authenticated` by default, so a convenience view would leak
> every member's flag across accounts (this exact bug already bit the `member_addon` feature). If
> you ever need one, create it `with (security_invoker = on)` and `revoke` the default grants.

**Admin operations (you, in SQL editor):**
- Disable: `update public.member_access set active=false, updated_at=now() where user_id='<uuid>';`
- Re-enable: `update public.member_access set active=true,  updated_at=now() where user_id='<uuid>';`

## Client — Android

Mirror the existing Supabase-read pattern in
`app/src/main/java/com/nuvio/tv/core/sync/AddonSyncService.kt` (inject `Postgrest` +
`AuthManager`, wrap the query in `withJwtRefreshRetry`).

> ⚠️ **Do *not* resolve the member with `authManager.getEffectiveUserId()` here.** That method
> returns the *sync owner's* id (it calls the `get_sync_owner` RPC, `AuthManager.kt:123`), which on
> any sync-linked device differs from the JWT's `auth.uid()`. Since `member_access` RLS is
> `auth.uid() = user_id`, filtering the query by the effective id would return an **empty** result
> on such a device → fail-open → that member could never be locked out (and it would pass every
> happy-path test, since today's email/password members have effective == own). Access gating must
> key on the member's *own* identity, so we let **RLS do the scoping**: issue the `select` with
> **no `user_id` filter** and decode the 0-or-1 row RLS returns. (The device-limit extension below
> already does this correctly via a `SECURITY DEFINER` RPC that reads `auth.uid()`.)

1. **Model** — add to `app/src/main/java/com/nuvio/tv/data/remote/supabase/SupabaseModels.kt`:
   `@Serializable data class MemberAccessRow(@SerialName("user_id") val userId: String, val active: Boolean)`.
   Add R8 keep rules for it + its `$$serializer` in `app/proguard-rules.pro` (mirror the
   existing serializable-model keeps; obfuscation can blank the field otherwise).

2. **`AccessControlDataStore`** — `app/src/main/java/com/nuvio/tv/data/local/AccessControlDataStore.kt`,
   modeled on existing DataStores (`LastSignInDataStore.kt`, `StartupSyncPreferences`).
   Persists the last-good-check timestamp as **two clocks** — `lastOkWallMs: Long`
   (`System.currentTimeMillis()`) and `lastOkElapsedMs: Long` (`SystemClock.elapsedRealtime()`),
   both defaulting to `0L` ("never checked") — plus `lockedOut: Boolean`; exposes `lockedOut` as a
   Flow. Two clocks because the wall clock is user-settable on Android TV: rolling it back would
   otherwise extend offline grace forever (step 3 shows how the grace check combines them).
   Persisting means grace/lockout survives a force-quit (can't be bypassed by relaunch).
   **Backup-exclude this DataStore** (step 6) so an old backup can't reset the grace clock.

3. **`AccessControlService`** — `app/src/main/java/com/nuvio/tv/core/access/AccessControlService.kt`,
   `@Singleton @Inject(postgrest, authManager, accessControlDataStore)`:
   - `suspend fun refreshAccess()`:
     - require `authManager.currentUserId != null` (member is signed in); else UNKNOWN (grace).
       Don't pass the id to the query — RLS scopes it.
     - `withJwtRefreshRetry { postgrest.from("member_access").select { }.decodeList<MemberAccessRow>() }`
       (no `filter` — RLS returns only the caller's own row).
       - row with `active == true` → ALLOWED: record success (below), `lockedOut = false`.
       - row with `active == false` → LOCKED: `lockedOut = true`.
       - empty list (no row) → ALLOWED (fail-open): record success.
     - **record success** = `lastOkWallMs = System.currentTimeMillis()`,
       `lastOkElapsedMs = SystemClock.elapsedRealtime()`.
     - on exception (network/JWT) → UNKNOWN: stay as-is **unless grace has expired**, computed
       tamper-resistantly:
       - both clocks `0L` (never checked) → **don't lock** (in-grace).
       - else `elapsed = if (SystemClock.elapsedRealtime() >= lastOkElapsedMs)` → no reboot →
         `elapsedRealtime() - lastOkElapsedMs` (rollback-proof) `else` the device rebooted →
         fall back to `System.currentTimeMillis() - lastOkWallMs`. Lock if `elapsed > GRACE_MS`.
   - `val lockedOut: StateFlow<Boolean>` (backed by the DataStore).
   - Constants: `GRACE_MS = 5 * 60 * 1000L`, `CHECK_INTERVAL_MS = 2 * 60 * 1000L`,
     and a compile-time `FEATURE_ACCESS_CONTROL = true` flag that no-ops the feature if a client
     bug surfaces. (This is a *build-time* guard, not the runtime kill — the runtime control is the
     server `active` flag; flipping the constant means rebuilding + re-sideloading every TV.)

4. **Wire the check + gate** in `app/src/main/java/com/nuvio/tv/MainActivity.kt`
   (already injects services and collects `authManager.authState` at L296; `startDestination`
   computed at L544):
   - Inject `AccessControlService`.
   - Startup + periodic re-check: put the loop **directly in the body** of a
     `LaunchedEffect(authState)` (mirroring the existing `LaunchedEffect(authState)` blocks at
     L298/L314) — do **not** `lifecycleScope.launch` from inside it:
     `if (authState is AuthState.FullAccount) { accessControlService.refreshAccess(); while (isActive) { delay(CHECK_INTERVAL_MS); refreshAccess() } }`.
     Keeping the loop in the effect body means a re-keyed `authState` (a token refresh re-emits
     `FullAccount`) **cancels the previous loop** before starting the next; a detached
     `lifecycleScope.launch` would instead leak a new 2-min poller on every emission.
     (Not `StartupSyncService` — its pull is TTL-gated at 6h, too coarse for a few-min grace.)
   - UI gate: `val lockedOut by accessControlService.lockedOut.collectAsState()`, then wrap the
     rendered content — `if (lockedOut) LockedOutScreen(onRetry = { scope.launch { accessControlService.refreshAccess() } }) else { /* existing NavHost block */ }`.
     Keep the gate and the `LaunchedEffect` at the **same level** (the effect is *not* inside the
     `else`) so the poller keeps running while locked and auto-recovers on re-enable.
     Because `lockedOut` is a StateFlow, flipping the server flag flips the screen **mid-session**
     on the next check — no restart needed.

5. **`LockedOutScreen`** — `app/src/main/java/com/nuvio/tv/ui/screens/account/LockedOutScreen.kt`,
   styled like `AuthSignInScreen.kt`. Full-screen, TV-remote focusable: "Access disabled —
   contact the administrator" + a focused **Retry** button (calls `refreshAccess()`).
   `BackHandler {}` no-op so back can't escape it.

6. **Exclude guard state from backup** — `AndroidManifest.xml` currently sets
   `android:allowBackup="true"` (L36). Add `android:dataExtractionRules` (and
   `android:fullBackupContent` for pre-Android-12) on `<application>` and exclude the
   `AccessControlDataStore` file (and the extension's `DeviceGuardDataStore`). Otherwise an
   `adb backup`/cloud restore onto another TV carries over the grace clock — and, for the device
   limit, the *same* device UUID — letting a second device impersonate the bound one. (Clearing
   app data already self-locks; restore is the gap this closes.)

## Optional, no code — instant streaming kill (Option 2)

Each member has their own debrid key baked into their addon URLs. To cut streaming
*immediately* (even mid-session, independent of this switch), revoke/rotate that member's
API key at the debrid provider (Real-Debrid / AllDebrid / Torbox). Belt-and-suspenders;
not required for the kill-switch to work.

**Known limitation the UI gate can't cover:** the app is single-activity (only `MainActivity` in
the manifest), so `LockedOutScreen` covers the in-process player and all navigation. But a stream
handed off to an **external player** (intent from `PlayerRuntimeControllerStreams.kt`, kept alive
by `ExternalPlaybackKeepAliveService`) runs in another process — an *already-playing* external
session won't be interrupted; the lock lands when the member returns to the app. For an in-flight
cutoff there, Option 2 (revoke the debrid key) is the real lever.

## Verification (end-to-end)

1. **Server:** run SQL; `select * from member_access`; RLS on; with the member's access
   token + `apikey`, `curl .../rest/v1/member_access` returns only their row; an UPDATE
   attempt with that token is denied.
2. **Seed/backfill + trigger coexistence:** a fresh sign-up gets an `active=true` row (trigger),
   the existing `on_member_addon_seed` trigger still fires, and **sign-up itself succeeds** (two
   `AFTER INSERT` triggers on `auth.users` now — a failure in either would roll back the insert).
   Backfill covered existing members.
3. **Happy path:** member with `active=true` uses the app normally.
4. **Lockout (online):** `update ... set active=false` → the *running* app switches to
   LockedOutScreen within ~`CHECK_INTERVAL_MS` (no restart); relaunch is also locked.
5. **Re-enable:** set `active=true` → Retry (or next check) → app returns to normal, addons intact.
6. **Grace (offline):** disable the flag, then take the TV offline → app keeps working until
   ~`GRACE_MS` since the last good check, then locks (fail-closed). Back online with
   `active=true` → unlocks.
7. **Fail-open + RLS scoping:** delete the member's `member_access` row → app stays usable (not
   locked), confirming unseeded/legacy members aren't bricked **and** that the no-`user_id`-filter
   query is correctly scoped by RLS (returns only the caller's own row, never another member's).
8. **R8:** assemble a signed release; confirm `MemberAccessRow` deserializes (keep rules hold)
   and LockedOutScreen renders in the minified build.

---

# Extension: one-device-per-member limit

## Context

The switch above controls *whether* a member may use the app; this controls *how many devices*
they may use it on. Each member has their own KevBox account (email/password — the QR/TV-login
flow is dormant), and today nothing stops that one account signing in on a second TV: the
Supabase session persists indefinitely via its refresh token. We bind each account to **one**
device; a second/replacement device stays locked out **until you explicitly allow it** in
Supabase.

This is the same goal-shape as the access switch (periodic Supabase check → `LockedOutScreen`,
reversible mid-session), so it **reuses all the client plumbing above** — the periodic loop in
`MainActivity`, `LockedOutScreen`, and the Supabase-read pattern. New pieces are only a stable
per-install device ID (none exists today — the QR flow's `deviceNonce` is throwaway) and a
server-side atomic claim.

Decisions already made:
- **Granularity:** one account per member → enforce on the member's own `auth.uid()`
  (server-resolved from the JWT; the client passes no `user_id`, and `getEffectiveUserId` is
  irrelevant here).
- **Allow-more model:** admin-controlled. Default limit = **1**. A device over the limit is
  locked out until you raise `max_devices` for that member or delete the stale device row.
- **No login-time hard block:** the claim runs the instant the app reaches `FullAccount`, so a
  disallowed device locks within seconds of sign-in — no edge-function/login hook needed.
- **Backfill-free:** `member_device` is populated lazily on first claim; an existing member's
  current TV auto-binds on first run of the new build (non-disruptive). A member who *currently*
  runs two TVs: whichever claims first wins — bump their `max_devices` to 2 before they update.

## Server — additional SQL (run in SQL editor)

```sql
-- One row per (member, device). Populated lazily by claim_device().
create table public.member_device (
  user_id     uuid not null references auth.users(id) on delete cascade,
  device_id   text not null,
  device_name text,
  first_seen  timestamptz not null default now(),
  last_seen   timestamptz not null default now(),
  primary key (user_id, device_id)
);
alter table public.member_device enable row level security;
create policy "read own devices" on public.member_device
  for select using (auth.uid() = user_id);   -- writes happen only via the RPC below

-- Per-member device cap. Missing row => limit of 1.
create table public.member_device_policy (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  max_devices int not null default 1,
  updated_at  timestamptz not null default now()
);
alter table public.member_device_policy enable row level security;
create policy "read own policy" on public.member_device_policy
  for select using (auth.uid() = user_id);

-- Atomic claim-or-deny. SECURITY DEFINER + pinned search_path (avoids the
-- function_search_path_mutable advisor warning); uid taken from the JWT (tamper-proof).
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

  -- Serialize concurrent claims for the same member so two devices can't both pass the
  -- count check at once (TOCTOU). Auto-released at transaction end.
  perform pg_advisory_xact_lock(hashtextextended(v_uid::text, 0));

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
grant execute on function public.claim_device(text, text) to authenticated;
```

**Admin operations (you, in SQL editor):**
- See a member's devices: `select * from public.member_device where user_id='<uuid>';`
  (identical KevBox models share the same `device_name`; use `first_seen`/`last_seen` to tell two
  devices apart and spot the stale one.)
- Allow a 2nd device: `insert into public.member_device_policy(user_id,max_devices) values('<uuid>',2) on conflict (user_id) do update set max_devices=2, updated_at=now();`
- Approve a replacement TV: `delete from public.member_device where user_id='<uuid>' and device_id='<old>';` (or delete all of that member's rows to let the new TV re-claim).

## Client — additions

1. **`DeviceGuardDataStore`** — `app/src/main/java/com/nuvio/tv/data/local/DeviceGuardDataStore.kt`,
   modeled on `LastSignInDataStore.kt`. Persists `deviceId: String`, the two grace clocks
   `lastOkWallMs: Long` + `lastOkElapsedMs: Long` (same scheme as `AccessControlDataStore`, both
   default `0L`), and `deviceLockedOut: Boolean`. Exposes `suspend fun getOrCreateDeviceId(): String`
   (generate `UUID.randomUUID().toString()` once, then return the stored value) and
   `deviceLockedOut` as a Flow. Persisting survives force-quit; clearing app data regenerates the
   UUID but that makes a *new* device now over the limit → still locked out (can't self-bypass).
   **Must be backup-excluded** (base plan, client step 6): otherwise restoring this TV's backup
   onto a second TV clones the *same* `deviceId`, letting both pass `claim_device` as one device
   and defeating the limit.

2. **`DeviceGuardService`** — `app/src/main/java/com/nuvio/tv/core/access/DeviceGuardService.kt`
   (sibling of `AccessControlService`), `@Singleton @Inject(postgrest, authManager, deviceGuardDataStore)`:
   - `suspend fun refreshDeviceClaim()`:
     - require `authManager.currentUserId != null` (**own** id, not effective); else UNKNOWN (grace).
     - `deviceId = getOrCreateDeviceId()`, `name = "${Build.MANUFACTURER} ${Build.MODEL}"`.
     - `withJwtRefreshRetry { postgrest.rpc("claim_device", buildJsonObject { put("p_device_id", deviceId); put("p_device_name", name) }).decodeAs<Boolean>() }`.
       - `true` → AUTHORIZED: record success (`lastOkWallMs`/`lastOkElapsedMs`), `deviceLockedOut = false`.
       - `false` → DENIED: `deviceLockedOut = true`.
     - on exception → UNKNOWN: never-checked (both clocks `0L`) → don't lock; else use the same
       reboot-aware, rollback-proof `elapsed` computation as `refreshAccess()` and lock if
       `elapsed > GRACE_MS`.
   - `val deviceLockedOut: StateFlow<Boolean>` (backed by the DataStore); reuse `GRACE_MS` /
     `CHECK_INTERVAL_MS`; add `FEATURE_DEVICE_LIMIT = true`.
   - Returns a bare `Boolean` → **no new `@Serializable` model and no R8 keep rules**.

3. **Wire into the same loop & gate** in `MainActivity.kt`:
   - Inject `DeviceGuardService`; in the **same** `LaunchedEffect(authState)` loop that calls
     `refreshAccess()`, also call `refreshDeviceClaim()` (immediately, then each `CHECK_INTERVAL_MS`).
   - `val deviceLockedOut by deviceGuardService.deviceLockedOut.collectAsState()`; the gate
     becomes `if (lockedOut || deviceLockedOut) LockedOutScreen(reason = ...) else { /* NavHost */ }`.

4. **`LockedOutScreen`** — add a `reason`/`message` param (same screen, two messages):
   device case shows "This device isn't authorized — contact the administrator". The single
   **Retry** button always calls **both** `refreshAccess()` and `refreshDeviceClaim()` — the gate
   is `lockedOut || deviceLockedOut`, so a one-guard Retry could leave the other condition stuck.
   When both are locked, show the access message first.

## Verification — additions

9. **Server RPC:** with a member token, `rpc/claim_device` with a fresh `p_device_id` → `true`
   and a row appears; a second *different* id for the same token → `false`; re-calling the first
   id → `true` (idempotent). Direct UPDATE to `member_device` with the member token is RLS-denied.
10. **Second device locks out:** sign the same account in on TV B → within seconds TV B shows
    `LockedOutScreen`; TV A keeps working.
11. **Allow a 2nd / replacement:** set `max_devices=2` → Retry on TV B unlocks it; or `delete`
    the old row + limit back to 1 → old TV locks next check, new TV claims and runs.
12. **Self-bypass attempt:** clear app data on the bound TV → new UUID → account now has 2 rows
    vs limit 1 → claim `false` → locked out.
13. **Backup-restore self-bypass:** back up the bound TV and restore onto a second TV — with the
    DataStores backup-excluded, the restored TV generates a *fresh* UUID and is over the limit →
    locked (confirms the `allowBackup` hole is closed; the same exclusion stops grace-clock reset).
14. **Concurrent claim (TOCTOU):** fire two `claim_device` calls for the same fresh token with
    *different* device ids at once → exactly one returns `true` (the advisory lock serializes
    them); the member never ends with 2 rows under a limit of 1.

## Deliverable

Add `member_device_setup.sql` (the Server block above) at repo root alongside the existing
`member_addon_setup.sql`, as the version-controlled schema source of truth.
