# Plan: Remote enable/disable kill-switch for KevBox TV

> **Revision note (2026-06-09):** hardened after a grounded gap analysis against the live
> codebase. Key changes vs. the first draft: the access read is now a `SECURITY DEFINER` RPC
> (closes a fail-open hole on a stale/expired session), the client guards are made
> exception-safe and lifecycle-aware, the feature is gated by a **per-flavor `buildConfigField`**
> (so it never ships into the public `playstore` flavor), the SQL is idempotent + grant-revoked
> to match the repo's `member_addon_setup.sql` standard, both `*_setup.sql` deliverables are named,
> and the threat model is stated honestly (client-side enforcement; the debrid key is the only
> true streaming kill).

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

This is a **client-side UI lock**, not a capability revocation — be honest about what it
does and doesn't stop (see *Threat model* below). For an adversarial, in-flight streaming
cutoff, the real lever is rotating the member's debrid key (Option 2), which is **promoted
to a primary action**, not "optional."

Decisions already made:
- **Lockout behavior:** blocking screen only — no sign-out, no local wipe. Fully reversible.
- **Grace window:** a few minutes. Online → locks within one check interval (~2 min) **while the
  app is foreground**; offline → keeps working until ~5 min since the last *successful* check,
  then fails closed. Grace is measured with a tamper-resistant clock (client step 1) so rolling the
  device clock back can't extend it; a device that has *never* had a successful check yet is treated
  as in-grace (not locked), so a first launch while offline isn't a false lockout.
- **Server is the source of truth for the verdict.** The client reads access via a
  `SECURITY DEFINER` RPC (`get_access_verdict`) that resolves `auth.uid()` **server-side** and
  returns a 3-state result. A raw `select` on the table is **not** used — see the fail-open note
  in the Client section.
- **Missing row = allowed** (fail-open) **only when the caller is provably authenticated**: an
  unauthenticated/expired-JWT call is *not* treated as allowed (it routes to grace). A legacy/unseeded
  but signed-in member is never bricked. Deletion is not the lockout path; disabling is.
- **Identity for gating = the member's own `auth.uid()`, resolved on the server from the JWT —
  never the *effective* id and never a client-supplied `user_id`** (see the Client warning).
- **Feature is gated by a per-flavor `buildConfigField`** so the public `playstore` flavor never
  ships the gate or calls the family Supabase project (see *Build flavor* below).

This is **standalone** — it does not depend on the (still-unbuilt) member-addon feature in
`MEMBER-CONFIG-PLAN.md`, though it composes cleanly with it. The runtime code lives in `src/main`
(so it can run on every flavor), but the gate is **compiled out** of `playstore` via the flag.

## Build flavor — `app/build.gradle.kts`

The codebase already gates Supabase-coupled features per flavor (`FEATURE_MEMBER_ADDON_CONFIG`
is `true` for `full` / `false` for `playstore`, at `app/build.gradle.kts:138,147`). Mirror it —
**do not** use a bare Kotlin `const val`, which is identical in both flavors and would ship the
kill-switch + `claim_device` calls into the public `com.nuvio.app` build (polluting `member_device`
with junk rows and hitting the family Supabase from arbitrary installs):

```kotlin
// productFlavors { full { ... } }
buildConfigField("boolean", "FEATURE_ACCESS_CONTROL", "true")
buildConfigField("boolean", "FEATURE_DEVICE_LIMIT", "true")
// productFlavors { playstore { ... } }
buildConfigField("boolean", "FEATURE_ACCESS_CONTROL", "false")
buildConfigField("boolean", "FEATURE_DEVICE_LIMIT", "false")
```

The KevBox family build is the `full` flavor (`applicationId = "tv.kevbox"`); `playstore` is the
public `com.nuvio.app` build. Branch on `BuildConfig.FEATURE_ACCESS_CONTROL` /
`BuildConfig.FEATURE_DEVICE_LIMIT` in `MainActivity` **before** starting the poller or rendering the
gate. These build-time flags also double as the per-client "no-op if a client bug surfaces" guard
(flipping to `false` requires a rebuild + re-sideload; the *runtime* kill is the server `active`
flag / the RPC override in *Rollback*).

## Server — Supabase (run in SQL editor)

> Idempotent and grant-revoked to match the repo standard (`member_addon_setup.sql`). Safe to re-run.

New table, one row per member:

```sql
-- 1. Table — one row per member.
create table if not exists public.member_access (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  active     boolean not null default true,
  updated_at timestamptz not null default now()
);
alter table public.member_access enable row level security;

-- 2. RLS: member can READ only their own row; nobody but service_role can change `active`.
drop policy if exists "read own access" on public.member_access;
create policy "read own access" on public.member_access
  for select using (auth.uid() = user_id);

-- 2b. Least-privilege grants. Supabase grants ALL DML to anon/authenticated by default; RLS gates
--     SELECT to own rows, but revoke the write/truncate grants so the table can't be mutated via the
--     REST API regardless of any future policy mistake (mirrors member_addon_setup.sql:29-30).
revoke insert, update, delete, truncate, references, trigger
  on public.member_access from anon, authenticated;

-- 3. Authoritative verdict RPC. SECURITY DEFINER + pinned search_path. Resolves auth.uid() from the
--    JWT (tamper-proof) and returns a 3-state result. Raises on an unauthenticated caller so the
--    client routes it to grace/UNKNOWN instead of fail-open. This is the ONLY read path the app uses.
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

-- 4. Auto-seed every new sign-up as active. SECURITY DEFINER + pinned search_path.
create or replace function public.seed_member_access()
  returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.member_access (user_id) values (new.id) on conflict do nothing;
  return new;
end $$;
-- NOTE: member_addon_setup.sql already adds an AFTER INSERT trigger on auth.users
-- (on_member_addon_seed). Both fire on sign-up; both are SECURITY DEFINER with
-- `on conflict do nothing`, so an error in either would roll back the sign-up —
-- verify sign-up still works after applying this (Verification step 2). See Rollback if it does not.
drop trigger if exists on_auth_user_created_seed_access on auth.users;
create trigger on_auth_user_created_seed_access
  after insert on auth.users for each row execute function public.seed_member_access();

-- 5. One-time backfill for existing members.
insert into public.member_access (user_id) select id from auth.users on conflict do nothing;

-- Sanity check
select count(*) as member_access_rows from public.member_access;
```

> ⚠️ **Don't expose `member_access` through a plain view.** A view bypasses the table's RLS and
> Supabase grants `ALL` to `anon`/`authenticated` by default, so a convenience view would leak
> every member's flag across accounts (this exact bug already bit the `member_addon` feature). If
> you ever need one, create it `with (security_invoker = on)` and `revoke` the default grants.

**Admin operations (interim — SQL editor; see *Admin integration* for the kevbox-admin path):**
- Disable: `update public.member_access set active=false, updated_at=now() where user_id='<uuid>';`
- Re-enable: `update public.member_access set active=true,  updated_at=now() where user_id='<uuid>';`
- Always **disable** rather than delete — a deleted row reads as `NO_ROW` → fail-open (allowed).

## Client — Android

The read mirrors the existing Supabase RPC pattern in
`app/src/main/java/com/nuvio/tv/core/sync/AddonSyncService.kt` (inject `Postgrest` +
`AuthManager`, wrap the call in `withJwtRefreshRetry`, `AddonSyncService.kt:29-36`), but goes
through the verdict **RPC**, not a table `select`.

> ⚠️ **Why an RPC, not `select`.** RLS (`auth.uid() = user_id`) only *filters rows*; it does not
> require a live JWT. A raw `postgrest.from("member_access").select { }` issued with an expired/missing
> JWT (most likely *exactly when you disable a member and their session goes stale*) returns an **empty
> list**, which is indistinguishable from "legacy member, no row" — so a naive `empty → ALLOWED`
> rule would **fail open and never lock the member**. `authManager.currentUserId != null` doesn't save
> you either: it reads cached `AuthState` (`AuthManager.kt:112`), which can still report `FullAccount`
> on an expired session. The `get_access_verdict` RPC closes this: it resolves `auth.uid()` on the
> server and **raises** when the caller isn't authenticated, so the client gets an exception (→ grace),
> never a fail-open allow. (PostgREST also rejects an expired JWT with 401 before the function runs —
> same result.)

> ⚠️ **Do *not* resolve the member with `authManager.getEffectiveUserId()`.** That returns the
> *sync owner's* id (the `get_sync_owner` RPC, `AuthManager.kt:123-137`), which on a sync-linked device
> differs from the JWT's `auth.uid()`. We no longer pass *any* id to the read — the RPC reads
> `auth.uid()` itself — so this can't be gotten wrong, but the same rule applies to `claim_device`.

1. **`AccessControlDataStore`** — `app/src/main/java/com/nuvio/tv/data/local/AccessControlDataStore.kt`,
   modeled on existing DataStores (`LastSignInDataStore.kt`, `StartupSyncPreferences`).
   Persists the last-good-check timestamp as **two clocks** — `lastOkWallMs: Long`
   (`System.currentTimeMillis()`) and `lastOkElapsedMs: Long` (`SystemClock.elapsedRealtime()`),
   both defaulting to `0L` ("never checked") — plus `lockedOut: Boolean`; exposes `lockedOut` and an
   `initialized: Boolean` (has the first persisted read landed yet?) as Flows. Two clocks because the
   wall clock is user-settable on Android TV: rolling it back would otherwise extend offline grace
   forever (step 2 shows how the grace check combines them). Persisting means grace/lockout survives a
   force-quit (can't be bypassed by relaunch). **Backup-exclude this DataStore** (step 5) so an old
   backup can't reset the grace clock.

2. **`AccessControlService`** — `app/src/main/java/com/nuvio/tv/core/access/AccessControlService.kt`,
   `@Singleton @Inject(postgrest, authManager, accessControlDataStore)`:
   - `suspend fun refreshAccess()` — **must never throw** (catch-all internally; return `Unit`,
     mirroring `ProfileSyncService.pullProfileLockStates()` at `ProfileSyncService.kt:127-139`). An
     escaped exception from a `LaunchedEffect` body cancels the effect's coroutine and **kills the
     poller for the rest of the session**, silently freezing the kill-switch. Do all network/DataStore
     work inside `withContext(Dispatchers.IO)` (the loop runs from a Main-thread effect).
     - require `authManager.currentUserId != null` (member is signed in); else UNKNOWN (grace).
     - `val verdict = withJwtRefreshRetry { postgrest.rpc("get_access_verdict").decodeAs<String>() }`
       (no params; the RPC reads `auth.uid()`).
       - `"ALLOWED"` or `"NO_ROW"` → ALLOWED: **record success** (below), `lockedOut = false`.
       - `"LOCKED"` → LOCKED: `lockedOut = true`. **Do not record success** (so a later re-enable while
         offline doesn't sit in a fresh 5-min grace).
     - **record success** = `lastOkWallMs = System.currentTimeMillis()`,
       `lastOkElapsedMs = SystemClock.elapsedRealtime()`; mark `initialized = true`.
     - on exception (network / JWT / 401 / `not authenticated` raise) → UNKNOWN: **`lockedOut` is
       monotonic here — never cleared; it can only go `false → true` via grace expiry.** Clearing the
       lock requires a successful `ALLOWED`/`NO_ROW`. Grace is computed tamper-resistantly:
       - both clocks `0L` (never checked) → **don't lock** (in-grace).
       - else `elapsed = if (SystemClock.elapsedRealtime() >= lastOkElapsedMs)` → no reboot →
         `elapsedRealtime() - lastOkElapsedMs` (rollback-proof) `else` the device rebooted →
         fall back to `System.currentTimeMillis() - lastOkWallMs`. Lock if `elapsed > GRACE_MS`.
     - Factor the grace decision into a **pure function**
       `fun graceExpired(lastOkWallMs, lastOkElapsedMs, nowWallMs, nowElapsedMs, graceMs): Boolean`
       so it can be unit-tested off-device (see *Tests*).
   - `val lockedOut: StateFlow<Boolean>` and `val initialized: StateFlow<Boolean>` (backed by the
     DataStore). **Seed `lockedOut` from the persisted value before first render** — either seed the
     StateFlow via a `first()` read in an init coroutine and have the gate render a loading box until
     `initialized` is true, or the gate treats `!initialized` as "show loading." Do **not** let the
     gate's first composition default to `false`, or a previously-locked member flashes full content
     for a frame at cold start before the persisted `true` loads.
   - Constants: `GRACE_MS = 5 * 60 * 1000L`, `CHECK_INTERVAL_MS = 2 * 60 * 1000L`. (The build-time
     feature guard is `BuildConfig.FEATURE_ACCESS_CONTROL`, set per flavor — see *Build flavor* —
     not a Kotlin `const`.)

3. **Wire the check + gate** in `app/src/main/java/com/nuvio/tv/MainActivity.kt`
   (collects `authState` at L296; `LaunchedEffect(authState)` blocks at L298/L314; scaffolds render
   inside the `Surface` body around L713-757; `NuvioNavHost` is **not** at the top level — it lives
   deep inside `LegacySidebarScaffold`/`ModernSidebarScaffold`, L1126/L1529):
   - Guard everything on `BuildConfig.FEATURE_ACCESS_CONTROL` (no-op on `playstore`).
   - Inject `AccessControlService`. Add `val scope = rememberCoroutineScope()` near the `authState`
     collection (there is **no** existing `scope`/`rememberCoroutineScope()` in this file — the Retry
     callback below needs one; the file otherwise uses `lifecycleScope.launch`, e.g. L458/L531/L807).
   - **Startup + periodic re-check (foreground):** put the loop **directly in the body** of a
     `LaunchedEffect(authState)` placed at the **outer composable level** (same level as the existing
     `authState` collection ~L296, *not* inside the `Surface`), so the poller keeps running even when
     the gate early-returns:
     `if (BuildConfig.FEATURE_ACCESS_CONTROL && authState is AuthState.FullAccount) { accessControlService.refreshAccess(); while (isActive) { delay(CHECK_INTERVAL_MS); runCatching { accessControlService.refreshAccess() } } }`.
     The `runCatching` is belt-and-suspenders on top of the never-throw contract so one bad cycle can't
     kill the loop.
     > **Rationale (corrected):** keeping the loop in the effect body avoids stacking pollers on a
     > genuine `FullAccount → Loading → FullAccount` transition (reconnect/relogin), where a detached
     > `lifecycleScope.launch` would leak a new 2-min poller each time. Note a *plain token refresh does
     > not re-key the effect*: `authState` is a `StateFlow<AuthState>` and a refresh re-emits an
     > **equal** `FullAccount(userId, email)` data class (`AuthManager.kt:72`), which StateFlow conflates
     > — `collectAsState()` never re-fires. That's fine: the single loop just keeps running.
   - **Backgrounded-but-resident catch-up (lifecycle):** a `LaunchedEffect` is cancelled when the
     Activity is STOPPED (Home → launcher, app switch, external-player handoff). On Android TV the
     process often stays resident, so the foreground loop alone would stop checking while the grace
     clock keeps ticking. Also fire the refresh from `onResume` — alongside the existing
     `startupSyncService.requestForegroundSync()` at `MainActivity.kt:806` —
     `if (BuildConfig.FEATURE_ACCESS_CONTROL) lifecycleScope.launch { accessControlService.refreshAccess() }`.
     The first post-resume check is the real enforcement point; "locks within ~2 min" only holds while
     foreground.
   - **UI gate:** `val lockedOut by accessControlService.lockedOut.collectAsState()` and
     `val initialized by accessControlService.initialized.collectAsState()`. Place the gate as an
     **early guard inside the `Surface` body**, after the onboarding/profile/loading guards
     (the existing `return@Surface` chain, ~L442-537) and **before** the scaffold render (L713):
     `if (!initialized) { /* loading box, mirror L512-518 */; return@Surface }`
     `if (lockedOut) { LockedOutScreen(onRetry = { scope.launch { accessControlService.refreshAccess() } }); return@Surface }`.
     Because the poller `LaunchedEffect` is at the outer level (not inside the `Surface`), it keeps
     running while locked and auto-recovers on re-enable. Because `lockedOut` is a StateFlow, flipping
     the server flag flips the screen **mid-session** on the next check — no restart needed.

4. **`LockedOutScreen`** — `app/src/main/java/com/nuvio/tv/ui/screens/account/LockedOutScreen.kt`,
   styled like `AuthSignInScreen.kt`. Full-screen, TV-remote focusable: "Access disabled —
   contact the administrator" + a focused **Retry** button (calls `refreshAccess()`).
   `BackHandler {}` no-op so back can't escape it.

5. **Exclude guard state from backup** — `AndroidManifest.xml` currently sets
   `android:allowBackup="true"` (L36). `minSdk = 24`, `targetSdk = 36`, so set **both**
   `android:dataExtractionRules` (Android 12+) **and** `android:fullBackupContent` (API 24-30, still
   the install base) on `<application>`, each pointing at an XML rule that **excludes by concrete path**
   the DataStore files (`<exclude domain="file" path="datastore/access_control.preferences_pb"/>` and
   the extension's `device_guard.preferences_pb`; match the actual file names you choose). A path-less
   "exclude all" is unnecessary; name the files. Otherwise an `adb backup`/cloud restore onto another
   TV carries over the grace clock — and, for the device limit, the *same* device UUID — letting a
   second device impersonate the bound one. (Clearing app data already self-locks; restore is the gap
   this closes.)

## Threat model — what this stops, and what it does not

Be honest with the operator; the UI lock is a real deterrent but not a capability kill:

- **Client-side enforcement only.** The gate exists only on devices running a build that has it. A
  member who keeps an **older sideloaded APK** (predating this feature) or simply **declines the next
  update** has no access check and never calls `claim_device` — `active=false` and the device limit
  have **zero effect** on them. The only control that reaches such a client is Option 2 (debrid key).
- **UI-only lock; background traffic continues.** `LockedOutScreen` swaps the composable tree, but
  injected singletons keep running — startup/profile sync, the Android-TV channel refresh job, Trakt,
  plugins — so a "disabled" device stays an active Supabase/debrid client (consuming quota, repainting
  launcher channels). Optionally gate those workers on `accessControlService.lockedOut` too; at minimum
  know that the lock does not silence the service layer.
- **External player.** The app is single-activity (`MainActivity` only), so the gate covers the
  in-process player and all navigation. But a stream handed off to an **external player** (intent at
  `PlayerRuntimeControllerStreams.kt:531-536`, kept alive by `ExternalPlaybackKeepAliveService`) runs
  in another process — an *already-playing* session won't be interrupted; the lock lands when the member
  returns to the app.
- **Clear-data + stay-offline residual.** Clearing app data resets both clocks to `0L`
  (`never checked` → in-grace) and `lockedOut=false`; while offline the verdict RPC can't run, so the
  device shows the local UI indefinitely. This is bounded — **streaming requires network, and the first
  successful online check locks them** — but the local UI is reachable until then. (To close it fully,
  you'd persist an install-time clock and cap total never-checked grace from first launch; that
  reintroduces the offline-first-launch false-lockout this plan deliberately avoids, so it's left as a
  documented residual, not silently on the permissive side.)

### Option 2 — the authoritative streaming kill (do this for an adversarial cutoff)

Each member has their own debrid key baked into their addon URLs. **Revoking/rotating that key at the
provider (Real-Debrid / AllDebrid / Torbox) is the only action that actually stops bytes from flowing**
— immediately, mid-session, on any build including old/never-updated ones. The Supabase `active` flag
is a reversible UI lock that also covers in-app browsing/metadata; the debrid key is what cuts
streaming. For a determined cutoff, do **both**.

## Admin integration (kevbox-admin)

`kevbox-admin` (the live web+CLI at `admin.kevbox.dev`) already manages `member_addon`. The SQL-editor
operations above are the **interim** path; add `member_access` disable/enable (and the device ops below)
to kevbox-admin so cutting a member off is a UI action, not hand-written SQL. Until then, document the
exact SQL in the admin runbook.

## Observability (optional but recommended)

- **Audit:** `member_access.updated_at` records *when* but not *who/why*. If you want an audit trail,
  add a `member_access_audit(user_id, active, actor, reason, at)` insert in the admin path (the table
  itself stays minimal). Same applies to device approvals.
- **Monitoring:** nothing today signals that the gate works or that a device was denied. Consider a
  lightweight metric (e.g. count of `LOCKED` verdicts / `claim_device=false` over time) surfaced in
  kevbox-admin, so a silently-broken gate is visible.

## Rollback — "kill the kill-switch"

The feature can fail *closed* against the whole fleet (a throwing seed trigger blocks all new sign-ups;
a client/RLS bug locks everyone), and the client flag is build-time only (changing it means rebuild +
re-sideload every TV). So ship a server-side off-switch that needs no re-distribution:

- **Globally unlock everyone instantly** (next check): override the RPC to always allow —
  `create or replace function public.get_access_verdict() returns text language sql as $$ select 'ALLOWED' $$;`
  Restore the real body to re-enable. (Same trick for the device limit: redefine `claim_device` to
  `return true`.)
- **Unlock a stuck fleet's flags:** `update public.member_access set active=true, updated_at=now();`
- **Stop the seed trigger if it's blocking sign-ups:**
  `drop trigger if exists on_auth_user_created_seed_access on auth.users;`

## Tests

The tamper-resistant clock is pure logic and must not regress silently. Add a JVM unit test for
`graceExpired(...)` (and the verdict→state mapping) covering: never-checked (both `0L`) → in-grace;
fresh success → not expired; `elapsed > GRACE_MS` → expired; **wall-clock rollback** with no reboot
→ still expired (elapsed clock wins); reboot (`elapsedRealtime()` reset) → falls back to wall clock;
`LOCKED` does not record success; UNKNOWN never clears a set lock. This is the cheapest guard against
re-introducing a fail-open.

## Verification (end-to-end)

1. **Server:** run SQL; `select * from member_access`; RLS on. With the member's access token +
   `apikey` header against the project's REST base URL,
   `curl -s "$SUPABASE_URL/rest/v1/rpc/get_access_verdict" -X POST -H "apikey: $ANON_KEY" -H "Authorization: Bearer $MEMBER_JWT" -H "Content-Type: application/json" -d '{}'`
   returns `"ALLOWED"`; a direct `UPDATE member_access` with that token is **denied** (grants revoked +
   no write policy); the same RPC call with **no/expired** `Authorization` returns an error/401 (not
   `"ALLOWED"`).
2. **Seed/backfill + trigger coexistence:** a fresh sign-up gets an `active=true` row (trigger),
   the existing `on_member_addon_seed` trigger still fires, and **sign-up itself succeeds** (two
   `AFTER INSERT` triggers on `auth.users` now — a failure in either rolls back the insert; if it
   breaks, use *Rollback*). Backfill covered existing members.
3. **Happy path:** member with `active=true` uses the app normally (`full` flavor).
4. **Lockout (online, foreground):** `update ... set active=false` → the *running* app switches to
   LockedOutScreen within ~`CHECK_INTERVAL_MS`; relaunch is also locked.
5. **Lockout after background:** disable while the app is backgrounded (process resident) → on resume
   the `onResume` check locks it (confirms the foreground-only caveat is covered).
6. **Stale-session fail-closed (the fail-open regression test):** disable the member **and** invalidate
   their session (sign them out server-side / let the JWT expire) → the next check's RPC errors → app
   does **not** fall open; it locks once grace expires.
7. **Re-enable:** set `active=true` → Retry (or next check) → app returns to normal, addons intact.
8. **Grace (offline):** disable, then take the TV offline → app keeps working until ~`GRACE_MS` since
   the last good check, then locks (fail-closed). Back online with `active=true` → unlocks.
9. **Fail-open for legacy member:** delete the member's `member_access` row while they stay signed in →
   RPC returns `NO_ROW` → app stays usable (confirms unseeded/legacy members aren't bricked).
10. **Flavor:** build `playstore` → no access check runs, no `member_*` calls hit Supabase
    (`BuildConfig.FEATURE_ACCESS_CONTROL == false`); build `full` → gate active.
11. **Cold-start no-flash:** lock a member, force-quit, relaunch → LockedOutScreen shows immediately
    with no frame of full content (the `initialized` loading gate holds).
12. **Rollback:** override `get_access_verdict` to `select 'ALLOWED'` → a locked fleet unlocks on the
    next check without re-sideloading; restore the body → locks resume.
13. **R8:** assemble a signed `full` release; LockedOutScreen renders in the minified build. **No new
    `@Serializable` model is needed** (the RPC returns a scalar `text`), so no proguard keep rules — just
    confirm the existing minified build still works.

---

# Extension: one-device-per-member limit

## Context

The switch above controls *whether* a member may use the app; this controls *how many devices*
they may use it on. **KevBox uses one account per member** (email/password — the QR/TV-login flow is
dormant). In-app **profiles live within a single account** and do **not** consume device slots: the
limit is account-wide, keyed on `auth.uid()`. Default limit = **1**, i.e. one TV per member account. If
a household instead shares **one** account across several TVs, raise `max_devices` for that member
before they update (otherwise the first TV to claim wins and the rest lock out).

This is the same goal-shape as the access switch (periodic Supabase check → `LockedOutScreen`,
reversible mid-session), so it **reuses all the client plumbing above** — the periodic loop + `onResume`
catch-up in `MainActivity`, `LockedOutScreen`, the never-throw/`Dispatchers.IO`/`runCatching` discipline,
and the build-flavor flag (`FEATURE_DEVICE_LIMIT`). New pieces are only a stable per-install device ID
(none exists today — the QR flow's `deviceNonce` is throwaway) and a server-side atomic claim.

Decisions already made:
- **Granularity:** one account per member → enforce on the member's own `auth.uid()`
  (server-resolved from the JWT; the client passes no `user_id`, and `getEffectiveUserId` is irrelevant).
- **Allow-more model:** admin-controlled. Default limit = **1**. A device over the limit is locked out
  until you raise `max_devices` or delete the stale device row.
- **No login-time hard block:** the claim runs the instant the app reaches `FullAccount`, so a
  disallowed device locks within seconds of sign-in — no edge-function/login hook needed.
- **Backfill-free:** `member_device` is populated lazily on first claim; an existing member's current
  TV auto-binds on first run of the new build (non-disruptive). A member who *currently* runs two TVs:
  whichever claims first wins — bump their `max_devices` to 2 before they update.

## Server — additional SQL (run in SQL editor)

> Idempotent + grant-revoked, same standard as above.

```sql
-- One row per (member, device). Populated lazily by claim_device().
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
create policy "read own devices" on public.member_device
  for select using (auth.uid() = user_id);   -- writes happen only via the RPC below
revoke insert, update, delete, truncate, references, trigger
  on public.member_device from anon, authenticated;

-- Per-member device cap. Missing row => limit of 1.
create table if not exists public.member_device_policy (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  max_devices int not null default 1,
  updated_at  timestamptz not null default now()
);
alter table public.member_device_policy enable row level security;
drop policy if exists "read own policy" on public.member_device_policy;
create policy "read own policy" on public.member_device_policy
  for select using (auth.uid() = user_id);
revoke insert, update, delete, truncate, references, trigger
  on public.member_device_policy from anon, authenticated;

-- Atomic claim-or-deny. SECURITY DEFINER + pinned search_path; uid from the JWT (tamper-proof).
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
revoke all on function public.claim_device(text, text) from public, anon;
grant execute on function public.claim_device(text, text) to authenticated;
```

**Admin operations (interim — SQL editor; integrate into kevbox-admin):**
- See a member's devices: `select * from public.member_device where user_id='<uuid>';`
  (identical KevBox models share the same `device_name`; use `first_seen`/`last_seen` to tell two
  devices apart and spot the stale one.)
- Allow a 2nd device: `insert into public.member_device_policy(user_id,max_devices) values('<uuid>',2) on conflict (user_id) do update set max_devices=2, updated_at=now();`
- Approve a replacement TV: `delete from public.member_device where user_id='<uuid>' and device_id='<old>';` (or delete all of that member's rows to let the new TV re-claim).

## Client — additions

1. **`DeviceGuardDataStore`** — `app/src/main/java/com/nuvio/tv/data/local/DeviceGuardDataStore.kt`,
   modeled on `LastSignInDataStore.kt`. Persists `deviceId: String`, the two grace clocks
   `lastOkWallMs: Long` + `lastOkElapsedMs: Long` (same scheme as `AccessControlDataStore`, both
   default `0L`), `deviceLockedOut: Boolean`, and `initialized: Boolean`. Exposes
   `suspend fun getOrCreateDeviceId(): String` (generate `UUID.randomUUID().toString()` once, then
   return the stored value) and `deviceLockedOut`/`initialized` as Flows. Persisting survives force-quit;
   clearing app data regenerates the UUID but that makes a *new* device now over the limit → still
   locked out (can't self-bypass). **Must be backup-excluded** (base plan, client step 5): otherwise
   restoring this TV's backup onto a second TV clones the *same* `deviceId`, letting both pass
   `claim_device` as one device.

2. **`DeviceGuardService`** — `app/src/main/java/com/nuvio/tv/core/access/DeviceGuardService.kt`
   (sibling of `AccessControlService`), `@Singleton @Inject(postgrest, authManager, deviceGuardDataStore)`:
   - `suspend fun refreshDeviceClaim()` — same discipline as `refreshAccess()`: **never throws**,
     work inside `withContext(Dispatchers.IO)`.
     - require `authManager.currentUserId != null` (**own** id, not effective); else UNKNOWN (grace).
     - `deviceId = getOrCreateDeviceId()`, `name = "${Build.MANUFACTURER} ${Build.MODEL}"`.
     - `val ok = withJwtRefreshRetry { postgrest.rpc("claim_device", buildJsonObject { put("p_device_id", deviceId); put("p_device_name", name) }).decodeAs<Boolean>() }`.
       - `true` → AUTHORIZED: record success (`lastOkWallMs`/`lastOkElapsedMs`), `deviceLockedOut = false`.
       - `false` → DENIED: `deviceLockedOut = true` (do not record success).
     - on exception → UNKNOWN: monotonic (never clears a set lock); never-checked (both `0L`) → don't
       lock; else the same reboot-aware, rollback-proof `graceExpired(...)` as `refreshAccess()`.
   - `val deviceLockedOut: StateFlow<Boolean>` + `val initialized: StateFlow<Boolean>`; reuse
     `GRACE_MS` / `CHECK_INTERVAL_MS`; gate on `BuildConfig.FEATURE_DEVICE_LIMIT`.
   - Returns a bare `Boolean` → **no `@Serializable` model and no R8 keep rules**.

3. **Wire into the same loop, `onResume`, & gate** in `MainActivity.kt`:
   - Inject `DeviceGuardService`; in the **same** outer-level `LaunchedEffect(authState)` loop that
     calls `refreshAccess()`, also call `refreshDeviceClaim()` (immediately, then each
     `CHECK_INTERVAL_MS`, both inside the `runCatching`); add it to the `onResume` catch-up too. Guard
     on `BuildConfig.FEATURE_DEVICE_LIMIT`.
   - `val deviceLockedOut by deviceGuardService.deviceLockedOut.collectAsState()`; fold into the gate:
     `if (lockedOut || deviceLockedOut) { LockedOutScreen(reason = ...); return@Surface }` (after the
     `!initialized` loading guard, which now waits on **both** services' `initialized`).

4. **`LockedOutScreen`** — add a `reason`/`message` param (same screen, two messages):
   device case shows "This device isn't authorized — contact the administrator". The single
   **Retry** button always calls **both** `refreshAccess()` and `refreshDeviceClaim()` (via the
   `scope`/`lifecycleScope`) — the gate is `lockedOut || deviceLockedOut`, so a one-guard Retry could
   leave the other condition stuck. When both are locked, show the access message first.

## Verification — additions

14. **Server RPC:** with a member token, `rpc/claim_device` with a fresh `p_device_id` → `true`
    and a row appears; a second *different* id for the same token → `false`; re-calling the first
    id → `true` (idempotent). Direct UPDATE to `member_device` with the member token is RLS-denied
    (and grant-revoked).
15. **Second device locks out:** sign the same account in on TV B → within seconds TV B shows
    `LockedOutScreen`; TV A keeps working.
16. **Allow a 2nd / replacement:** set `max_devices=2` → Retry on TV B unlocks it; or `delete`
    the old row + limit back to 1 → old TV locks next check, new TV claims and runs.
17. **Self-bypass attempt:** clear app data on the bound TV → new UUID → account now has 2 rows
    vs limit 1 → claim `false` → locked out.
18. **Backup-restore self-bypass:** back up the bound TV and restore onto a second TV — with the
    DataStores backup-excluded (concrete paths, base step 5), the restored TV generates a *fresh* UUID
    and is over the limit → locked.
19. **Concurrent claim (TOCTOU):** fire two `claim_device` calls for the same fresh token with
    *different* device ids at once → exactly one returns `true` (the advisory lock serializes them);
    the member never ends with 2 rows under a limit of 1.

## Deliverables

Add, at repo root alongside the existing `member_addon_setup.sql`, as the version-controlled schema
source of truth:

- **`member_access_setup.sql`** — the base-plan Server block (table, RLS, revoke grants,
  `get_access_verdict` RPC, seed trigger, backfill) **plus the Rollback overrides** as commented,
  ready-to-paste statements.
- **`member_device_setup.sql`** — the Extension Server block (tables, RLS, revoke grants,
  `claim_device`) plus its rollback override.

Plus, in the app: the `app/build.gradle.kts` per-flavor `FEATURE_ACCESS_CONTROL` /
`FEATURE_DEVICE_LIMIT` fields, and the JVM unit test for `graceExpired(...)` (see *Tests*).
