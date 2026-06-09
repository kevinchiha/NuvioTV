# KevBox Member Activity Telemetry — Design Spec

**Date:** 2026-06-09
**Status:** Approved (brainstorming) — pending implementation plan
**Repos in scope:** `~/Projects/NuvioTV` (Android app + Supabase SQL) and `~/Projects/NuvioTV/kevbox-admin` (admin UI)
**Supabase project:** `scmqdptagksltnwiveyh`
**Sibling docs:** `member_access_setup.sql`, `member_device_setup.sql`, `member_addon_setup.sql`, `plans/MEMBER-ACCESS-ADMIN-PLAN.md`

---

## 1. Purpose

KevBox operators want member-level operational insight from the existing Supabase backend, driving four outcomes:

1. **Retention / churn** — detect an active member whose engagement drops to ~zero ("going dark"). Complements (does **not** duplicate) the expiring-soon data already in www.kevbox.dev.
2. **Support & ops** — one screen per member: active?, device, app version, recent playback errors — so "it's not working" tickets resolve in seconds.
3. **Activity rankings** — daily / weekly / monthly watch-time to identify the most- and least-active members.
4. **Abuse / sharing enforcement** — derived signals (no new collection) on top of the existing one-device-per-account limit.

## 2. Guiding principle — "how much," never "what"

Activity rankings, retention, support, and sharing detection need only **durations + technical fields**. They do **not** need to know *which titles* a member watched. This design therefore collects **no content/title/search data at all** — a large reduction in privacy sensitivity and breach surface. This is a firm decision, not a deferral.

## 3. Non-goals

- ❌ No content/title/search/watch-history capture (ever, under this design).
- ❌ No expiring-soon list (www.kevbox.dev already provides it).
- ❌ No IP / geolocation capture (the sensitive part of abuse detection). May be reconsidered later as an explicit opt-in; out of scope here.
- ❌ No new admin auth model — reuse the existing `kevbox_admin` (BYPASSRLS) role.

## 4. Architecture overview

Everything follows the **existing** KevBox data pattern, identical to `member_access`/`member_device`:

```
Android client ──record_heartbeat() RPC──▶ Postgres (RLS, RPC-write-only)
                 (SECURITY DEFINER,           │
                  uid from JWT)                ├─ member_activity_daily   (authoritative rollup)
                                              └─ member_event            (notable events, pruned)
                                                       │
kevbox-admin ◀── owner-privileged views ◀──────────────┘
 (kevbox_admin role)   (member_activity_v, joins kevbox_auth_users for email)
```

- **Members** read only their own rows (RLS `auth.uid() = user_id`); all writes go through `SECURITY DEFINER` RPCs. Direct DML revoked from `anon`/`authenticated`.
- **Admin** reads through owner-privileged views granted to `kevbox_admin` (mirrors `kevbox_auth_users` / `member_addon_v`). Never exposes telemetry via a plain anon-readable view.
- **No cron required for the core metric** — watch-time is upserted directly by the heartbeat RPC. `pg_cron` (or an admin-triggered prune RPC) is used only for retention pruning.

## 5. Data model (new)

### 5.1 `member_activity_daily` — authoritative rollup
```
member_activity_daily (
  user_id          uuid    references auth.users(id) on delete cascade,
  day              date    not null,                  -- UTC day bucket
  watch_seconds    int     not null default 0,        -- capped accumulation (see §6)
  heartbeats       int     not null default 0,
  sessions         int     not null default 0,        -- session_start count for the day
  last_app_version text,
  updated_at       timestamptz not null default now(),
  primary key (user_id, day)
)
```
Weekly / monthly / leaderboard figures are `SUM(watch_seconds)` over a date range — **no separate W/M tables**.

### 5.2 `member_event` — append-only notable events (pruned)
```
member_event (
  id          bigint generated always as identity primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
  device_id   text,
  occurred_at timestamptz not null default now(),
  kind        text not null,                          -- 'session_start' | 'playback_error'
  app_version text,
  detail      jsonb                                   -- bounded; NEVER secrets/URLs/titles
)
```
Stores only **notable** events — **not** every heartbeat (which would be high-volume). Indexed on `(user_id, occurred_at desc)` and `(kind, occurred_at desc)`. `detail` is size-bounded and validated to exclude secrets (no debrid tokens, addon URLs, or content identifiers).

### 5.3 `member_device` — additive columns
```
alter table public.member_device
  add column if not exists last_heartbeat timestamptz,   -- distinct from last_seen
  add column if not exists app_version    text;
```
`last_seen` keeps its existing device-limit semantics; `last_heartbeat` drives the watch-time delta (§6) so the two concerns stay decoupled.

## 6. Watch-time algorithm

The app sends a heartbeat (`kind='playback'`) roughly every **60 s** *while actively playing*. On each heartbeat the RPC:

1. Resolves `uid` from the JWT (tamper-proof).
2. Computes `delta = now() − member_device.last_heartbeat` for this `(uid, device_id)`.
3. Accrues `add = least(delta, CAP)` where **CAP = 120 s** (≈2× interval). A null/old `last_heartbeat` (new session, backgrounded gap) therefore contributes at most `CAP`, not the whole gap.
4. `upsert member_activity_daily (uid, current UTC day) set watch_seconds = watch_seconds + add, heartbeats = heartbeats + 1`.
5. Updates `member_device.last_heartbeat = now()`, `app_version = p_app_version`.

**Consequences:** the 120 s cap makes sustained `watch_seconds` > ~18 h/day structurally impossible for a single device — which doubles as the **sharing tripwire** (§8). Pauses/backgrounding never inflate time.

## 7. RPC contracts (client write path)

All `SECURITY DEFINER`, `set search_path = ''`, `revoke all from public, anon`, `grant execute to authenticated` — mirroring `claim_device`.

- `record_heartbeat(p_device_id text, p_app_version text, p_kind text default 'playback') returns void`
  - `p_kind='playback'` → accrue watch-time per §6.
  - `p_kind='session_start'` → increment `sessions`, insert a `session_start` event, set `last_heartbeat`.
  - Validates `p_kind` against an allowlist; ignores unknown kinds.
- `record_error(p_device_id text, p_app_version text, p_detail jsonb) returns void`
  - Inserts a `playback_error` `member_event`. `p_detail` is size-capped and key-allowlisted (e.g. `code`, `message_short`) — never raw URLs/titles/secrets.

Batch sizes and payloads are bounded server-side to prevent log-flooding.

## 8. Abuse / sharing signals (derived — no new collection)

The one-device-per-account limit (`member_device` + `claim_device`) already blocks the obvious case. Residual derived signals, surfaced as a **suspicion flag** on the member panel:

- **Impossible hours:** `watch_seconds` for a day at/over the §6 ceiling sustained across days.
- **Device-replacement churn:** frequent `claim_device` slot turnover (claims/deletes) for one account.

No IP/geo. The flag is advisory (an operator reviews), not an automated lock.

## 9. Admin read path & UI (kevbox-admin)

### 9.1 Owner-privileged views (granted to `kevbox_admin` only)
- `member_activity_v` — one row per member: `email` (via `kevbox_auth_users`), `last_seen`, `last_app_version`, `watch_seconds_today/_7d/_30d`, `sessions_7d`, `errors_7d`, `access_active` (from `member_access`), `device_count`, `going_dark` flag, `sharing_suspect` flag.
- Created `with (security_invoker = off)` (owner-privileged), `revoke all from anon, authenticated`, `grant select to kevbox_admin` — exactly like `kevbox_auth_users`.

### 9.2 UI surfaces
- **Fleet dashboard:** DAU / WAU / MAU, total watch-hours, app-version distribution, error rate, # going-dark.
- **Leaderboards:** most active / least active, with a D / W / M toggle.
- **Going-dark list:** `access_active = true` AND `watch_seconds_7d ≈ 0` → churn risk (distinct from expiring-soon).
- **Per-member panel:** plugs into the existing `?member=` deep-link. Shows watch-time sparkline, last-seen, device + app version, recent errors, sharing-suspicion flag.

## 10. Android client work

- **Heartbeat scheduler** bound to player lifecycle: emit `session_start` on playback start; emit `playback` every ~60 s while playing; cancel on pause / stop / background. Reuses the existing Supabase client path used for `claim_device()`.
- **Error reporting:** call `record_error` from the player error callback with a bounded `detail` (error code + short message only).
- App version from `BuildConfig.VERSION_NAME`, sent with every call.
- Fail-soft: telemetry failures are swallowed (logged locally only) and never block playback or auth.

## 11. Privacy, security, retention

- **Data minimization:** durations + technical fields only. No content, no search, no secrets, no IP. `member_event.detail` is key-allowlisted and size-bounded.
- **Isolation:** members read only their own rows (RLS); writes only via RPC; admin only via owner-privileged views + `kevbox_admin` grants. Direct DML revoked from `anon`/`authenticated`.
- **Retention:** `member_event` pruned at **90 days**; `member_activity_daily` aggregates kept **~13 months** (year-over-year) then pruned. Pruning via `pg_cron` or an admin-triggered `prune_telemetry()` RPC.
- **Notice / lawful basis:** a one-line in-app / onboarding notice that the box reports activity & diagnostics for service operation. GDPR basis = legitimate interest (operational/security telemetry, durations only, no content) — defensible. *(Engineering note, not legal advice; operator confirms final wording.)*

## 12. Tunable defaults

| Knob | Default | Notes |
|------|---------|-------|
| Heartbeat interval | 60 s | client-side |
| Accrual cap (CAP) | 120 s | ≈2× interval; also the sharing ceiling |
| Raw event retention | 90 days | `member_event` |
| Aggregate retention | 13 months | `member_activity_daily` |
| Day bucketing | UTC | simplest; revisit if per-member TZ ranking matters |

## 13. Rollout phases

1. **Schema + RPCs** — `member_activity_daily`, `member_event`, `member_device` columns, `record_heartbeat`, `record_error`, owner-privileged `member_activity_v`. Idempotent setup file matching the existing `member_*_setup.sql` style. Includes RLS, grants, rollback block.
2. **Android instrumentation** — heartbeat scheduler + error hook + app-version plumbing. Fail-soft.
3. **Admin UI** — fleet dashboard, leaderboards, going-dark list, per-member panel via `?member=`.
4. **Retention** — `prune_telemetry()` + `pg_cron` schedule (or admin-triggered).

Each phase is independently shippable; Phase 1 unblocks 2 and 3.

## 14. Open questions

- **Heartbeat backend shape:** single `record_heartbeat` with a `p_kind` arg (chosen) vs separate RPCs per kind — confirm during planning.
- **Pruning mechanism:** is `pg_cron` enabled on this project, or do we drive pruning from the admin app on a schedule? Verify before Phase 4.
- **Day TZ:** UTC bucketing (chosen) vs operator-local — only matters if leaderboards look "off by a day"; revisit if it bites.
