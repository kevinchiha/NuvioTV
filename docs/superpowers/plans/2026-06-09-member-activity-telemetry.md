# Member Activity Telemetry — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collect durations-only member telemetry (heartbeat watch-time, app version, playback errors) and surface activity rankings, going-dark churn detection, a support panel, and derived sharing signals in admin.kevbox.dev — without ever capturing what members watch.

**Architecture:** Android player pings a `SECURITY DEFINER` RPC (`record_heartbeat`) ~every 60 s while playing; Postgres accrues **capped** wall-clock seconds into a per-member-per-day rollup (`member_activity_daily`). Notable events (session start, playback error) go to a pruned `member_event` log. The admin app reads aggregates as the existing `kevbox_admin` role and renders fleet stats, leaderboards, a going-dark list, and a per-member activity tab. Everything mirrors the existing `member_access`/`member_device` pattern: RLS read-own, writes RPC-only, admin via owner-privileged reads.

**Tech Stack:** Postgres (Supabase project `scmqdptagksltnwiveyh`) · vitest + Dockerized Postgres (kevbox-admin tests) · Kotlin/Android (Media3 ExoPlayer, supabase-kt, Hilt, kotlinx-coroutines-test + mockk) · React 18 + Vite + Fastify (kevbox-admin).

**Spec:** `docs/superpowers/specs/2026-06-09-member-activity-telemetry-design.md`

---

## Deviations from the spec (deliberate, decided during planning)

1. **Dedicated `member_heartbeat` table instead of altering `member_device`** (spec §5.3). Keeps telemetry fully decoupled from the access-control tables — zero risk to the kill-switch / device-limit logic, and the accrual baseline (`last_heartbeat`) exists even when the device-limit feature is off. Same intent (a heartbeat timestamp distinct from `last_seen`), cleaner boundary.
2. **Ranged aggregation in the TypeScript data layer instead of a `member_activity_v` SQL view** (spec §9.1). Date-windowed sums (today / 7 d / 30 d) are cleaner as parameterized queries than baked into a view, and they're directly unit-testable in the existing harness. The admin still reads only member activity, as `kevbox_admin`.
3. **Sharing-suspect threshold concretized:** a member is flagged when their **max single-day `watch_seconds` over the last 30 days ≥ 64800 (18 h)** — structurally near-impossible for *one device* given the 120 s accrual cap. **Caveat (multi-device):** `member_activity_daily` sums across all of a member's devices, so an account with `max_devices ≥ 2` (or with the device-limit feature off, per Deviation 1) can legitimately exceed 18 h/day. The flag is therefore **advisory and known to false-positive on multi-device accounts** — an operator reviews it; it never auto-locks. (If false positives become noisy, scale the threshold by `max_devices` or base it on a `(user_id, device_id, day)` rollup.)
4. **§9.1 view fields not surfaced in the Activity tab.** Because Deviation 2 drops the `member_activity_v` SQL view, the per-member `access_active`, `device_count`, and per-member `going_dark` fields are **not** rendered in the Activity tab. `access`/`device_count` already appear in the same member panel's existing Access tab, and `going_dark` is covered fleet-wide in the going-dark list — so this is a deliberate de-dup, not a coverage loss.
5. **"Last seen" is the last *playback* heartbeat, not the spec's device `last_seen`.** With Deviation 1 we surface `member_heartbeat.last_heartbeat` (playback-only). The UI labels it **"Last played"** (not "Last seen") to avoid conflating it with the broader app-open device timestamp. Going-dark classification keys on `watch_seconds = 0` (not heartbeat), so the relabel has no behavioral effect.

---

## File structure

**Phase 1 — Supabase backend**
- Create `member_telemetry_setup.sql` (repo root) — prod tables, RLS, grants, inner functions, RPC wrappers, prune function, rollback block. Mirrors `member_device_setup.sql`.
- Modify `kevbox-admin/packages/core/test/schema.sql` — add the new tables + inner functions (no `auth.uid()` wrappers) so vitest can exercise the logic.
- Create `kevbox-admin/packages/core/test/telemetry.test.ts` — accrual/cap/session/error tests.

**Phase 2 — Android client**
- Create `app/src/main/java/com/nuvio/tv/core/telemetry/TelemetryRepository.kt` — fail-soft RPC wrappers.
- Create `app/src/main/java/com/nuvio/tv/core/telemetry/HeartbeatScheduler.kt` — lifecycle ticker.
- Create `app/src/test/java/com/nuvio/tv/core/telemetry/HeartbeatSchedulerTest.kt` — unit test.
- Modify `app/build.gradle.kts` — add `FEATURE_TELEMETRY` buildConfigField.
- Modify the player controller (`app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeController*.kt`) — start/stop scheduler, report errors.

**Phase 3 — Admin UI**
- Create `kevbox-admin/packages/core/src/activity.ts` — data layer (+ export from `index.ts`).
- Create `kevbox-admin/packages/core/test/activity.test.ts`.
- Create `kevbox-admin/apps/web/src/server/routes/activity.ts` (+ register in `app.ts`).
- Modify `kevbox-admin/apps/web/src/web/lib/api.ts` — client methods.
- Create `kevbox-admin/apps/web/src/web/components/ActivityTab.tsx` and `FleetView.tsx`.
- Modify `kevbox-admin/apps/web/src/web/components/MemberDetail.tsx` and `App.tsx` — wire tab + fleet view.

**Phase 4 — Retention**
- Add `prune_telemetry()` to `member_telemetry_setup.sql` + `schema.sql`; test in `telemetry.test.ts`. Admin-triggered route + commented `pg_cron` schedule.

---

## Test harness quick reference (Phases 1, 3, 4)

```bash
# One-time / after editing schema.sql — recreate the test DB so the new schema loads:
cd kevbox-admin && docker compose down -v && docker compose up -d test-db && sleep 4

# Run core tests (telemetry + activity):
cd kevbox-admin/packages/core && npm run test

# Single file:
cd kevbox-admin/packages/core && npx vitest run test/telemetry.test.ts
```
Tests connect to `postgres://postgres:test@localhost:5433/kevbox_test` and wrap each case in `withRollback()` (auto-rollback isolation). `schema.sql` is loaded only on a fresh volume, hence the `down -v` when it changes.

---

# PHASE 1 — Supabase backend (schema + RPCs)

The contract every later phase binds to. The load-bearing logic (capped accrual) lives in an **inner function** that takes an explicit `p_user_id` so it is unit-testable without a JWT; thin `auth.uid()` RPC wrappers delegate to it (verified manually, as the existing `claim_device` is).

### Task 1.1: Add telemetry tables to the test schema

**Files:**
- Modify: `kevbox-admin/packages/core/test/schema.sql` (append at end)

- [ ] **Step 1: Append the new tables**

Add to the end of `kevbox-admin/packages/core/test/schema.sql`:

```sql
-- ===== Member activity telemetry (durations only) =====
-- Per-member-per-day watch-time rollup (authoritative metric).
create table public.member_activity_daily (
  user_id          uuid not null references auth.users(id) on delete cascade,
  day              date not null,
  watch_seconds    int  not null default 0,
  heartbeats       int  not null default 0,
  sessions         int  not null default 0,
  last_app_version text,
  updated_at       timestamptz not null default now(),
  primary key (user_id, day)
);

-- Accrual baseline + last-known app version, per (member, device). Decoupled from member_device.
create table public.member_heartbeat (
  user_id        uuid not null references auth.users(id) on delete cascade,
  device_id      text not null,
  last_heartbeat timestamptz not null default now(),
  app_version    text,
  primary key (user_id, device_id)
);

-- Notable events only (session_start, playback_error). Pruned at 90 days. NEVER content/secrets.
create table public.member_event (
  id          bigint generated always as identity primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
  device_id   text,
  occurred_at timestamptz not null default now(),
  kind        text not null check (kind in ('session_start','playback_error')),
  app_version text,
  detail      jsonb
);
create index member_event_user_time on public.member_event (user_id, occurred_at desc);
create index member_event_kind_time on public.member_event (kind, occurred_at desc);
```

- [ ] **Step 2: Recreate the test DB so the schema loads**

Run:
```bash
cd kevbox-admin && docker compose down -v && docker compose up -d test-db && sleep 4
```
Expected: `test-db` healthy. (No assertion yet — schema is verified by Task 1.2's test.)

### Task 1.2: Capped accrual — the core watch-time logic (TDD)

**Files:**
- Create: `kevbox-admin/packages/core/test/telemetry.test.ts`
- Modify: `kevbox-admin/packages/core/test/schema.sql`

- [ ] **Step 1: Write the failing tests**

Create `kevbox-admin/packages/core/test/telemetry.test.ts`:

```typescript
import { describe, test, expect } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";

// Inner accrual fn is called directly with an explicit user id (no JWT needed in tests).
describe("accrue_heartbeat", () => {
  test("first heartbeat of a session accrues 0 and seeds baseline", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "a@test.dev");
      const { rows } = await db.query<{ accrue_heartbeat: number }>(
        "select public.accrue_heartbeat($1, $2, $3, 120) as accrue_heartbeat",
        [uid, "dev-1", "1.0.0"],
      );
      expect(rows[0].accrue_heartbeat).toBe(0);
      const daily = await db.query(
        "select watch_seconds, heartbeats from public.member_activity_daily where user_id=$1",
        [uid],
      );
      expect(daily.rows[0].watch_seconds).toBe(0);
      expect(daily.rows[0].heartbeats).toBe(1);
    });
  });

  test("accrues elapsed seconds since last heartbeat", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "b@test.dev");
      // Seed a baseline 30s in the past (now() is fixed within the txn).
      await db.query(
        "insert into public.member_heartbeat(user_id, device_id, last_heartbeat, app_version) values ($1,$2, now() - interval '30 seconds', $3)",
        [uid, "dev-1", "1.0.0"],
      );
      const { rows } = await db.query<{ accrue_heartbeat: number }>(
        "select public.accrue_heartbeat($1,$2,$3,120) as accrue_heartbeat",
        [uid, "dev-1", "1.0.0"],
      );
      expect(rows[0].accrue_heartbeat).toBe(30);
    });
  });

  test("caps accrual at p_cap_seconds (pauses/gaps cannot inflate)", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "c@test.dev");
      await db.query(
        "insert into public.member_heartbeat(user_id, device_id, last_heartbeat) values ($1,$2, now() - interval '10 minutes')",
        [uid, "dev-1"],
      );
      const { rows } = await db.query<{ accrue_heartbeat: number }>(
        "select public.accrue_heartbeat($1,$2,$3,120) as accrue_heartbeat",
        [uid, "dev-1", "1.0.0"],
      );
      expect(rows[0].accrue_heartbeat).toBe(120); // capped, not 600
    });
  });

  test("repeated accruals sum into the same day row", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "d@test.dev");
      await db.query(
        "insert into public.member_heartbeat(user_id, device_id, last_heartbeat) values ($1,$2, now() - interval '60 seconds')",
        [uid, "dev-1"],
      );
      await db.query("select public.accrue_heartbeat($1,$2,$3,120)", [uid, "dev-1", "1.0.0"]);
      // Move baseline back again and accrue once more.
      await db.query(
        "update public.member_heartbeat set last_heartbeat = now() - interval '60 seconds' where user_id=$1",
        [uid],
      );
      await db.query("select public.accrue_heartbeat($1,$2,$3,120)", [uid, "dev-1", "1.0.0"]);
      const daily = await db.query(
        "select watch_seconds, heartbeats from public.member_activity_daily where user_id=$1",
        [uid],
      );
      expect(daily.rows[0].watch_seconds).toBe(120);
      expect(daily.rows[0].heartbeats).toBe(2);
    });
  });

  test("two devices on one account each track their own baseline and sum into the day", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "multi@test.dev");
      for (const dev of ["dev-1", "dev-2"]) {
        await db.query(
          "insert into public.member_heartbeat(user_id, device_id, last_heartbeat) values ($1,$2, now() - interval '60 seconds')",
          [uid, dev],
        );
        await db.query("select public.accrue_heartbeat($1,$2,$3,120)", [uid, dev, "1.0.0"]);
      }
      const daily = await db.query(
        "select watch_seconds, heartbeats from public.member_activity_daily where user_id=$1", [uid]);
      expect(daily.rows[0].watch_seconds).toBe(120);  // 60 + 60, summed across devices (see Deviation 3 caveat)
      expect(daily.rows[0].heartbeats).toBe(2);
      const hb = await db.query(
        "select count(*)::int as n from public.member_heartbeat where user_id=$1", [uid]);
      expect(hb.rows[0].n).toBe(2);                    // one independent baseline row per device
    });
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd kevbox-admin/packages/core && npx vitest run test/telemetry.test.ts`
Expected: FAIL — `function public.accrue_heartbeat(...) does not exist`.

- [ ] **Step 3: Add the inner accrual function to the test schema**

Append to `kevbox-admin/packages/core/test/schema.sql`:

```sql
-- Capped wall-clock accrual. Inner fn takes explicit user id → unit-testable without a JWT.
-- Concurrency (L3): the daily upsert is atomic (ON CONFLICT row lock). Two concurrent beats for the
-- same (user,device) could each read the old baseline and accrue ≤ CAP; with the one-device limit and
-- ~60s sequential beats this is improbable and bounded by the 120s cap — so no advisory lock is used.
create or replace function public.accrue_heartbeat(
  p_user_id uuid, p_device_id text, p_app_version text, p_cap_seconds int default 120
) returns int language plpgsql security definer set search_path = '' as $$
declare v_last timestamptz; v_accrued int;
begin
  if p_user_id is null then return 0; end if;
  select last_heartbeat into v_last
    from public.member_heartbeat where user_id = p_user_id and device_id = p_device_id;
  if v_last is null then
    v_accrued := 0;                                   -- first beat: establish baseline only
  else
    v_accrued := least(greatest(0, floor(extract(epoch from (now() - v_last)))::int), p_cap_seconds);
  end if;
  insert into public.member_activity_daily(user_id, day, watch_seconds, heartbeats, last_app_version, updated_at)
    values (p_user_id, (now() at time zone 'utc')::date, v_accrued, 1, p_app_version, now())
  on conflict (user_id, day) do update
    set watch_seconds = public.member_activity_daily.watch_seconds + excluded.watch_seconds,
        heartbeats    = public.member_activity_daily.heartbeats + 1,
        last_app_version = excluded.last_app_version,
        updated_at = now();
  insert into public.member_heartbeat(user_id, device_id, last_heartbeat, app_version)
    values (p_user_id, p_device_id, now(), p_app_version)
  on conflict (user_id, device_id) do update
    set last_heartbeat = now(), app_version = excluded.app_version;
  return v_accrued;
end $$;
```

- [ ] **Step 4: Recreate the DB and run the tests**

Run:
```bash
cd kevbox-admin && docker compose down -v && docker compose up -d test-db && sleep 4
cd packages/core && npx vitest run test/telemetry.test.ts
```
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add kevbox-admin/packages/core/test/schema.sql kevbox-admin/packages/core/test/telemetry.test.ts
git commit -m "test(telemetry): capped watch-time accrual function + tests"
```

### Task 1.3: Session-start and error-event inner functions (TDD)

**Files:**
- Modify: `kevbox-admin/packages/core/test/telemetry.test.ts`
- Modify: `kevbox-admin/packages/core/test/schema.sql`

- [ ] **Step 1: Write the failing tests** — append to `telemetry.test.ts`:

```typescript
describe("record_session_start", () => {
  test("increments sessions, logs an event, resets baseline", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "s@test.dev");
      await db.query("select public.record_session_start($1,$2,$3)", [uid, "dev-1", "1.0.0"]);
      const daily = await db.query(
        "select sessions from public.member_activity_daily where user_id=$1", [uid]);
      expect(daily.rows[0].sessions).toBe(1);
      const ev = await db.query(
        "select kind from public.member_event where user_id=$1", [uid]);
      expect(ev.rows.map((r: any) => r.kind)).toContain("session_start");
      const hb = await db.query(
        "select count(*)::int as n from public.member_heartbeat where user_id=$1", [uid]);
      expect(hb.rows[0].n).toBe(1);
    });
  });

  test("session_start then a playback accrual compose on the same day row (sessions preserved)", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "sc@test.dev");
      await db.query("select public.record_session_start($1,$2,$3)", [uid, "dev-1", "1.0.0"]);
      await db.query("update public.member_heartbeat set last_heartbeat = now() - interval '60 seconds' where user_id=$1", [uid]);
      await db.query("select public.accrue_heartbeat($1,$2,$3,120)", [uid, "dev-1", "1.0.0"]);
      const daily = await db.query("select sessions, watch_seconds, heartbeats from public.member_activity_daily where user_id=$1", [uid]);
      expect(daily.rows[0].sessions).toBe(1);       // preserved across the second upsert
      expect(daily.rows[0].watch_seconds).toBe(60); // accrued
      expect(daily.rows[0].heartbeats).toBe(1);
    });
  });
});

describe("record_error_event", () => {
  test("stores only allowlisted keys (no arbitrary payload)", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "e@test.dev");
      await db.query("select public.record_error_event($1,$2,$3,$4::jsonb)", [
        uid, "dev-1", "1.0.0",
        JSON.stringify({ code: "2001", message: "boom http://evil/x", secret: "debrid-token-xyz", url: "http://x" }),
      ]);
      const ev = await db.query(
        "select kind, detail from public.member_event where user_id=$1 and kind='playback_error'", [uid]);
      const detail = ev.rows[0].detail;
      expect(detail.code).toBe("2001");
      expect(detail.message_short).toBe("boom [url]"); // URL-ish tokens stripped server-side (spec §7/§11)
      expect(detail.message).toBeUndefined();          // renamed to message_short
      expect(detail.secret).toBeUndefined();           // dropped at the DB boundary
      expect(detail.url).toBeUndefined();
    });
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd kevbox-admin/packages/core && npx vitest run test/telemetry.test.ts`
Expected: FAIL — `function public.record_session_start(...) does not exist`.

- [ ] **Step 3: Add the functions** — append to `schema.sql`:

```sql
create or replace function public.record_session_start(
  p_user_id uuid, p_device_id text, p_app_version text
) returns void language plpgsql security definer set search_path = '' as $$
begin
  if p_user_id is null then return; end if;
  insert into public.member_activity_daily(user_id, day, sessions, last_app_version, updated_at)
    values (p_user_id, (now() at time zone 'utc')::date, 1, p_app_version, now())
  on conflict (user_id, day) do update
    set sessions = public.member_activity_daily.sessions + 1,
        last_app_version = excluded.last_app_version, updated_at = now();
  insert into public.member_event(user_id, device_id, kind, app_version)
    values (p_user_id, p_device_id, 'session_start', p_app_version);
  insert into public.member_heartbeat(user_id, device_id, last_heartbeat, app_version)
    values (p_user_id, p_device_id, now(), p_app_version)
  on conflict (user_id, device_id) do update set last_heartbeat = now(), app_version = excluded.app_version;
end $$;

create or replace function public.record_error_event(
  p_user_id uuid, p_device_id text, p_app_version text, p_detail jsonb
) returns void language plpgsql security definer set search_path = '' as $$
begin
  if p_user_id is null then return; end if;
  insert into public.member_event(user_id, device_id, kind, app_version, detail)
    values (
      p_user_id, p_device_id, 'playback_error', p_app_version,
      jsonb_build_object(                                   -- key allowlist: no secrets/urls/titles
        'code', left(coalesce(p_detail->>'code',''), 32),
        -- message_short (spec §7): strip URL-ish tokens, then cap hard at 120.
        'message_short', left(regexp_replace(coalesce(p_detail->>'message',''), '\S*://\S*', '[url]', 'g'), 120)
      )
    );
end $$;
```

- [ ] **Step 4: Recreate DB + run**

Run:
```bash
cd kevbox-admin && docker compose down -v && docker compose up -d test-db && sleep 4
cd packages/core && npx vitest run test/telemetry.test.ts
```
Expected: PASS (all telemetry tests).

- [ ] **Step 5: Commit**

```bash
git add kevbox-admin/packages/core/test/schema.sql kevbox-admin/packages/core/test/telemetry.test.ts
git commit -m "test(telemetry): session-start + key-allowlisted error events"
```

### Task 1.4: Production setup file (RLS, grants, JWT RPC wrappers)

**Files:**
- Create: `member_telemetry_setup.sql` (repo root)

No automated test (runs against prod Postgres). Verified manually in Task 1.5.

- [ ] **Step 1: Write the setup file**

Create `member_telemetry_setup.sql`:

```sql
-- KevBox TV — member activity telemetry (durations only). Run ONCE against the KevBox Supabase
-- project (scmqdptagksltnwiveyh). Idempotent — safe to re-run. No secrets here.
-- Plan: docs/superpowers/plans/2026-06-09-member-activity-telemetry.md
-- Pattern mirrors member_device_setup.sql: RLS read-own, writes RPC-only, admin via grants.

-- 1. Tables -----------------------------------------------------------------------
create table if not exists public.member_activity_daily (
  user_id uuid not null references auth.users(id) on delete cascade,
  day date not null, watch_seconds int not null default 0, heartbeats int not null default 0,
  sessions int not null default 0, last_app_version text,
  updated_at timestamptz not null default now(), primary key (user_id, day)
);
create table if not exists public.member_heartbeat (
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id text not null, last_heartbeat timestamptz not null default now(),
  app_version text, primary key (user_id, device_id)
);
create table if not exists public.member_event (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id text, occurred_at timestamptz not null default now(),
  kind text not null check (kind in ('session_start','playback_error')), app_version text, detail jsonb
);
create index if not exists member_event_user_time on public.member_event (user_id, occurred_at desc);
create index if not exists member_event_kind_time on public.member_event (kind, occurred_at desc);
-- Fleet-wide queries (leaderboard / going-dark / stats) filter by `day` across ALL users; the PK is
-- (user_id, day), so a day-only range can't use it efficiently. Cheap insurance at any scale (L1).
create index if not exists member_activity_daily_day on public.member_activity_daily (day);

alter table public.member_activity_daily enable row level security;
alter table public.member_heartbeat      enable row level security;
alter table public.member_event          enable row level security;

-- 2. RLS: members read only their own rows. No write policy → writes go through RPCs only.
drop policy if exists "read own activity" on public.member_activity_daily;
create policy "read own activity" on public.member_activity_daily for select using (auth.uid() = user_id);
drop policy if exists "read own heartbeat" on public.member_heartbeat;
create policy "read own heartbeat" on public.member_heartbeat for select using (auth.uid() = user_id);
drop policy if exists "read own events" on public.member_event;
create policy "read own events" on public.member_event for select using (auth.uid() = user_id);

-- 2b. Revoke default DML grants from anon/authenticated (mirrors member_device_setup.sql).
revoke insert, update, delete, truncate, references, trigger
  on public.member_activity_daily, public.member_heartbeat, public.member_event
  from anon, authenticated;

-- 2c. Admin reads (kevbox_admin is BYPASSRLS but still needs table grants).
grant select on public.member_activity_daily, public.member_heartbeat, public.member_event to kevbox_admin;

-- 3. Inner functions (explicit user id) — copy EXACTLY from packages/core/test/schema.sql:
--    accrue_heartbeat / record_session_start / record_error_event.
--    >>> Paste the three function bodies from schema.sql here verbatim. <<<

-- 3b. LOCK DOWN the inner functions (CRITICAL). They are SECURITY DEFINER and take an explicit
--     p_user_id, so the Postgres default `EXECUTE to PUBLIC` would let any authenticated REST
--     client call them with a FOREIGN uid and forge/inflate another member's telemetry —
--     bypassing the entire RLS / JWT-uid model. Only the owner-running wrappers (section 4) call
--     them, and that call succeeds regardless of caller grants because the wrapper runs as owner.
revoke all on function public.accrue_heartbeat(uuid, text, text, int)     from public, anon, authenticated;
revoke all on function public.record_session_start(uuid, text, text)      from public, anon, authenticated;
revoke all on function public.record_error_event(uuid, text, text, jsonb) from public, anon, authenticated;

-- 4. JWT RPC wrappers — the ONLY write path the app uses. uid from JWT (tamper-proof).
create or replace function public.record_heartbeat(
  p_device_id text, p_app_version text, p_kind text default 'playback'
) returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then return; end if;             -- unauthenticated: silently no-op
  if p_kind = 'session_start' then                  -- p_kind allowlist (spec §7): unknown kinds ignored
    perform public.record_session_start(v_uid, p_device_id, p_app_version);
  elsif p_kind = 'playback' then
    perform public.accrue_heartbeat(v_uid, p_device_id, p_app_version, 120);
  else
    return;                                          -- not 'session_start' or 'playback' → no-op
  end if;
end $$;
revoke all on function public.record_heartbeat(text, text, text) from public, anon;
grant execute on function public.record_heartbeat(text, text, text) to authenticated;

create or replace function public.record_error(
  p_device_id text, p_app_version text, p_detail jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then return; end if;
  perform public.record_error_event(v_uid, p_device_id, p_app_version, p_detail);
end $$;
revoke all on function public.record_error(text, text, jsonb) from public, anon;
grant execute on function public.record_error(text, text, jsonb) to authenticated;

-- Sanity
select count(*) as activity_rows from public.member_activity_daily;

-- ============================================================================================
-- ROLLBACK — stop all telemetry ingestion instantly (no app re-sideload). Use VALID void no-op
-- bodies: `language sql as $$ select $$` is a SYNTAX ERROR (bare select, empty target list) —
-- use plpgsql begin/end:
--   create or replace function public.record_heartbeat(text,text,text) returns void language plpgsql as $$ begin end $$;
--   create or replace function public.record_error(text,text,jsonb)    returns void language plpgsql as $$ begin end $$;
--   -- To restore: re-run the ENTIRE member_telemetry_setup.sql (idempotent). Re-running section 4
--   --   alone leaves the wrappers calling section-3 inner fns ("function does not exist").
--   -- To purge: truncate member_event, member_heartbeat, member_activity_daily;  (FK only to auth.users)
-- ============================================================================================
```

- [ ] **Step 2: Copy the three inner functions** from `kevbox-admin/packages/core/test/schema.sql` (Tasks 1.2/1.3) into section 3, replacing the `>>> Paste <<<` marker. They are identical text.

  > **Drift risk (M2):** the inner-function bodies now exist in two files — the *tested* copy in `schema.sql` and this *prod* copy. Only the schema.sql copy has automated coverage; this copy is exercised once by the Task 1.5 smoke. Keep them byte-identical. Add a lightweight CI/dev guard that diffs the function bodies between the two files (e.g. a `node`/`grep` script asserting the `create or replace function public.accrue_heartbeat … $$;` blocks match), or extract them into a shared `telemetry_functions.sql` fragment that both `schema.sql` and `member_telemetry_setup.sql` `\i`-include. At minimum, re-run the accrual/session/error expectations against the prod-applied functions in Task 1.5.

- [ ] **Step 3: Commit**

```bash
git add member_telemetry_setup.sql
git commit -m "feat(telemetry): production Supabase setup (RLS, grants, RPC wrappers)"
```

### Task 1.5: Apply to prod + manual smoke test

- [ ] **Step 1:** In the Supabase SQL editor for `scmqdptagksltnwiveyh`, run `member_telemetry_setup.sql`. Expected: completes, `activity_rows` = 0.

- [ ] **Step 2: Verify the SQL plumbing via the INNER functions** (the SQL editor runs as the `postgres`/owner role with **no JWT**, so `auth.uid()` is `NULL` and `record_heartbeat` would silently no-op — do **not** test the wrapper here; the JWT wrapper is verified end-to-end by the Task 2.4 sideload smoke). Pick a real member uuid `:uid` from `auth.users` and run:
  ```sql
  select public.record_session_start(:'uid','smoke-dev','1.0.0');     -- sessions += 1, seeds baseline
  select public.accrue_heartbeat(:'uid','smoke-dev','1.0.0',120);     -- first beat: accrues 0
  -- wait ~30s, then:
  select public.accrue_heartbeat(:'uid','smoke-dev','1.0.0',120);     -- accrues ~30
  ```
  Expected: a `member_activity_daily` row for `:uid` with `sessions = 1`, `heartbeats = 2`, `watch_seconds` ≈ 30; a `member_event` `session_start` row; `member_heartbeat.app_version = '1.0.0'`.

- [ ] **Step 3: Security checklist (the model is UNTESTABLE in the vitest harness — superuser connection; verify here instead).** Confirm each:
  - **Inner fns locked (H1):** as a member (anon key + member JWT, e.g. via `supabase.rpc` or PostgREST), `accrue_heartbeat`/`record_session_start`/`record_error_event` are **not callable** — Postgres returns `permission denied for function …`. In particular a member **cannot** forge a foreign uid by calling `accrue_heartbeat('<other-uuid>', …)`.
  - **Wrapper null-uid no-op:** calling `record_heartbeat('smoke-dev','1.0.0','playback')` with **no** auth (anon, no member JWT) writes **no** rows.
  - **Direct DML revoked:** as `authenticated`, a direct `insert/update/delete` on `member_activity_daily` / `member_heartbeat` / `member_event` fails with `permission denied`.
  - **RLS read-own:** as the member, `select * from member_activity_daily` returns only their row; as `anon`, it returns nothing.
  - **p_kind allowlist (M5):** `record_heartbeat('smoke-dev','1.0.0','garbage')` (with a member JWT) writes **no** new `watch_seconds`/`heartbeats`.

---

# PHASE 2 — Android client (heartbeat + error reporting)

Gated behind `FEATURE_TELEMETRY`. Fail-soft everywhere: telemetry never blocks playback or auth.

### Task 2.1: Feature flag

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1:** Find the existing `FEATURE_DEVICE_LIMIT` `buildConfigField` declaration (`grep -n FEATURE_DEVICE_LIMIT app/build.gradle.kts`). It is defined **per product flavor** (`full` = `true`, `playstore` = `false`), not per build type — add an identically-shaped line in the same place for each flavor:

```kotlin
buildConfigField("boolean", "FEATURE_TELEMETRY", "true")
```
Set it to **`true` in the `full` flavor** and **`false` in the `playstore` flavor** (mirroring `FEATURE_DEVICE_LIMIT`, so the Play Store build never talks to the family Supabase backend). Note `compileFullDebugKotlin` only exercises the `full` flavor, so a wrong `playstore` value won't be caught by the build below — set it explicitly.

- [ ] **Step 2:** Sync/build to regenerate `BuildConfig`.

Run: `./gradlew :app:compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL; `BuildConfig.FEATURE_TELEMETRY` resolves.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat(telemetry): FEATURE_TELEMETRY build flag"
```

### Task 2.2: TelemetryRepository (fail-soft RPC wrappers)

**Files:**
- Create: `app/src/main/java/com/nuvio/tv/core/telemetry/TelemetryRepository.kt`

Mirrors `DeviceGuardService`'s `postgrest.rpc(...).withJwtRefreshRetry` pattern. No automated test (thin I/O wrapper); covered by Task 2.4's manual smoke.

- [ ] **Step 1: Write the class**

```kotlin
package com.nuvio.tv.core.telemetry

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Durations-only telemetry. NEVER sends content ids, titles, urls, or playback position. */
@Singleton
class TelemetryRepository @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
) {
    suspend fun heartbeat(deviceId: String, kind: String) = call("record_heartbeat") {
        buildJsonObject {
            put("p_device_id", deviceId)
            put("p_app_version", BuildConfig.VERSION_NAME)
            put("p_kind", kind)
        }
    }

    suspend fun error(deviceId: String, code: String, message: String) = call("record_error") {
        buildJsonObject {
            put("p_device_id", deviceId)
            put("p_app_version", BuildConfig.VERSION_NAME)
            put("p_detail", buildJsonObject {
                put("code", code)
                put("message", message.take(300))
            })
        }
    }

    private suspend inline fun call(rpc: String, params: () -> kotlinx.serialization.json.JsonObject) =
        withContext(Dispatchers.IO) {
            if (authManager.currentUserId == null) return@withContext
            try {
                withJwtRefreshRetry { postgrest.rpc(rpc, params()) }
            } catch (e: CancellationException) {
                throw e                                                 // never swallow cancellation (structured concurrency)
            } catch (e: Exception) {
                Log.w(TAG, "$rpc failed (telemetry is fail-soft)", e)   // swallow — never disrupt playback
            }
        }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T =
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }

    companion object { private const val TAG = "TelemetryRepository" }
}
```

> Note: confirm `AuthManager.refreshSessionIfJwtExpired` / `currentUserId` signatures against `app/src/main/java/com/nuvio/tv/core/auth/AuthManager.kt` and copy the exact `withJwtRefreshRetry` helper used by `DeviceGuardService.kt` if it differs.
>
> **Attribution (L14):** telemetry is recorded under `authManager.currentUserId` (matching `DeviceGuardService`), i.e. the signed-in account. If a sync-owner / "effective user" concept applies on this device (`AuthManager.getEffectiveUserId()`), confirm which uid the operator wants telemetry rolled up under and switch deliberately — `currentUserId` is the intended default here.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/core/telemetry/TelemetryRepository.kt
git commit -m "feat(telemetry): fail-soft TelemetryRepository RPC wrappers"
```

### Task 2.3: HeartbeatScheduler (TDD)

**Files:**
- Create: `app/src/main/java/com/nuvio/tv/core/telemetry/HeartbeatScheduler.kt`
- Create: `app/src/test/java/com/nuvio/tv/core/telemetry/HeartbeatSchedulerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nuvio/tv/core/telemetry/HeartbeatSchedulerTest.kt`:

```kotlin
package com.nuvio.tv.core.telemetry

import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatSchedulerTest {

    private fun scheduler(scope: TestScope, repo: TelemetryRepository) =
        HeartbeatScheduler(repo, scope, intervalMs = 1000L)

    // NOTE: the ticker is an infinite `while (isActive) { delay() }` loop, so NEVER call
    // advanceUntilIdle() while it is live (it never goes idle → hang). Use advanceTimeBy()+runCurrent()
    // to fire due beats, and always s.stop() before the test ends so the coroutine doesn't leak.

    @Test
    fun `start emits a session_start then a playback beat each interval`() = runTest {
        val repo = mockk<TelemetryRepository>(relaxed = true)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val s = scheduler(scope, repo)

        s.start("dev-1")
        advanceTimeBy(3500) // 3 full intervals elapsed (interval = 1000ms → beats at 1000/2000/3000)
        runCurrent()
        s.stop()

        coVerify(exactly = 1) { repo.heartbeat("dev-1", "session_start") }
        coVerify(atLeast = 3) { repo.heartbeat("dev-1", "playback") }
    }

    @Test
    fun `stop halts further beats`() = runTest {
        val repo = mockk<TelemetryRepository>(relaxed = true)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val s = scheduler(scope, repo)

        s.start("dev-1")
        advanceTimeBy(1500) // exactly one playback beat (at t=1000)
        runCurrent()
        s.stop()
        advanceTimeBy(5000) // would be 5 more beats if the ticker were still alive
        runCurrent()

        coVerify(exactly = 1) { repo.heartbeat("dev-1", "playback") } // no beats after stop()
    }

    @Test
    fun `restart with emitSessionStart=false does not re-emit session_start (pause then resume)`() = runTest {
        val repo = mockk<TelemetryRepository>(relaxed = true)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val s = scheduler(scope, repo)

        s.start("dev-1"); advanceTimeBy(1200); runCurrent(); s.stop()                       // play
        s.start("dev-1", emitSessionStart = false); advanceTimeBy(1200); runCurrent(); s.stop() // resume same playback

        coVerify(exactly = 1) { repo.heartbeat("dev-1", "session_start") } // session_start NOT re-emitted on resume
    }

    @Test
    fun `repo failure does not stop the ticker (fail-soft)`() = runTest {
        val repo = mockk<TelemetryRepository>(relaxed = true)
        coEvery { repo.heartbeat(any(), "playback") } throws RuntimeException("network")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val s = scheduler(scope, repo)

        s.start("dev-1")
        advanceTimeBy(3500)
        runCurrent()
        s.stop()
        coVerify(atLeast = 3) { repo.heartbeat("dev-1", "playback") } // kept ticking despite throws
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testFullDebugUnitTest --tests "*HeartbeatSchedulerTest*"`
Expected: FAIL — `HeartbeatScheduler` unresolved.

- [ ] **Step 3: Write the scheduler**

Create `app/src/main/java/com/nuvio/tv/core/telemetry/HeartbeatScheduler.kt`:

```kotlin
package com.nuvio.tv.core.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fires a playback heartbeat every [intervalMs] while a session is active.
 * Each beat is independently fail-soft (TelemetryRepository swallows errors), so a throw never
 * stops the ticker. Inject a real CoroutineScope in prod; a TestScope in unit tests.
 *
 * Session-start dedup: [start] emits exactly ONE `session_start` per call. The player must pass
 * `emitSessionStart = false` when *resuming the same playback* after a pause, so that pause→resume
 * (and rebuffer-driven restarts) do not inflate the `sessions` metric (spec §5.1). A brand-new
 * playback passes `emitSessionStart = true` (the default).
 */
class HeartbeatScheduler(
    private val repo: TelemetryRepository,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 60_000L,
) {
    private var job: Job? = null

    fun start(deviceId: String, emitSessionStart: Boolean = true) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (emitSessionStart) runCatching { repo.heartbeat(deviceId, "session_start") }
            while (isActive) {
                delay(intervalMs)
                runCatching { repo.heartbeat(deviceId, "playback") }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testFullDebugUnitTest --tests "*HeartbeatSchedulerTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/core/telemetry/HeartbeatScheduler.kt app/src/test/java/com/nuvio/tv/core/telemetry/HeartbeatSchedulerTest.kt
git commit -m "feat(telemetry): HeartbeatScheduler with unit tests"
```

### Task 2.4: Wire into the player + manual verification

**Files:**
- Modify: `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeController.kt` (and `PlayerRuntimeControllerInitialization.kt` for the listener)

- [ ] **Step 1: Inject via `PlayerViewModel`, not the controller (M8).** `PlayerRuntimeController` is a plain class that is *hand-constructed* by `PlayerViewModel` (the `@HiltViewModel @Inject` class) — the controller receives **nothing** through Hilt, so a `@Inject` on it is inert. Instead:
  - Add `telemetryRepository: TelemetryRepository` and `deviceGuardDataStore: DeviceGuardDataStore` to **`PlayerViewModel`'s `@Inject` constructor**.
  - Add matching constructor params to `PlayerRuntimeController` and pass them at the controller construction site (~`PlayerViewModel.kt:76`).
  - In the controller, build `private val heartbeatScheduler = HeartbeatScheduler(telemetryRepository, scope, 60_000L)` on the controller's existing `scope` (= `viewModelScope`).

- [ ] **Step 2: Resolve the device id once and cache it (M7).** `DeviceGuardDataStore.getOrCreateDeviceId()` is a **`suspend`** function (DataStore I/O) and cannot be called from the non-suspend `Player.Listener` callbacks. In the controller's existing init `scope.launch`, resolve it once into a cached field; telemetry no-ops while it is null. Also add a once-per-playback session_start guard:
```kotlin
@Volatile private var telemetryDeviceId: String? = null
@Volatile private var telemetrySessionStarted = false
// in the controller's init coroutine (where the player is set up):
if (BuildConfig.FEATURE_TELEMETRY) scope.launch { telemetryDeviceId = deviceGuardDataStore.getOrCreateDeviceId() }
```
  Reset `telemetrySessionStarted = false` inside `initializePlayer()` (mirror the existing `hasRenderedFirstFrame` reset) so each NEW playback emits exactly one `session_start`.

- [ ] **Step 3: Drive start/stop from `onIsPlayingChanged`, NOT `onPlaybackStateChanged` (H2).** `onPlaybackStateChanged` only reports buffering/ready/ended — a pure pause toggles `isPlaying` without changing `playbackState`, and the controller defers play to `onRenderedFirstFrame`, so `playWhenReady` is usually still `false` at `STATE_READY`. The original wiring therefore never reliably starts and never stops on pause. In the `Player.Listener.onIsPlayingChanged(isPlaying: Boolean)` override (`PlayerRuntimeControllerInitialization.kt`, ~line 919), gated on `BuildConfig.FEATURE_TELEMETRY`:
```kotlin
val dev = telemetryDeviceId ?: return
if (isPlaying) {
    val firstForThisPlayback = !telemetrySessionStarted
    telemetrySessionStarted = true
    heartbeatScheduler.start(dev, emitSessionStart = firstForThisPlayback) // session_start once per playback
} else {
    heartbeatScheduler.stop()   // pause → stop the ticker (no phantom watch-time)
}
```
  Result: pause→resume / rebuffer / retry / engine-failover within one playback do **not** re-emit `session_start` or re-increment `sessions` (spec §5.1).

- [ ] **Step 4: Stop on background, end, and release (H2/M9).** The ticker lives on `viewModelScope` and is otherwise only cancelled at `onCleared()`, so a paused/backgrounded-but-not-cleared player would keep beating. Add explicit `heartbeatScheduler.stop()` (gated on the flag) to:
  - the lifecycle/background pause path (`pauseForLifecycle()` / `ON_PAUSE`) — spec §10 "cancel on background";
  - `STATE_ENDED` in the playback-state handler;
  - `releasePlayer()` / `stopAndRelease()` (~`PlayerRuntimeController.kt:204-206, 529-539`) and `PlayerViewModel.onCleared()` (~:105).

- [ ] **Step 5: Report errors (gated).** In `onPlayerError(error)`, after the existing handling:
```kotlin
if (BuildConfig.FEATURE_TELEMETRY) telemetryDeviceId?.let { dev ->
    scope.launch {
        runCatching { telemetryRepository.error(dev, error.errorCode.toString(), error.message ?: "playback error") }
    }
}
```

- [ ] **Step 6: Build**

Run: `./gradlew :app:assembleFullDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Manual verification** (ExoPlayer timing isn't unit-tested):
  - Sideload the full-debug build, sign in as a test member, play a title for ~3 minutes, **pause, resume** (≥1 cycle), stop.
  - In Supabase, confirm: `member_activity_daily` row for that member has `watch_seconds` ≈ played seconds (±2 min from the cap), and **`sessions == 1` despite the pause/resume** (verifies the H2 fix — pause→resume must NOT increment `sessions`); exactly one `member_event` `session_start` exists; `member_heartbeat.app_version` = the build's version. Background the app mid-playback and confirm `watch_seconds` stops climbing.
  - Force a playback error (bad source) → a `playback_error` event appears with only `code`/`message_short` in `detail`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/ui/screens/player/
git commit -m "feat(telemetry): start/stop heartbeat + report errors from the player"
```

---

# PHASE 3 — Admin UI (fleet stats, leaderboards, going-dark, per-member tab)

### Task 3.1: Activity data layer (TDD)

**Files:**
- Create: `kevbox-admin/packages/core/src/activity.ts`
- Modify: `kevbox-admin/packages/core/src/index.ts`
- Create: `kevbox-admin/packages/core/test/activity.test.ts`

- [ ] **Step 1: Write the failing tests**

Create `kevbox-admin/packages/core/test/activity.test.ts`:

```typescript
import { describe, test, expect } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { getMemberActivity, listMembersByActivity, listGoingDark, getFleetStats } from "../src/activity.js";

async function seedDay(db: any, uid: string, daysAgo: number, seconds: number) {
  await db.query(
    "insert into public.member_activity_daily(user_id, day, watch_seconds, heartbeats, sessions) values ($1, (now() at time zone 'utc')::date - $2, $3, 1, 1) on conflict (user_id, day) do update set watch_seconds=excluded.watch_seconds",
    [uid, daysAgo, seconds],
  );
}

describe("getMemberActivity", () => {
  test("rolls up today / 7d / 30d watch seconds", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "a@test.dev");
      await seedDay(db, uid, 0, 100);
      await seedDay(db, uid, 3, 200);
      await seedDay(db, uid, 20, 400);
      const a = await getMemberActivity(db, uid);
      expect(a!.watchSecondsToday).toBe(100);
      expect(a!.watchSeconds7d).toBe(300);   // today + 3d
      expect(a!.watchSeconds30d).toBe(700);  // + 20d
    });
  });

  test("flags sharing-suspect when a day exceeds 18h", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "b@test.dev");
      await seedDay(db, uid, 1, 19 * 3600);
      const a = await getMemberActivity(db, uid);
      expect(a!.sharingSuspect).toBe(true);
    });
  });

  test("strict 7d/30d window boundaries exclude exactly day-7 and day-30", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "win@test.dev");
      await seedDay(db, uid, 6, 10);    // inside 7d (and 30d)
      await seedDay(db, uid, 7, 100);   // excluded by `> date - 7`; still inside 30d
      await seedDay(db, uid, 29, 20);   // inside 30d
      await seedDay(db, uid, 30, 200);  // excluded by `> date - 30`
      const a = await getMemberActivity(db, uid);
      expect(a!.watchSeconds7d).toBe(10);     // only day-6
      expect(a!.watchSeconds30d).toBe(130);   // day-6 + day-7 + day-29; day-30 excluded
    });
  });

  test("sharing-suspect boundary: 64800 true, 64799 false, 17h false (no false-positive on heavy use)", async () => {
    await withRollback(async (db) => {
      const over = await createTestMember(db, "over@test.dev");
      await seedDay(db, over, 1, 64800);
      expect((await getMemberActivity(db, over))!.sharingSuspect).toBe(true);
      const under = await createTestMember(db, "under@test.dev");
      await seedDay(db, under, 1, 64799);
      expect((await getMemberActivity(db, under))!.sharingSuspect).toBe(false);
      const heavy = await createTestMember(db, "heavy@test.dev");
      await seedDay(db, heavy, 1, 17 * 3600);
      expect((await getMemberActivity(db, heavy))!.sharingSuspect).toBe(false);
    });
  });

  test("null only when truly no data; a session_start-only day returns a real all-zero object", async () => {
    await withRollback(async (db) => {
      const empty = await createTestMember(db, "empty@test.dev");
      expect(await getMemberActivity(db, empty)).toBeNull();

      const sOnly = await createTestMember(db, "sonly@test.dev");
      await db.query(
        "insert into public.member_activity_daily(user_id, day, watch_seconds, heartbeats, sessions) values ($1, (now() at time zone 'utc')::date, 0, 0, 1)", [sOnly]);
      await db.query(
        "insert into public.member_heartbeat(user_id, device_id, last_heartbeat) values ($1,'dev-1', now())", [sOnly]);
      const a = await getMemberActivity(db, sOnly);
      expect(a).not.toBeNull();
      expect(a!.watchSecondsToday).toBe(0);
      expect(a!.sessions7d).toBeGreaterThanOrEqual(1);
    });
  });

  test("lastAppVersion reflects the most recent day, not text max (1.9.0 → 1.10.0)", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "ver@test.dev");
      await db.query(
        "insert into public.member_activity_daily(user_id, day, watch_seconds, last_app_version) values ($1, (now() at time zone 'utc')::date - 3, 10, '1.9.0')", [uid]);
      await db.query(
        "insert into public.member_activity_daily(user_id, day, watch_seconds, last_app_version) values ($1, (now() at time zone 'utc')::date, 10, '1.10.0')", [uid]);
      expect((await getMemberActivity(db, uid))!.lastAppVersion).toBe("1.10.0"); // text max() would wrongly pick 1.9.0
    });
  });
});

describe("listMembersByActivity", () => {
  test("orders most-active first over the window", async () => {
    await withRollback(async (db) => {
      const low = await createTestMember(db, "low@test.dev");
      const high = await createTestMember(db, "high@test.dev");
      await seedDay(db, low, 1, 100);
      await seedDay(db, high, 1, 5000);
      const rows = await listMembersByActivity(db, { window: "7d", order: "most", limit: 10 });
      expect(rows[0].userId).toBe(high);
      expect(rows[0].watchSeconds).toBe(5000);
    });
  });
});

describe("listGoingDark", () => {
  test("returns members with access but ~0 recent watch time", async () => {
    await withRollback(async (db) => {
      const dark = await createTestMember(db, "dark@test.dev");
      const live = await createTestMember(db, "live@test.dev");
      await db.query("insert into public.member_access(user_id, active) values ($1,true),($2,true)", [dark, live]);
      await seedDay(db, dark, 40, 5000); // last watched 40 days ago
      await seedDay(db, live, 1, 5000);  // watched yesterday
      const rows = await listGoingDark(db, { days: 14 });
      const ids = rows.map((r) => r.userId);
      expect(ids).toContain(dark);
      expect(ids).not.toContain(live);
    });
  });

  test("a tiny non-zero in-window watch keeps a member OFF the list", async () => {
    await withRollback(async (db) => {
      const blip = await createTestMember(db, "blip@test.dev");
      await db.query("insert into public.member_access(user_id, active) values ($1,true)", [blip]);
      await seedDay(db, blip, 2, 1); // 1 second within the 14d window
      const rows = await listGoingDark(db, { days: 14 });
      expect(rows.map((r) => r.userId)).not.toContain(blip);
    });
  });

  test("the 14d edge: watch exactly at day-14 is out-of-window → member IS dark", async () => {
    await withRollback(async (db) => {
      const edge = await createTestMember(db, "edge@test.dev");
      await db.query("insert into public.member_access(user_id, active) values ($1,true)", [edge]);
      await seedDay(db, edge, 14, 5000); // day-14 excluded by `> date - 14`
      const rows = await listGoingDark(db, { days: 14 });
      expect(rows.map((r) => r.userId)).toContain(edge);
    });
  });

  test("inactive access (active=false) is never listed even with zero watch", async () => {
    await withRollback(async (db) => {
      const off = await createTestMember(db, "off@test.dev");
      await db.query("insert into public.member_access(user_id, active) values ($1,false)", [off]);
      const rows = await listGoingDark(db, { days: 14 });
      expect(rows.map((r) => r.userId)).not.toContain(off);
    });
  });
});

describe("getFleetStats", () => {
  test("counts active members and total watch hours", async () => {
    await withRollback(async (db) => {
      const u = await createTestMember(db, "u@test.dev");
      await seedDay(db, u, 0, 3600);
      const s = await getFleetStats(db);
      expect(s.dau).toBeGreaterThanOrEqual(1);
      expect(s.totalWatchHours).toBeGreaterThanOrEqual(1);
    });
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd kevbox-admin/packages/core && npx vitest run test/activity.test.ts`
Expected: FAIL — cannot import from `../src/activity.js`.

- [ ] **Step 3: Implement the data layer**

Create `kevbox-admin/packages/core/src/activity.ts`:

```typescript
import type { Db } from "./types.js";

const SHARING_SUSPECT_SECONDS = 18 * 3600; // 64800

export interface MemberActivity {
  userId: string;
  watchSecondsToday: number;
  watchSeconds7d: number;
  watchSeconds30d: number;
  sessions7d: number;
  lastAppVersion: string | null;
  lastHeartbeatAt: string | null;
  errors7d: number;
  sharingSuspect: boolean;
}

export async function getMemberActivity(db: Db, userId: string): Promise<MemberActivity | null> {
  const { rows } = await db.query(
    `select
        coalesce(sum(watch_seconds) filter (where day = (now() at time zone 'utc')::date),0)::int as today,
        coalesce(sum(watch_seconds) filter (where day > (now() at time zone 'utc')::date - 7),0)::int as d7,
        coalesce(sum(watch_seconds) filter (where day > (now() at time zone 'utc')::date - 30),0)::int as d30,
        coalesce(sum(sessions) filter (where day > (now() at time zone 'utc')::date - 7),0)::int as sessions7d,
        coalesce(max(watch_seconds) filter (where day > (now() at time zone 'utc')::date - 30),0)::int as maxday30,
        count(*)::int as daily_rows,
        -- most-recent day's version, not text max() (so 1.9.0 → 1.10.0 reports 1.10.0, not 1.9.0).
        (array_agg(last_app_version order by day desc) filter (where last_app_version is not null))[1] as last_app_version
     from public.member_activity_daily where user_id = $1`,
    [userId],
  );
  const r = rows[0];
  const hb = await db.query(
    "select max(last_heartbeat) as last_heartbeat from public.member_heartbeat where user_id=$1", [userId]);
  const errs = await db.query(
    "select count(*)::int as n from public.member_event where user_id=$1 and kind='playback_error' and occurred_at > now() - interval '7 days'", [userId]);
  // No data at all (no daily rows AND no heartbeat) → null, matching getMember's null contract.
  // A session_start-only day (watch 0 but a row exists) returns a real all-zero object, not null.
  if (Number(r.daily_rows) === 0 && hb.rows[0].last_heartbeat == null) return null;
  return {
    userId,
    watchSecondsToday: r.today, watchSeconds7d: r.d7, watchSeconds30d: r.d30,
    sessions7d: r.sessions7d, lastAppVersion: r.last_app_version,
    lastHeartbeatAt: hb.rows[0].last_heartbeat ? new Date(hb.rows[0].last_heartbeat).toISOString() : null,
    errors7d: errs.rows[0].n,
    sharingSuspect: Number(r.maxday30) >= SHARING_SUSPECT_SECONDS,
  };
}

export interface ActivityRankRow { userId: string; email: string | null; watchSeconds: number; }

export async function listMembersByActivity(
  db: Db, opts: { window: "today" | "7d" | "30d"; order: "most" | "least"; limit: number },
): Promise<ActivityRankRow[]> {
  const days = opts.window === "today" ? 1 : opts.window === "7d" ? 7 : 30;
  const dir = opts.order === "most" ? "desc" : "asc";
  const { rows } = await db.query(
    `select d.user_id, u.email, coalesce(sum(d.watch_seconds),0)::int as watch_seconds
       from public.member_activity_daily d
       left join public.kevbox_auth_users u on u.id = d.user_id
      where d.day > (now() at time zone 'utc')::date - $1
      group by d.user_id, u.email
      order by watch_seconds ${dir}
      limit $2`,
    [days, opts.limit],
  );
  return rows.map((r: any) => ({ userId: r.user_id, email: r.email, watchSeconds: r.watch_seconds }));
}

export interface GoingDarkRow { userId: string; email: string | null; lastHeartbeatAt: string | null; }

export async function listGoingDark(db: Db, opts: { days: number }): Promise<GoingDarkRow[]> {
  const { rows } = await db.query(
    `select a.user_id, u.email, hb.last_heartbeat
       from public.member_access a
       left join public.kevbox_auth_users u on u.id = a.user_id
       left join (select user_id, max(last_heartbeat) as last_heartbeat from public.member_heartbeat group by user_id) hb on hb.user_id = a.user_id
      where a.active = true
        and coalesce((select sum(watch_seconds) from public.member_activity_daily d
                       where d.user_id = a.user_id and d.day > (now() at time zone 'utc')::date - $1), 0) = 0
      order by hb.last_heartbeat asc nulls first`,
    [opts.days],
  );
  return rows.map((r: any) => ({
    userId: r.user_id, email: r.email,
    lastHeartbeatAt: r.last_heartbeat ? new Date(r.last_heartbeat).toISOString() : null,
  }));
}

export interface FleetStats {
  dau: number; wau: number; mau: number; totalWatchHours: number;
  goingDark: number; errors7d: number; appVersions: { version: string; count: number }[];
}

export async function getFleetStats(db: Db): Promise<FleetStats> {
  const win = async (days: number) => Number((await db.query(
    `select count(distinct user_id)::int as n from public.member_activity_daily
      where day > (now() at time zone 'utc')::date - $1 and watch_seconds > 0`, [days])).rows[0].n);
  const totalSec = Number((await db.query(
    "select coalesce(sum(watch_seconds),0)::bigint as s from public.member_activity_daily where day > (now() at time zone 'utc')::date - 30")).rows[0].s);
  const errors7d = Number((await db.query(
    "select count(*)::int as n from public.member_event where kind='playback_error' and occurred_at > now() - interval '7 days'")).rows[0].n);
  const versions = (await db.query(
    "select coalesce(app_version,'unknown') as version, count(*)::int as count from public.member_heartbeat group by 1 order by 2 desc")).rows
    .map((r: any) => ({ version: r.version, count: r.count }));
  const goingDark = (await listGoingDark(db, { days: 14 })).length;
  return {
    dau: await win(1), wau: await win(7), mau: await win(30),
    totalWatchHours: Math.round(totalSec / 3600), goingDark, errors7d, appVersions: versions,
  };
}
```

- [ ] **Step 4:** Add `export * from "./activity.js";` to `kevbox-admin/packages/core/src/index.ts`.

- [ ] **Step 5: Recreate DB + run**

Run:
```bash
cd kevbox-admin && docker compose down -v && docker compose up -d test-db && sleep 4
cd packages/core && npx vitest run test/activity.test.ts
```
Expected: PASS (all activity tests).

- [ ] **Step 6: Commit**

```bash
git add kevbox-admin/packages/core/src/activity.ts kevbox-admin/packages/core/src/index.ts kevbox-admin/packages/core/test/activity.test.ts
git commit -m "feat(telemetry): admin activity data layer + tests"
```

### Task 3.2: Server routes

**Files:**
- Create: `kevbox-admin/apps/web/src/server/routes/activity.ts`
- Modify: `kevbox-admin/apps/web/src/server/app.ts`

- [ ] **Step 1: Write the routes**

Create `kevbox-admin/apps/web/src/server/routes/activity.ts`:

```typescript
import type { FastifyInstance } from "fastify";
import type { Db } from "@kevbox-admin/core";
import { getMemberActivity, listMembersByActivity, listGoingDark, getFleetStats, getMember } from "@kevbox-admin/core";

// Clamp an untrusted ?limit= to [1,100] (default 25). Guards NaN/negative from reaching SQL LIMIT.
function clampLimit(raw?: string): number {
  const n = Number(raw ?? 25);
  return Number.isFinite(n) ? Math.min(Math.max(Math.trunc(n), 1), 100) : 25;
}

export function registerActivityRoutes(app: FastifyInstance, db: Db): void {
  app.get<{ Params: { userId: string } }>("/members/:userId/activity", async (req, reply) => {
    if (!(await getMember(db, req.params.userId))) return reply.code(404).send({ error: "member not found" });
    return { activity: await getMemberActivity(db, req.params.userId) };
  });

  app.get<{ Querystring: { window?: "today" | "7d" | "30d"; order?: "most" | "least"; limit?: string } }>(
    "/activity/leaderboard", async (req) => ({
      rows: await listMembersByActivity(db, {
        window: req.query.window ?? "7d",
        order: req.query.order ?? "most",
        limit: clampLimit(req.query.limit),
      }),
    }),
  );

  app.get("/activity/going-dark", async () => ({ rows: await listGoingDark(db, { days: 14 }) }));
  app.get("/activity/stats", async () => ({ stats: await getFleetStats(db) }));
}
```

- [ ] **Step 2:** In `kevbox-admin/apps/web/src/server/app.ts`, import and register it next to the existing `registerAccessRoutes`/`registerMemberRoutes` calls (inside the authed `api` scope):
```typescript
import { registerActivityRoutes } from "./routes/activity.js";
// ...
registerActivityRoutes(api, opts.db);
```

- [ ] **Step 3: Build core FIRST, then the web app (M1)**

`@kevbox-admin/core` resolves to its built `dist/` — the new exports are invisible to `apps/web` (typecheck + server esbuild bundle) until core is rebuilt. The vitest tests pass without this because they import `../src/*.js` directly, which masks the failure.

Run:
```bash
cd kevbox-admin && npm run -w packages/core build && npm run -w apps/web build
```
Expected: no TypeScript errors, and `getMemberActivity`/`listMembersByActivity`/`listGoingDark`/`getFleetStats`/`Db` all resolve from `@kevbox-admin/core`.

- [ ] **Step 4: Commit**

```bash
git add kevbox-admin/apps/web/src/server/routes/activity.ts kevbox-admin/apps/web/src/server/app.ts
git commit -m "feat(telemetry): admin activity API routes"
```

### Task 3.3: API client methods

**Files:**
- Modify: `kevbox-admin/apps/web/src/web/lib/api.ts`

- [ ] **Step 1:** Add to the `Api` class (mirroring the existing `getMember` method shape), plus exported row types matching the core interfaces:

```typescript
getMemberActivity(userId: string): Promise<{ activity: MemberActivity | null }> {
  return this.request("GET", `/members/${encodeURIComponent(userId)}/activity`);
}
getLeaderboard(window: "today" | "7d" | "30d", order: "most" | "least"): Promise<{ rows: ActivityRankRow[] }> {
  return this.request("GET", `/activity/leaderboard?window=${window}&order=${order}`);
}
getGoingDark(): Promise<{ rows: GoingDarkRow[] }> { return this.request("GET", "/activity/going-dark"); }
getFleetStats(): Promise<{ stats: FleetStats }> { return this.request("GET", "/activity/stats"); }
```
At the **top of `api.ts`**, add this explicit re-export (as code, not prose) so components import the same types from one place and a missing line fails typecheck:
```typescript
export type { MemberActivity, ActivityRankRow, GoingDarkRow, FleetStats } from "@kevbox-admin/core";
```
(Requires the core rebuild from Task 3.2 Step 3 to be in place.)

- [ ] **Step 2: Commit**

```bash
git add kevbox-admin/apps/web/src/web/lib/api.ts
git commit -m "feat(telemetry): admin API client methods"
```

### Task 3.4: Per-member Activity tab

**Files:**
- Create: `kevbox-admin/apps/web/src/web/components/ActivityTab.tsx`
- Modify: `kevbox-admin/apps/web/src/web/components/MemberDetail.tsx`
- Modify: `kevbox-admin/apps/web/src/web/App.tsx`

- [ ] **Step 1: Write the component**

Create `kevbox-admin/apps/web/src/web/components/ActivityTab.tsx`:

```tsx
import type { MemberActivity } from "../lib/api.js";

const fmt = (s: number) => `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;

export function ActivityTab({ activity }: { activity: MemberActivity | null }) {
  if (!activity) return <p className="muted">No activity recorded yet.</p>;
  return (
    <div>
      {activity.sharingSuspect && (
        <p className="danger">⚠ Possible sharing — a single day exceeded 18h of watch time.</p>
      )}
      <div className="row"><span>Today</span><strong>{fmt(activity.watchSecondsToday)}</strong></div>
      <div className="row"><span>Last 7 days</span><strong>{fmt(activity.watchSeconds7d)}</strong></div>
      <div className="row"><span>Last 30 days</span><strong>{fmt(activity.watchSeconds30d)}</strong></div>
      <div className="row"><span>Sessions (7d)</span><strong>{activity.sessions7d}</strong></div>
      <div className="row"><span>App version</span><strong>{activity.lastAppVersion ?? "—"}</strong></div>
      <div className="row"><span>Last seen</span><strong>{activity.lastHeartbeatAt ? new Date(activity.lastHeartbeatAt).toLocaleString() : "—"}</strong></div>
      <div className="row"><span>Playback errors (7d)</span><strong>{activity.errors7d}</strong></div>
    </div>
  );
}
```

- [ ] **Step 2:** In `MemberDetail.tsx`, add an `"Activity"` tab alongside the existing `"Addons"`/`"Access"` tabs. **Widen the local `Tab` union type to include `"activity"`** (C6 — it is currently `"addons" | "access"`), add `activity: MemberActivity | null` to the component props, import `MemberActivity` from `../lib/api.js`, and render `<ActivityTab activity={activity} />` when that tab is selected (follow the exact tab-switch pattern already in the file).

- [ ] **Step 3:** In `App.tsx`, where `reloadSelected(userId)` fetches member detail + access in parallel (`selectMember` flow), also call `api.getMemberActivity(userId)`, store it in state, and pass it through to `<MemberDetail activity={activity} ... />`. Clear it on member change.

- [ ] **Step 4: Manual verification**

Run the admin dev server, open a member who has telemetry (from Phase 2 Task 2.4), click the Activity tab. Expected: watch-time figures, app version, last-seen, and error count render; a high-usage member shows the sharing warning.

- [ ] **Step 5: Commit**

```bash
git add kevbox-admin/apps/web/src/web/components/ActivityTab.tsx kevbox-admin/apps/web/src/web/components/MemberDetail.tsx kevbox-admin/apps/web/src/web/App.tsx
git commit -m "feat(telemetry): per-member Activity tab"
```

### Task 3.5: Fleet view (stats + leaderboards + going-dark)

**Files:**
- Create: `kevbox-admin/apps/web/src/web/components/FleetView.tsx`
- Modify: `kevbox-admin/apps/web/src/web/App.tsx`

- [ ] **Step 1: Write the component**

Create `kevbox-admin/apps/web/src/web/components/FleetView.tsx`:

```tsx
import { useEffect, useState } from "react";
import type { FleetStats, ActivityRankRow, GoingDarkRow } from "../lib/api.js";
import { Api } from "../lib/api.js";

const fmt = (s: number) => `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;

export function FleetView({ api, onOpenMember }: { api: Api; onOpenMember: (userId: string) => void }) {
  const [stats, setStats] = useState<FleetStats | null>(null);
  const [most, setMost] = useState<ActivityRankRow[]>([]);
  const [least, setLeast] = useState<ActivityRankRow[]>([]);
  const [dark, setDark] = useState<GoingDarkRow[]>([]);
  const [window, setWindow] = useState<"today" | "7d" | "30d">("7d");

  useEffect(() => {
    api.getFleetStats().then((r) => setStats(r.stats));
    api.getGoingDark().then((r) => setDark(r.rows));
  }, [api]);
  useEffect(() => {
    api.getLeaderboard(window, "most").then((r) => setMost(r.rows));
    api.getLeaderboard(window, "least").then((r) => setLeast(r.rows));
  }, [api, window]);

  return (
    <div>
      <h3>Fleet</h3>
      {stats && (
        <div className="stat-grid">
          <div><span className="muted">DAU</span><strong>{stats.dau}</strong></div>
          <div><span className="muted">WAU</span><strong>{stats.wau}</strong></div>
          <div><span className="muted">MAU</span><strong>{stats.mau}</strong></div>
          <div><span className="muted">Watch (30d)</span><strong>{stats.totalWatchHours}h</strong></div>
          <div><span className="muted">Going dark</span><strong>{stats.goingDark}</strong></div>
          <div><span className="muted">Errors (7d)</span><strong>{stats.errors7d}</strong></div>
        </div>
      )}
      <label>Window:{" "}
        <select value={window} onChange={(e) => setWindow(e.target.value as any)}>
          <option value="today">Today</option><option value="7d">7 days</option><option value="30d">30 days</option>
        </select>
      </label>
      <h4>Most active</h4>
      {most.map((r) => (
        <div key={r.userId} className="member-row" role="button" onClick={() => onOpenMember(r.userId)}>
          <span>{r.email ?? r.userId}</span><span>{fmt(r.watchSeconds)}</span></div>
      ))}
      <h4>Least active</h4>
      {least.map((r) => (
        <div key={r.userId} className="member-row" role="button" onClick={() => onOpenMember(r.userId)}>
          <span>{r.email ?? r.userId}</span><span>{fmt(r.watchSeconds)}</span></div>
      ))}
      <h4>Going dark (active, no watch in 14d)</h4>
      {dark.map((r) => (
        <div key={r.userId} className="member-row" role="button" onClick={() => onOpenMember(r.userId)}>
          <span>{r.email ?? r.userId}</span>
          <span>{r.lastHeartbeatAt ? new Date(r.lastHeartbeatAt).toLocaleDateString() : "never"}</span></div>
      ))}
    </div>
  );
}
```

- [ ] **Step 2:** In `App.tsx` (C6 — these are real type changes, not just JSX):
  - **Widen the `view` state union to include `"fleet"`** (currently `"member" | "bulk"`), and add a `"Fleet"` button in the left pane next to "Bulk operations" that sets `view = "fleet"`.
  - Add a `view === "fleet"` branch rendering `<FleetView api={api} onOpenMember={openMemberById} />`.
  - Implement `openMemberById(userId: string)` by **reusing the existing `?member=` deep-link path** (`api.getMember(userId)` → set selected → `reloadSelected`), **not** `selectMember(summary)` — a leaderboard/going-dark `userId` may not be present in the loaded `MemberSummary[]`, so an in-memory lookup can miss.

- [ ] **Step 3:** Add minimal CSS for `.stat-grid` to `index.css` (grid of small stat cards using the existing `--panel`/`--border`/`--muted` vars).

- [ ] **Step 4: Build + typecheck the full UI (C7)**

Run: `cd kevbox-admin && npm run -w packages/core build && npm run -w apps/web build`
Expected: no TypeScript errors — this is the first step that compiles `ActivityTab`/`FleetView`/`MemberDetail`/`App` together, so it catches a missing `export type` re-export or an un-widened `view`/`Tab` union.

- [ ] **Step 5: Manual verification**

Open the admin app → Fleet. Expected: DAU/WAU/MAU + watch hours render; most/least leaderboards populate and the window selector re-queries; going-dark list shows inactive-but-active members; clicking any row opens that member (with the Activity tab available).

- [ ] **Step 6: Commit**

```bash
git add kevbox-admin/apps/web/src/web/components/FleetView.tsx kevbox-admin/apps/web/src/web/App.tsx kevbox-admin/apps/web/src/web/index.css
git commit -m "feat(telemetry): fleet dashboard with leaderboards + going-dark"
```

---

# PHASE 4 — Retention

### Task 4.1: prune_telemetry function (TDD)

**Files:**
- Modify: `kevbox-admin/packages/core/test/telemetry.test.ts`
- Modify: `kevbox-admin/packages/core/test/schema.sql`
- Modify: `member_telemetry_setup.sql`

- [ ] **Step 1: Write the failing test** — append to `telemetry.test.ts`:

```typescript
describe("prune_telemetry", () => {
  test("deletes old events and old daily rows, keeps recent", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "p@test.dev");
      await db.query("insert into public.member_event(user_id, kind, occurred_at) values ($1,'playback_error', now() - interval '200 days')", [uid]);
      await db.query("insert into public.member_event(user_id, kind, occurred_at) values ($1,'playback_error', now() - interval '2 days')", [uid]);
      await db.query("insert into public.member_activity_daily(user_id, day, watch_seconds) values ($1, (now() at time zone 'utc')::date - 500, 10)", [uid]);
      await db.query("insert into public.member_activity_daily(user_id, day, watch_seconds) values ($1, (now() at time zone 'utc')::date - 2, 10)", [uid]);
      await db.query("select public.prune_telemetry(90, 396)");
      const ev = await db.query("select count(*)::int as n from public.member_event where user_id=$1", [uid]);
      const dl = await db.query("select count(*)::int as n from public.member_activity_daily where user_id=$1", [uid]);
      expect(ev.rows[0].n).toBe(1);   // 200d pruned, 2d kept
      expect(dl.rows[0].n).toBe(1);   // 500d pruned, 2d kept
    });
  });

  test("retention boundary is strict: rows at the cutoff are kept, one past it is pruned", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "pb@test.dev");
      // events use strict `occurred_at < now() - 90d`: 89d kept, 91d pruned
      await db.query("insert into public.member_event(user_id, kind, occurred_at) values ($1,'playback_error', now() - interval '89 days'),($1,'playback_error', now() - interval '91 days')", [uid]);
      // daily uses strict `day < today - 396`: day-396 kept, day-397 pruned
      await db.query("insert into public.member_activity_daily(user_id, day, watch_seconds) values ($1, (now() at time zone 'utc')::date - 396, 10),($1, (now() at time zone 'utc')::date - 397, 10)", [uid]);
      await db.query("select public.prune_telemetry(90, 396)");
      const ev = await db.query("select count(*)::int as n from public.member_event where user_id=$1", [uid]);
      const dl = await db.query("select count(*)::int as n from public.member_activity_daily where user_id=$1", [uid]);
      expect(ev.rows[0].n).toBe(1);   // 89d kept, 91d pruned
      expect(dl.rows[0].n).toBe(1);   // day-396 kept, day-397 pruned
    });
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd kevbox-admin/packages/core && npx vitest run test/telemetry.test.ts`
Expected: FAIL — `function public.prune_telemetry(...) does not exist`.

- [ ] **Step 3: Add the function** — append to **both** `kevbox-admin/packages/core/test/schema.sql` and section 3/4 of `member_telemetry_setup.sql`:

```sql
create or replace function public.prune_telemetry(p_event_days int default 90, p_aggregate_days int default 396)
  returns text language plpgsql security definer set search_path = '' as $$
declare v_events int; v_days int;
begin
  delete from public.member_event where occurred_at < now() - make_interval(days => p_event_days);
  get diagnostics v_events = row_count;
  delete from public.member_activity_daily where day < (now() at time zone 'utc')::date - p_aggregate_days;
  get diagnostics v_days = row_count;
  return format('pruned %s events, %s daily rows', v_events, v_days);
end $$;
```
In `member_telemetry_setup.sql` only, also add:
```sql
revoke all on function public.prune_telemetry(int, int) from public, anon, authenticated;
grant execute on function public.prune_telemetry(int, int) to kevbox_admin;
-- Optional automated schedule (uncomment if pg_cron is enabled on this project):
--   select cron.schedule('prune_telemetry_daily', '0 4 * * *', $$ select public.prune_telemetry(90, 396) $$);
```

- [ ] **Step 4: Recreate DB + run**

Run:
```bash
cd kevbox-admin && docker compose down -v && docker compose up -d test-db && sleep 4
cd packages/core && npx vitest run test/telemetry.test.ts
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kevbox-admin/packages/core/test/schema.sql kevbox-admin/packages/core/test/telemetry.test.ts member_telemetry_setup.sql
git commit -m "feat(telemetry): prune_telemetry retention function + test"
```

### Task 4.2: Scheduled prune — in-process daily timer (primary) + manual admin route

**Files:**
- Modify: `kevbox-admin/packages/core/src/activity.ts` (add `pruneTelemetry` export)
- Modify: `kevbox-admin/apps/web/src/server/routes/activity.ts` (manual trigger)
- Modify: `kevbox-admin/apps/web/src/server/app.ts` (register the in-process timer)

- [ ] **Step 1:** Add to `activity.ts`:
```typescript
export async function pruneTelemetry(db: Db): Promise<string> {
  const { rows } = await db.query("select public.prune_telemetry(90, 396) as result");
  return rows[0].result as string;
}
```

- [ ] **Step 2: Primary automation — in-process daily timer (M4).** There is **no** existing scheduler in the repo (only the long-running Fastify server), and pg_cron may be disabled (spec §14). Drive the prune from inside the always-on server, calling `pruneTelemetry(opts.db)` directly against the pool — no HTTP, no auth. In `app.ts`, after routes are registered, add a guarded daily interval cleared on close:
```typescript
// Daily retention prune (fallback when pg_cron is unavailable). Fail-soft: log and continue.
const pruneTimer = setInterval(() => {
  pruneTelemetry(opts.db)
    .then((r) => app.log.info({ prune: r }, "telemetry retention prune"))
    .catch((e) => app.log.error({ err: e }, "telemetry prune failed"));
}, 24 * 60 * 60 * 1000);
pruneTimer.unref?.();
app.addHook("onClose", async () => clearInterval(pruneTimer));
```

- [ ] **Step 3: Manual trigger (operator-initiated only).** Add an ad-hoc route to `routes/activity.ts`. **NOTE (L7):** it sits behind the `api` scope's `requireAdmin`, so it needs an admin JWT and is **not** callable by a headless scheduler — Step 2's timer is the unattended path.
```typescript
app.post("/activity/prune", async () => ({ result: await pruneTelemetry(db) }));
```

- [ ] **Step 4: Build + verify**

Run: `cd kevbox-admin && npm run -w packages/core build && npm run -w apps/web build`
Then manual: `POST /api/activity/prune` (as an admin) returns `{ result: "pruned N events, M daily rows" }`; the server log shows a `telemetry retention prune` line on the daily tick.

- [ ] **Step 5: Commit**
```bash
git add kevbox-admin/packages/core/src/activity.ts kevbox-admin/apps/web/src/server/routes/activity.ts kevbox-admin/apps/web/src/server/app.ts
git commit -m "feat(telemetry): scheduled in-process retention prune + manual admin route"
```

### Task 4.3: Apply retention to prod + privacy notice

- [ ] **Step 1: Verify pg_cron, then pick ONE schedule (M4).** In the SQL editor: `select * from pg_extension where extname='pg_cron';`
  - **Enabled** → uncomment and run the `cron.schedule('prune_telemetry_daily', …)` line in `member_telemetry_setup.sql`.
  - **Absent / non-enableable** (likely on stock Supabase) → rely on the in-process daily timer shipped in Task 4.2 Step 2. Do **not** plan a cron'd `POST /api/activity/prune` — that route is behind `requireAdmin` (L7) and has no headless caller.
- [ ] **Step 2:** Re-run the updated `member_telemetry_setup.sql` against `scmqdptagksltnwiveyh` (idempotent).
- [ ] **Step 3: Verify the prune grant (L4).** As `kevbox_admin`, `select public.prune_telemetry(90, 396);` succeeds; as a member/anon JWT it fails with `permission denied for function prune_telemetry`.
- [ ] **Step 4: Privacy notice timing (L12/C8).** Spec §11's one-line activity/diagnostics notice must ship **no later than the build that turns `FEATURE_TELEMETRY` on in prod** — land it in Phase 2, *or* default `FEATURE_TELEMETRY=false` in the `full` flavor until the notice is live. (Each phase is independently shippable, so Phase 2 alone would otherwise collect data before any notice exists.) Add it to the app's onboarding/settings text and the member-facing terms; operator confirms final wording.
- [ ] **Step 5: Commit** any app-side notice copy changes.

---

## Self-review

**Spec coverage:**
- §1 retention/churn → Task 3.1 `listGoingDark` + 3.5 going-dark list. ✓
- §1 support/ops → 3.1 `getMemberActivity` (app version, last-seen, errors) + 3.4 Activity tab. ✓
- §1 activity rankings (daily/weekly/monthly) → 1.2 accrual + 3.1 `listMembersByActivity` + 3.5 leaderboards with window toggle. ✓
- §1 abuse/sharing → 3.1 `sharingSuspect` (18h/day rule) + 3.4 warning. ✓
- §2 "how much not what" → no content fields anywhere; heartbeat sends only device/version/kind. ✓
- §5 schema → Tasks 1.1–1.3 (note: `member_heartbeat` table replaces the `member_device` columns — Deviation 1). ✓
- §6 capped accrual (120s) → Task 1.2 cap test + function. ✓
- §7 RPC contracts → Task 1.4 `record_heartbeat`/`record_error`, now with a true `p_kind` **allowlist** (unknown kinds no-op, M5) and `message_short` URL-stripping (L2). ✓
- §8 abuse signals → covered (impossible-hours; device-churn left as an operator-visible follow-up, not auto-flagged — noted; multi-device false-positive caveat in Deviation 3, M6).
- §9 admin read/UI → Phase 3 (Deviation 2: TS aggregation instead of `member_activity_v`; view-only fields `access_active`/`device_count`/per-member `going_dark` intentionally not re-surfaced — Deviation 4; "Last seen" relabeled "Last played" — Deviation 5). ✓
- §10 Android → Phase 2 (start/stop on `onIsPlayingChanged` + background/release stops; `session_start` once per playback — H2/M9). ✓
- §11 privacy/security/retention → RLS + grants + **inner-function lockdown** (1.4 §3b, H1), key-allowlist + URL-strip (1.3), prune (4.1) + scheduled in-process timer (4.2), notice timed to the telemetry-on build (4.3, L12). Security model verified by the Task 1.5 manual checklist (untestable in the superuser vitest harness, M10). ✓
- §12 defaults → 120s cap (1.2/1.4), 90d/396d retention (4.1). ✓

**Placeholder scan:** One intentional marker — section 3 of `member_telemetry_setup.sql` says "paste the three function bodies from schema.sql verbatim" (Task 1.4 Step 2 performs the copy; the bodies are fully written in Tasks 1.2/1.3, not invented; a byte-identical drift guard is noted there, M2). The earlier non-compiling `io.mockk.mockkStatic::class` placeholder in `HeartbeatSchedulerTest` has been **removed** (H3). No other TODOs.

**Type consistency:** Core interfaces (`MemberActivity`, `ActivityRankRow`, `GoingDarkRow`, `FleetStats`) are defined in `activity.ts` (Task 3.1) and re-exported for the API client (3.3) and components (3.4/3.5). RPC names (`record_heartbeat`, `record_error`) and inner fns (`accrue_heartbeat`, `record_session_start`, `record_error_event`, `prune_telemetry`) are consistent across SQL, the Kotlin repo (2.2), and tests.

**Known follow-ups (out of scope, logged):**
- Device-replacement-churn sharing signal (§8) is not auto-computed — only impossible-hours is; add later if wanted.
- Watch-time day bucketing is UTC (spec §12 open question).
- Multi-device accounts can legitimately exceed the 18h sharing threshold (sum across devices) — advisory flag may false-positive (Deviation 3 caveat, M6).
- No server-side rate-limit beyond once-per-playback `session_start` (H2) + pruning; a per-`(user,device,day)` session/error ceiling is deferred (L10).
- First-run / un-updated clients: going-dark lists the whole active fleet and DAU/WAU/MAU read 0 until clients beat (no back-fill possible) — misleading for ~14 d (L15); optionally guard going-dark to "has ≥1 activity/heartbeat ever."
- Test schema omits the `member_access` seed trigger/backfill, so going-dark enumeration is exercised with explicit seeds only (C3) — documented, not closed.

**Gap review applied:** This plan was hardened against a multi-agent gap analysis (see `docs/superpowers/reviews/2026-06-09-member-activity-telemetry-gaps.md`). All 3 High (H1 inner-fn lockdown, H2 player wiring, H3 broken test), the actionable Mediums (M1–M11), and the Lows/critic items (L1–L16, C1–C8) are addressed inline above; refuted candidates were intentionally not changed.
