# Member Activity Telemetry — Prioritized Gap Report

_Review date: 2026-06-09 · Target plan: `docs/superpowers/plans/2026-06-09-member-activity-telemetry.md` · Spec: `docs/superpowers/specs/2026-06-09-member-activity-telemetry-design.md`_

## Executive summary

The plan is well-structured, internally consistent, and follows the proven `member_access`/`member_device` setup-file patterns closely. The accrual/cap math is genuinely TDD-covered, the security model is well-intentioned (RLS + RPC-only writes + JWT-derived uid), and the author documents three deviations. However, the review surfaced a cluster of real, mostly pre-implementation gaps that should be fixed before coding — concentrated in three areas: **(1) the SQL security surface** (inner SECURITY DEFINER functions are PUBLIC-executable, enabling cross-member telemetry forgery), **(2) the Android player wiring** (the plan targets the wrong Media3 callback, which both fails to start/stop heartbeats correctly *and* inflates the `sessions` metric on every pause/resume and every retry/failover), and **(3) build/operational glue** (core is never rebuilt so `apps/web` can't see new exports; the prod smoke test silently no-ops; retention has no working scheduler).

Confirmed gap counts (after adversarial verification): **3 High**, **11 Medium**, **17 Low**. Plus **8 lighter-verified** findings from the completeness critic, **2 acknowledged-by-plan** items (not gaps), and **7 refuted** candidates. Several findings across dimensions describe the same underlying defect and are merged below.

---

## Critical & High gaps

### H1. Inner SECURITY DEFINER functions are PUBLIC-executable → any member can forge another member's telemetry
- **Severity:** High · **Category:** security · **Confidence:** 0.92
- **Where:** `member_telemetry_setup.sql` section 3 (plan Task 1.4 Step 2; inner fns plan:224-353); contrast wrappers plan:447-448, prune plan:1263
- **What's wrong:** `accrue_heartbeat(p_user_id,...)`, `record_session_start(p_user_id,...)`, and `record_error_event(p_user_id,...)` are `SECURITY DEFINER` and take an **explicit `p_user_id`**. Postgres grants `EXECUTE` to `PUBLIC` by default on `CREATE FUNCTION`. The plan revokes/grants only the *wrappers* (`record_heartbeat`/`record_error`) and `prune_telemetry` — the three inner functions get **no** `revoke ... from public`. Because they run as owner (postgres) with `search_path=''`, any authenticated REST client can call `POST /rpc/accrue_heartbeat {"p_user_id":"<victim>",...}` (or `record_error_event`) directly and write/inflate/forge ANY member's watch-time, sessions, or `playback_error` rows — bypassing the entire RLS/RPC-only model and the "uid from JWT (tamper-proof)" guarantee (spec §4/§11). The claim that this is "verified manually, as the existing claim_device is" (plan:68) is a false precedent: `claim_device` is a single self-contained function with its own locked grants (`member_device_setup.sql:83-84`); there is no inner/wrapper split there.
- **Concrete fix:** In section 3, immediately after each inner function add:
  ```sql
  revoke all on function public.accrue_heartbeat(uuid,text,text,int) from public, anon, authenticated;
  revoke all on function public.record_session_start(uuid,text,text) from public, anon, authenticated;
  revoke all on function public.record_error_event(uuid,text,text,jsonb) from public, anon, authenticated;
  ```
  (Grant to no client role — only the owner-running wrappers call them.) Add a Task 1.5 smoke assertion that a member cannot call `accrue_heartbeat` with a foreign uid.

### H2. Session-count inflation: every resume / retry / engine-failover re-emits `session_start` (and the plan targets the wrong Media3 callback)
- **Severity:** High · **Category:** logic · **Confidence:** 0.88
- **Where:** plan Task 2.4 Step 2 (plan:732-735); `PlayerRuntimeControllerInitialization.kt:788` (`onPlaybackStateChanged`) vs `:919` (`onIsPlayingChanged`); `HeartbeatScheduler.start()` plan:698-707
- **Merged from:** "actively playing cannot be read from playWhenReady", "Player wiring covers pause/ended but NOT background", "Session-count inflation", and "Wiring location mismatch (listener rebuilt per-playback)" — all describe one defect cluster in the player wiring.
- **What's wrong:** The plan gates heartbeat start/stop inside `onPlaybackStateChanged(Int)`, keyed on `STATE_READY && playWhenReady==true` (start) and `playWhenReady==false` (stop). This is the wrong callback and produces three compounding bugs:
  1. **Start usually misses.** The controller starts paused and defers play to `onRenderedFirstFrame()` (Init.kt:851-865, 956-958), so `playWhenReady` is normally still `false` when `STATE_READY` fires → heartbeats never begin.
  2. **Stop on pause never fires.** `onPlaybackStateChanged` only delivers the playback STATE; a pure pause toggles `playWhenReady`/`isPlaying` without changing `playbackState`, so the "stop on `playWhenReady==false`" branch never runs → ticker keeps beating through pauses (and through **background** — `isInBackground` is set in `pauseForLifecycle()` but never read by this listener; violates spec §6/§10 "cancel on background").
  3. **`sessions` becomes a play/pause-cycle counter.** If start is (correctly) moved to `onIsPlayingChanged(true)`, `HeartbeatScheduler.start()` emits `session_start` unconditionally on each start, so every pause→resume, rebuffer-driven `initializePlayer()`, DV/safe-audio/timeout/416 retry, and engine failover re-emits `session_start` → `sessions` is inflated, contradicting spec §5.1 ("sessions = session_start count for the day"). The 120s server cap bounds `watch_seconds`, but `sessions` (and `heartbeats`) are not bounded.
- **Concrete fix:** Drive start/stop from `onIsPlayingChanged(isPlaying)` (true→start, false→stop), with `STATE_ENDED`/`releasePlayer()`/`onCleared()` as additional stops, and add a lifecycle/background stop via the existing `pauseForLifecycle`/`ON_PAUSE` path. **Decouple `session_start` from the per-resume ticker:** emit it exactly once per playback via a controller-level once-per-playback flag reset in `initializePlayer()` (mirror the existing `hasRenderedFirstFrame` guard), or give `HeartbeatScheduler.start()` a `suppressSessionStart` flag on restart. Add a test asserting pause→resume and a retry do **not** increment `sessions`.

### H3. `HeartbeatSchedulerTest` "stop halts further beats" does not compile and asserts nothing meaningful
- **Severity:** High · **Category:** test · **Confidence:** 0.90
- **Where:** plan Task 2.3 Step 1 / `HeartbeatSchedulerTest.kt` lines 644-651
- **Merged from:** the two near-identical findings on this test (test-coverage + android-integration dimensions).
- **What's wrong:** The prescribed test body contains `val before = io.mockk.mockkStatic::class // placeholder to keep import` — `mockkStatic` is an overloaded top-level **function**, not a type, so `::class` on it is illegal Kotlin and the whole file fails to compile, breaking the entire Task 2.3 TDD loop (run-to-fail and run-to-pass). It also pins nothing (there is no `import io.mockk.mockkStatic` in the import block). The body ends with a tautological `assertTrue(true)`, and its only real check is `coVerify(atMost = 2) { repo.heartbeat("dev-1", "playback") }` — with `intervalMs=1000` and `advanceTimeBy(1500)` before `stop()`, exactly one playback beat fires, so `atMost=2` would still pass even if a broken `stop()` leaked an extra beat. Note this directly contradicts the plan's own "Placeholder scan" (plan:1334) which claims "No other TODOs."
- **Concrete fix:** Delete the placeholder line and `assertTrue(true)`. After `s.start("dev-1"); advanceTimeBy(1500); s.stop(); advanceUntilIdle()`, then `advanceTimeBy(5000); advanceUntilIdle()`, assert `coVerify(exactly = 1) { repo.heartbeat("dev-1", "playback") }` to prove no further beats fire after `stop()`. Remove the unused mockk import rather than pinning it with a fake reference.

---

## Medium gaps

### M1. Phase 3 never rebuilds `packages/core`, so `apps/web` cannot see the new exports
- **Severity:** Medium (build-time failure; tagged high by reviewer but deterministically caught at the verify step) · **Confidence:** 0.85
- **Where:** plan Task 3.2 Step 3 (plan:1037); Task 3.1 Step 4 (plan:974); `packages/core/package.json`
- **What's wrong:** `@kevbox-admin/core` resolves only to its **built** `dist/index.js` (no `module` field, no src import map, NodeNext, no project references). The new `getMemberActivity`/`listMembersByActivity`/`listGoingDark`/`getFleetStats`/`Db` live in `src/activity.ts` and are re-exported via `src/index.ts`, but Task 3.1 commits only `src/`+`test/` and never builds `dist`. Task 3.2 Step 3 runs only `npm run -w apps/web build`, so the web typecheck/server esbuild bundle fail with "no exported member getMemberActivity" (or silently bundle a stale dist). Core unit tests pass because they import `../src/*.js` (vitest), masking it. Task 4.2 (`pruneTelemetry`) repeats the pattern.
- **Concrete fix:** Add `npm run -w packages/core build` (or root `npm run build`) before the `apps/web` build/typecheck in Task 3.2, and again in Task 4.2.

### M2. Inner SECURITY DEFINER functions are duplicated by manual "paste verbatim" → tested copy can drift from prod copy
- **Severity:** Medium · **Category:** test · **Confidence:** 0.85
- **Where:** plan Task 1.4 Step 2 (plan:430-432, 472) + Task 4.1 Step 3 (plan:1247)
- **What's wrong:** `accrue_heartbeat`/`record_session_start`/`record_error_event` (and `prune_telemetry`) are written once in `test/schema.sql` (where they ARE tested) then hand-pasted into `member_telemetry_setup.sql` (prod, never unit-tested — Task 1.4 "No automated test"). The load-bearing capped-accrual/on-conflict-sum logic has TDD only on the schema.sql copy. This is a *new* cross-file duplication (the existing `claim_device` is not duplicated into schema.sql at all). The prod copy is exercised end-to-end exactly once by the Task 1.5 manual smoke (which itself has a defect — see M3).
- **Concrete fix:** Single-source the bodies (a shared `.sql` fragment included by both files) or add a CI check that extracts and diffs the function bodies across the two files. Minimum: a Task 1.5 manual step re-running the accrual/session/error expectations against the prod-applied functions.

### M3. Task 1.5 manual smoke test silently no-ops in the Supabase SQL editor (`auth.uid()` is NULL)
- **Severity:** Medium · **Category:** operational · **Confidence:** 0.92
- **Where:** plan Task 1.5 Step 2 (plan:483-484); RPC body plan:438-446
- **What's wrong:** Task 1.5 says "as an authenticated test member … call `select record_heartbeat(...)`" in the Supabase SQL editor. The SQL editor runs as the postgres/owner role with no JWT, so `auth.uid()` is NULL, and `record_heartbeat` begins `if v_uid is null then return;` → it silently writes nothing. The prescribed expected outcome (a `member_activity_daily` row with sessions=1, heartbeats≥2, watch_seconds>0) FAILS even on a correct deploy — the single load-bearing prod verification for the whole backend (Task 1.4 defers to it).
- **Concrete fix:** Exercise the inner functions directly in the editor (they take explicit `p_user_id`, no JWT): `select public.accrue_heartbeat('<real-uuid>','smoke-dev','1.0.0',120);` then re-run ~30s later and check the row (first beat seeds the baseline and accrues 0, so two calls are needed). Document that Task 1.5 verifies the SQL plumbing while Task 2.4's sideload smoke verifies the JWT wrapper end-to-end. Alternatively simulate the JWT in-editor with `set local request.jwt.claims = '{"sub":"<uuid>","role":"authenticated"}'`.

### M4. Daily-prune "operator's existing scheduler" does not exist; Phase 4 retention has no working automation when pg_cron is absent
- **Severity:** Medium · **Category:** operational · **Confidence:** 0.88
- **Where:** plan Task 4.3 Step 1 (plan:1311); spec §11/§14 (lines 145, 170)
- **What's wrong:** Both branches of Task 4.3 are unverified. (1) pg_cron is flagged as an open question in spec §14 and is never verified before Phase 4 depends on it. (2) There is NO existing scheduler in the repo — no cron, systemd `.timer`, node-cron, or `setInterval`; the only systemd unit is the long-running Fastify server. So if pg_cron is disabled (likely on stock Supabase), retention never runs and `member_event` grows past the 90-day promise.
- **Concrete fix:** Before Phase 4, verify pg_cron: `select * from pg_extension where extname='pg_cron';`. If absent/non-enableable, ship a concrete artifact in `deploy/` — either (a) a systemd `.timer` + oneshot unit mirroring `kevbox-admin.service`, or (b) preferably an in-process node-cron job inside the always-running Fastify server that calls `pruneTelemetry(db)` directly against the pool (no HTTP, no auth — also sidesteps M-low "prune route is behind requireAdmin").

### M5. `record_heartbeat` accepts ANY non-`session_start` `p_kind` as playback — contradicts spec §7 allowlist
- **Severity:** Medium · **Category:** assumption-broken / consistency · **Confidence:** 0.85
- **Where:** `member_telemetry_setup.sql` wrapper (plan:441-445); spec §7 (spec:107)
- **Merged from:** the sql-correctness finding + the spec-consistency finding on the same wrapper.
- **What's wrong:** Spec §7 requires "Validates p_kind against an allowlist; ignores unknown kinds." The wrapper is a binary `if p_kind='session_start' then ... else accrue_heartbeat(...) end if`, so any value (typo, `'pause'`, `'buffering'`, garbage) is counted as a playback heartbeat and accrues watch-time. The plan's own coverage checklist (plan:1327) falsely marks "§7 RPC contracts ✓". Bounded by the 120s cap and uid-from-JWT (no injection), but it inflates `watch_seconds` and violates the spec. Not under Deviations.
- **Concrete fix:** Make the wrapper allowlist-driven: `if p_kind='session_start' then ... elsif p_kind='playback' then perform accrue_heartbeat(...); else return; end if;` so unknown kinds are no-ops.

### M6. Multi-device concurrency weakens the sharing tripwire: two devices on one account double the daily total above the "impossible 18h" ceiling
- **Severity:** Medium · **Category:** logic · **Confidence:** 0.78
- **Where:** `accrue_heartbeat` upsert (plan:237-243); spec §6/§8 (spec:98,117); `access.ts` `setMaxDevices` (76-88)
- **What's wrong:** `member_heartbeat` is per `(user_id, device_id)` but `member_activity_daily` is keyed `(user_id, day)` and sums across all devices. The 120s cap bounds *one device* to ~86400s/day, but with `max_devices≥2` (supported) or with the device-limit OFF (Deviation 1), two concurrent devices push a day total to ~172800s — far above the 64800s (18h) sharing-suspect threshold. So the very signal the design leans on fires on **legitimate** multi-device accounts and the "18h impossible for one viewer" framing is wrong. Advisory-only flag, so capped impact. Not flagged as a deviation.
- **Concrete fix:** Either (a) compute the threshold as `18h * max_devices`, (b) base impossible-hours on a `(user_id, device_id, day)` rollup, or (c) explicitly document the flag as advisory and known to false-positive on multi-device accounts (note it in §8 / Deviation 3).

### M7. Player wiring uses a suspend `getOrCreateDeviceId()` synchronously at non-suspend callback sites
- **Severity:** Medium · **Category:** logic · **Confidence:** 0.85
- **Where:** plan Task 2.4 Step 1/Step 3; `DeviceGuardDataStore.kt:60` (`suspend fun getOrCreateDeviceId`)
- **What's wrong:** `getOrCreateDeviceId()` is `suspend` (DataStore I/O). The plan obtains the device id "the same way DeviceGuardService does" and then uses a bare `deviceId` synchronously inside the non-suspend `Player.Listener` callbacks (`heartbeatScheduler.start(deviceId)`, `telemetryRepository.error(deviceId, ...)`). There is no plan step to resolve+cache it, so as written it won't compile / is a dangling reference.
- **Concrete fix:** Resolve the device id once (in `initializePlayer`'s existing `scope.launch` or a controller init coroutine) into a cached `@Volatile var telemetryDeviceId: String?`, and no-op `start()`/`error()` when null. Add an explicit plan step.

### M8. `PlayerRuntimeController` is hand-constructed, not Hilt-injected — Task 2.4 Step 1 "constructor @Inject" guidance is wrong
- **Severity:** Medium · **Category:** assumption-broken · **Confidence:** 0.90
- **Where:** plan Task 2.4 Step 1 (plan:730); `PlayerRuntimeController.kt:59-89`; `PlayerViewModel.kt:36/76/105`
- **Merged from:** the two duplicate findings (codebase-assumptions + android-integration) on the controller DI.
- **What's wrong:** The instruction says to add `@Inject` "matching how the controller receives other singletons." But `PlayerRuntimeController` is a plain class with ~28 positional params and no Hilt annotation; it is manually instantiated by `PlayerViewModel` (the `@HiltViewModel @Inject` class) with `scope = viewModelScope`. The controller receives **nothing** via Hilt, so annotating it is inert.
- **Concrete fix:** Rewrite Step 1: add `TelemetryRepository` (and `DeviceGuardDataStore` for the device id) to `PlayerViewModel`'s `@Inject` constructor, add matching params to `PlayerRuntimeController`'s constructor, and pass them at the `PlayerViewModel.kt:76` call site. Build the `HeartbeatScheduler` on the controller's existing `scope`.

### M9. Scheduler `stop()` not wired to player release/`onCleared` — ticker can outlive playback within the ViewModel scope
- **Severity:** Medium · **Category:** logic · **Confidence:** 0.72
- **Where:** plan Task 2.4 Step 2; `PlayerRuntimeController.kt:204-206, 529-539`; `PlayerViewModel.kt:105`
- **What's wrong:** `HeartbeatScheduler` runs on `scope = viewModelScope`, cancelled only at `onCleared()` — not on pause or player release. `releasePlayer()`/`stopAndRelease()`/`onCleared()` add no `scheduler.stop()` (grep `scope.cancel` = 0 hits), and the plan's stop relies on `onPlaybackStateChanged` (which, per H2, doesn't fire on pause). So a paused/backgrounded-but-not-cleared player keeps the `while(isActive)` loop emitting `playback` beats every 60s, accruing phantom watch_seconds. (Closely related to H2; fix together.)
- **Concrete fix:** Explicitly call `heartbeatScheduler.stop()` from `releasePlayer()`/`stopAndRelease()`, `onCleared()`, and `onIsPlayingChanged(isPlaying=false)`. Do not rely on `onPlaybackStateChanged`. Add a verification note that paused/backgrounded playback emits no beats.

### M10. ZERO automated coverage of the security model (RLS, revoke/grant, RPC `auth.uid` gating) — harness connects as postgres superuser
- **Severity:** Medium · **Category:** test · **Confidence:** 0.92
- **Where:** plan Phase 1 (Task 1.4/1.5); `helpers.ts:8`; `test/schema.sql:43-48`
- **What's wrong:** The entire write-path security model (RLS read-own, `revoke insert/update/delete from anon/authenticated`, `grant select to kevbox_admin`, the JWT wrappers' null-uid no-op) has no automated coverage and is structurally untestable here: the test DB connects as `postgres` (RLS bypassed), `schema.sql` deliberately omits all RLS/policies/grants and has no `anon`/`authenticated`/`kevbox_admin` roles, and the wrappers exist only in the prod file. The only verification is the manual Task 1.5 smoke. The Self-review (plan:1331) maps §11 to "RLS+grants ✓" as if covered. (This is the same untestable-grant surface noted in the acknowledged section, but the plan does NOT flag it as a coverage gap.) A botched/typo'd grant passes all vitest yet 500s in prod with "permission denied".
- **Concrete fix:** Document the untestable-here boundary (matching the `claim_device` precedent) AND turn Task 1.5 into a written security checklist verifying: (a) `authenticated` cannot directly INSERT/UPDATE/DELETE the three tables, (b) `anon` SELECT returns nothing, (c) `record_heartbeat` with null `auth.uid()` is a no-op. Optionally add one CI test connecting as a non-superuser role to assert the revokes.

### M11. ROLLBACK kill-switch SQL `language sql as $$ select $$` is invalid and will not run
- **Severity:** Medium · **Category:** operational · **Confidence:** 0.90
- **Where:** `member_telemetry_setup.sql` ROLLBACK block (plan:466-467)
- **What's wrong:** The documented instant kill-switch `create or replace function public.record_heartbeat(text,text,text) returns void language sql as $$ select $$;` is invalid SQL — a bare `select` with no target list raises a syntax error in all Postgres versions. The sibling rollbacks work only because they have non-empty target lists (`select 'ALLOWED'`, `select true`). An operator pasting this during an incident gets a syntax error and ingestion is NOT stopped.
- **Concrete fix:** Use a valid void no-op: `create or replace function public.record_heartbeat(text,text,text) returns void language plpgsql as $$ begin end $$;` (same for `record_error`). Validate against Postgres 15/16 before finalizing the runbook.

### M-test-1. `getMemberActivity` null-contract & boundary windows under-tested
- **Severity:** Medium · **Confidence:** 0.82 · **Where:** plan Task 3.1 (activity.ts:898-899); tests 788-810
- **What's wrong:** Null is returned only when `d30===0 && today===0 && last_heartbeat==null`. A `session_start`-only day sets `last_heartbeat` (so a non-null all-zero object is returned despite watch_seconds 0) — untested and contradicts the line-898 "no data → null" intent. Strict `> date - 30`/`- 7` windows exclude exactly day-30/day-7, but tests seed only days 0/3/20, so boundaries are unpinned.
- **Fix:** Add tests: (a) `session_start`-only day → assert intended null-vs-object; (b) rows at exactly day-7 and day-30 to lock strict-`>`; (c) stale-heartbeat + zero-recent-watch member.

### M-test-2. sharing-suspect & going-dark tested only deep inside thresholds — no boundary/false-positive coverage
- **Severity:** Medium · **Confidence:** 0.83 · **Where:** plan tests 802-809 (sharing) + 826-840 (going-dark); activity.ts:906,940
- **What's wrong:** `sharingSuspect` (≥64800) is tested only at 19h (clearly over); never ==64800, ==64799, or a 17h day. Given the cap makes 18h structurally near-impossible (Deviation 3), the **false-positive** direction (legitimate heavy use must stay false) is the load-bearing risk and is wholly untested. `listGoingDark` uses strict `sum=0`; tested only 40d-ago vs yesterday — never a tiny non-zero in-window watch (must NOT be dark) or the 14d edge.
- **Fix:** Add boundary tests (==64800 true, ==64799 false, 17h false; going-dark with 1s in-window must not appear; pin the 14d-edge predicate).

### M-test-3. No test for multi-device watch-time accrual summing into one daily row
- **Severity:** Medium · **Confidence:** 0.80 · **Where:** plan Task 1.2 tests (139-210)
- **What's wrong:** All Task 1.2 tests use a single `dev-1`; no test seeds two `device_id`s for one uid in the same day to confirm `watch_seconds` sums and each baseline is tracked independently — load-bearing for the §8 sharing case (see M6).
- **Fix:** Seed baselines for `(uid,dev-1)` and `(uid,dev-2)` each 60s in the past, accrue both, assert `daily.watch_seconds==120`, `heartbeats==2`, and both `member_heartbeat` rows updated independently.

---

## Low gaps (terser)

- **L1. No index on `member_activity_daily.day`** (plan:390-395) — fleet/leaderboard/going-dark queries (no user_id predicate) seq-scan. Negligible at current scale. Optional: `create index ... on member_activity_daily (day)` (or BRIN). _Conf 0.80._
- **L2. `record_error_event` stores `message` (left 300) not spec's `message_short`, and the value is truncated but not scrubbed** (plan:348-351; spec §7/§11) — a raw `PlaybackException.message` could embed a URI; spec forbids URLs/titles. The codebase's normal formatter is URL-free, so leak likelihood is low. Rename to `message_short`, shorten cap (~120), and/or strip URL substrings server-side; or document as a deviation. _Conf 0.72._ (Naming half overlaps the §7 consistency finding.)
- **L3. `accrue_heartbeat` read-then-upsert not serialized per `(user,device)`** (plan:230-247) — concurrent same-key beats can double-count ≤120s; `claim_device` uses `pg_advisory_xact_lock`, this doesn't. The cited `withJwtRefreshRetry` is sequential, so realistic trigger is unlikely. Add the advisory lock or document the ≤120s race as acceptable. _Conf 0.60._ (Test counterpart: no concurrency/TOCTOU test — add a comment noting the ON CONFLICT upsert is atomic so no lock is needed, optionally an interleaved test. _Conf 0.60._)
- **L4. `prune_telemetry` EXECUTE grant to `kevbox_admin` is untested** (plan:1263-1264, 1300) — test calls it as superuser; no `kevbox_admin` role in test DB. Function/model is correct; gap is verification only. Add a Task 4.3 manual smoke: as `kevbox_admin` `select public.prune_telemetry(90,396)` succeeds; member/anon JWT cannot. _Conf 0.65._
- **L5. `prune_telemetry` test misses the exact retention boundary day** (plan:1224-1238) — strict `<` keeps at-boundary rows, but seeds are 200/2 and 500/2 vs 90/396 (far from edge). Add rows at day-90/day-89 and day-396/day-395. _Conf 0.72._
- **L6. `record_session_start` daily upsert relies on column defaults; compose-with-playback path unverified** (plan:328-332; tests 277-292) — asserts only `sessions==1`, never that the two upserts compose (sessions bumped while accrued watch_seconds preserved). Correct by construction (disjoint columns), but add a session_start→accrue_heartbeat same-day test. _Conf 0.78._
- **L7. POST /api/activity/prune is behind `requireAdmin`** (plan:1300; app.ts:57-64) — a headless scheduler has no admin JWT, so the Task 4.3 HTTP fallback is uncallable unattended. Prefer in-process `pruneTelemetry(db)` or pg_cron (see M4); if HTTP is wanted, use a shared-secret loopback endpoint. _Conf 0.83._
- **L8. Spec §9.1 fields silently dropped from the Activity tab: `access_active`, `device_count`, per-member `going_dark`** (spec §9.1; plan:869-879,1089-1105) — Deviation 2 omits them without note. `access`/`device_count` are already shown in the same panel's Access tab, so impact is low; the truly-missing item is a recorded decision (a 4th deviation) plus the per-member `going_dark` flag. _Conf 0.85._
- **L9. `last_seen` vs `last_heartbeat` conflation** (spec §5.3/§9.1; plan Deviation 1) — the UI labels the playback-only `lastHeartbeatAt` as "Last seen" (plan:1101,1188); spec's `last_seen` was the broader app-open device timestamp, never surfaced. Going-dark classification correctly keys on `watch_seconds=0` (not heartbeat), so no false churn-listing — it's a label mismatch. Relabel "Last played"/"Last heartbeat", or also surface `member_device.max(last_seen)`. _Conf 0.70._
- **L10. Spec §7 "batch sizes/payloads bounded server-side to prevent log-flooding" not implemented** (spec §7:111) — no batching (so "batch sizes" is moot) and no rate-limit/insert-throttle; `record_error_event`/`record_session_start` insert one uncapped row per call. Payload size IS bounded. Sole caller is the first-party client; pruning bounds long-term growth. Add a per-(user,device,day) session_start cap / daily error ceiling, or document as a deferred deviation. _Conf 0.70._
- **L11. `FEATURE_TELEMETRY` guidance says "per build type" but the mirrored flag is per product FLAVOR (full=true / playstore=false)** (plan Task 2.1; build.gradle.kts:131-156) — terminology error; the grep-anchored "add next to FEATURE_DEVICE_LIMIT" instruction self-corrects placement, and `compileFullDebugKotlin` only exercises `full` (a wrong `playstore` value wouldn't be caught). Reword to name productFlavors explicitly; state `false` for `playstore` to preserve the no-family-Supabase guarantee. _Conf 0.90._ (Two duplicate findings merged.)
- **L12. Spec §11 notice is a Phase 4 step while `FEATURE_TELEMETRY` defaults true in Phase 2** (spec §11; plan:501 vs 1312) — each phase is independently shippable, so Phase 2 alone collects in prod before the lawful-basis notice exists. Durations-only data, family fork, "engineering note not legal advice." Move the notice into Phase 2 or default the flag false until the notice ships. _Conf 0.60._
- **L13. `withJwtRefreshRetry` / `runCatching` swallow `CancellationException`** (plan Task 2.2/2.3) — `catch (e: Exception)` and `runCatching` catch `CancellationException` (violates the project Kotlin rule + structured concurrency). Real impact is near-zero (the `while(isActive)` loop exits cleanly; the next `delay()` rethrows). Note: the existing `DeviceGuardService` also swallows it, so the plan is consistent with the (imperfect) precedent. Add `catch (e: CancellationException) { throw e }` before the broad catch. _Conf 0.70._
- **L14. TelemetryRepository attributes telemetry to `currentUserId`, not `getEffectiveUserId()` (sync-owner)** (plan Task 2.2; AuthManager.kt:112/123) — no code defect; matches `DeviceGuardService`. A sync-linked TV records under its own uid, which may or may not be the desired roll-up. Confirm and document the attribution decision. _Conf 0.55._
- **L15. First-run: going-dark shows the ENTIRE active fleet and DAU/WAU/MAU read 0 until clients beat** (plan:932-948; spec §9.2) — no back-fill is possible (forward-only durations); members on un-updated builds without `FEATURE_TELEMETRY` stay in the churn list forever. Correct behavior, misleading for ~14 days. Add a Task 3.5 caveat; optionally guard `listGoingDark` to "has ≥1 heartbeat/activity row ever." _Conf 0.72._
- **L16. ROLLBACK restore note says "Re-run section 4" but the wrappers depend on section-3 inner functions** (plan:465-469) — re-running section 4 alone doesn't recreate the inner fns; loud "function does not exist" error, not silent. Reword to "Re-run the ENTIRE member_telemetry_setup.sql (idempotent) to restore." (truncate order is fine — tables FK only to auth.users). _Conf 0.60._

---

## Newly surfaced by completeness critic (lighter verification)

These came from the completeness pass and had lighter codebase verification than the items above; treat as plausible-but-confirm.

- **C1 (Medium, logic).** Leaderboard route passes `NaN`/negative to SQL `LIMIT`: `Math.min(Number(req.query.limit ?? 25), 100)` — `?limit=abc` → `NaN`, `?limit=-5` → no lower bound; Postgres 500s. Use `Number.isFinite`, clamp to `[1,100]`, default 25; add tests. _(plan:1018, consumed at 925)_
- **C2 (Medium, logic).** `HeartbeatScheduler` has no session de-dup contract — same defect family as **H2** (`stop()` nulls the job, next `start()` re-emits `session_start` with no debounce; no stop-then-start test). Fold into the H2 fix. _(plan:698-710)_
- **C3 (Medium, test).** `listGoingDark`/`getFleetStats` test coverage narrower than prod: test schema omits the `member_access` seed trigger+backfill (`schema.sql:46-48`), so the realistic trigger-seeded enumeration is never exercised — a change joining `auth.users` instead of `member_access` would pass tests yet diverge in prod. Add a member + default `member_access` row test (active below threshold → appears; inactive → excluded); document the omission.
- **C4 (Low, logic).** `getMemberActivity` reports `lastAppVersion` via text `max()`, not most-recent — `1.9.0`→`1.10.0` displays `1.9.0`; a downgrade displays the older string. Derive from `member_heartbeat` ordered by `last_heartbeat desc`, or `(array_agg(... order by day desc))[1]`; add a non-monotonic-version test. _(plan:889)_
- **C5 (Low, logic).** No CHECK constraint on `member_event.kind` — spec §5.2 enumerates `session_start|playback_error` but neither schema enforces it; unknown kinds persist silently. Add `check(kind in ('session_start','playback_error'))` to both files, or document the open-set decision. _(plan:108,405)_
- **C6 (Low, consistency).** App.tsx view union + `MemberDetail` Tab/props lack the new members; `FleetView` wiring won't type-check as written (`view` is `member|bulk`; Tab is `addons|access`; `selectMember` takes a `MemberSummary` not an id, and a leaderboard `userId` may not be loaded). Widen `view` to include `fleet`, widen `Tab`, add an `activity` prop, and give `onOpenMember` a body reusing the deep-link `api.getMember(id)` path. _(App.tsx:27,69,105; MemberDetail.tsx:12-29)_
- **C7 (Low, consistency).** New core types re-exported only in prose (Task 3.3); the only typecheck (Task 3.2 Step 3) runs before the components exist, and Tasks 3.4/3.5 have only manual UI verification — a missing `export type` line isn't caught pre-commit. Make the `api.ts`/`index.ts` re-export explicit as code and add a web build/tsc step at the end of Task 3.5. (Related to **M1**.)
- **C8 (Low, operational).** Privacy-notice step not coupled to `FEATURE_TELEMETRY=true` across separate release trains — same concern as **L12**; merge.

---

## Acknowledged by the plan (verified — not gaps)

- **Test grants/RLS untested (no `kevbox_admin` role in test DB).** The plan/spec acknowledge the superuser-harness limitation and follow the `claim_device`/`member_device` precedent (which is likewise untested by automation). _Note: M10 above is the part the plan does NOT acknowledge — the missing explicit coverage-gap doc + null-uid/direct-DML manual checks._
- **Phase 3 reads telemetry tables that only get `kevbox_admin` grants in Task 1.5 (prod).** The cross-phase "independently shippable, Phase 1 unblocks 2/3" ordering dependency is stated in spec §13; the BYPASSRLS-skips-policies-not-grants model is documented in `deploy/README.md` and `member_device_setup.sql`.

---

## Refuted candidates (reviewer misreads — for transparency)

- **"Views need `security_invoker=off` but `member_addon_v` uses ON."** Misread: different threat models (admin-only owner-privileged view vs member-readable RLS-respecting view). Moot anyway — Deviation 2 drops the SQL view for TS aggregation.
- **"Tests import `./helpers.js` but file is `helpers.ts`."** Not a defect — `.js` specifiers under NodeNext are the required idiom; existing passing tests use it. (Appeared twice; both refuted.)
- **"`session_start` + first playback beat double-counts / drops in-progress time."** Refuted: the baseline reset is *correct* (discards pause gaps per spec §6 "backgrounding never inflate"); the reviewer's fix #1 would actively over-count by crediting paused time.
- **"Least-active leaderboard omits zero-activity members."** Refuted: zero-activity members are by design the going-dark list's domain (spec §9.2), which IS implemented and tested.
- **"`FEATURE_DEVICE_LIMIT` under-covers debug builds."** Refuted: it's per-flavor, and `fullDebug` merges the `full` flavor field; `MainActivity.kt` already references it unconditionally so the project wouldn't compile otherwise. (Wording nit captured separately in **L11**.)
- **"first playback beat after session_start violates spec's 'first beat accrues 0'."** Refuted: spec §6 never says first beat accrues 0; that wording is unit-test-only. Implementation matches spec.

---

## Recommended pre-implementation fixes (ordered checklist)

1. **[H1] Lock down the three inner SECURITY DEFINER functions** — add `revoke all ... from public, anon, authenticated` after each in `member_telemetry_setup.sql` section 3; add a Task 1.5 foreign-uid smoke assertion.
2. **[H2 + M9 + C2] Rewrite the Android player wiring** — drive start/stop from `onIsPlayingChanged`, add lifecycle/background + `releasePlayer`/`onCleared` stops, and emit `session_start` exactly once per playback (once-per-playback guard reset in `initializePlayer`). Add pause→resume and retry tests asserting `sessions` does not increment.
3. **[H3] Fix `HeartbeatSchedulerTest` "stop halts further beats"** — remove the non-compiling `mockkStatic::class` placeholder and `assertTrue(true)`; assert `coVerify(exactly = 1)` after `stop()`.
4. **[M1 + C7] Rebuild `packages/core` before the web build** — add `npm run -w packages/core build` to Task 3.2 and Task 4.2; make the `api.ts`/`index.ts` re-exports explicit code + add a final web tsc step.
5. **[M3] Fix the Task 1.5 prod smoke test** — call the inner `accrue_heartbeat('<uuid>',...)` twice ~30s apart (auth.uid() is NULL in the SQL editor), and split SQL-plumbing vs JWT-wrapper verification across Task 1.5 / Task 2.4.

_Also fix before/with implementation: M5 (p_kind allowlist), M8 (PlayerViewModel DI), M11 (rollback no-op body), M4 (real prune scheduler / verify pg_cron). M2/M10/M6 are doc + test-hardening items to land alongside Phase 1._

---

_Report path: `/Users/kevin/Projects/NuvioTV/docs/superpowers/reviews/2026-06-09-member-activity-telemetry-gaps.md`_
