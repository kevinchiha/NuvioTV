# KevBox Admin — Plan 1: Monorepo Scaffold + `core` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `kevbox-admin` TypeScript monorepo and a fully-tested `@kevbox-admin/core` package containing every member_addon operation the CLI and web admin will share.

**Architecture:** npm-workspaces monorepo. `core` is a pure library: each operation is a function taking an injected `pg` client (`Db`) so it holds no secrets/connection. Data access is direct Postgres. Tests run against a disposable local Postgres (docker) seeded with a minimal schema, each test wrapped in a transaction that rolls back.

**Tech Stack:** Node 20+, TypeScript (ESM), `pg`, `vitest`, Docker (local test Postgres).

**Spec:** `docs/superpowers/specs/2026-06-09-kevbox-admin-design.md`

---

## File Structure (locked in this plan)

```
kevbox-admin/
  package.json                 # workspaces root
  tsconfig.base.json
  .gitignore
  docker-compose.yml           # local test Postgres on :5433
  packages/
    core/
      package.json             # @kevbox-admin/core
      tsconfig.json
      vitest.config.ts
      test/
        schema.sql             # minimal auth.users + member_addon + view + default_member_addons()
        helpers.ts             # pool, withRollback(), createTestMember()
        defaults.test.ts
        members.test.ts
        addons.test.ts
        reset.test.ts
        debrid.test.ts
        bulk.test.ts
      src/
        types.ts               # MemberSummary, AddonRow, MemberDetail, Db
        defaults.ts            # buildTorrentioUrl(), debrid constants
        members.ts             # listMembers, getMember
        addons.ts              # addAddon, updateAddon, setEnabled, reorder, deleteAddon
        reset.ts               # resetToDefaults
        debrid.ts              # onboardDebrid
        bulk.ts                # bulkAddAddon, bulkSwapUrl
        index.ts               # barrel re-exports
```

Each `src/*.ts` module owns one responsibility and is tested by the matching `test/*.test.ts`.

---

## Task 1: Monorepo scaffold

**Files:**
- Create: `package.json`, `tsconfig.base.json`, `.gitignore`

- [ ] **Step 1: Create the root `package.json`**

```json
{
  "name": "kevbox-admin",
  "private": true,
  "type": "module",
  "workspaces": ["packages/*", "apps/*"],
  "engines": { "node": ">=20" },
  "scripts": {
    "test": "npm run test --workspaces --if-present",
    "build": "npm run build --workspaces --if-present"
  }
}
```

- [ ] **Step 2: Create `tsconfig.base.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "declaration": true,
    "sourceMap": true,
    "forceConsistentCasingInFileNames": true
  }
}
```

- [ ] **Step 3: Create `.gitignore`**

```
node_modules/
dist/
*.log
.env
.env.*
!.env.example
```

- [ ] **Step 4: Commit**

```bash
git add package.json tsconfig.base.json .gitignore
git commit -m "chore: monorepo scaffold (npm workspaces + tsconfig base)"
```

---

## Task 2: `core` package skeleton + types

**Files:**
- Create: `packages/core/package.json`, `packages/core/tsconfig.json`, `packages/core/src/types.ts`

- [ ] **Step 1: Create `packages/core/package.json`**

```json
{
  "name": "@kevbox-admin/core",
  "version": "0.1.0",
  "type": "module",
  "main": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "exports": { ".": { "types": "./dist/index.d.ts", "default": "./dist/index.js" } },
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run"
  },
  "dependencies": { "pg": "^8.12.0" },
  "devDependencies": {
    "@types/node": "^20.14.0",
    "@types/pg": "^8.11.6",
    "typescript": "^5.5.0",
    "vitest": "^2.0.0"
  }
}
```

- [ ] **Step 2: Create `packages/core/tsconfig.json`**

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": { "rootDir": "src", "outDir": "dist" },
  "include": ["src"]
}
```

- [ ] **Step 3: Create `packages/core/src/types.ts`**

```ts
import type { Pool, PoolClient } from "pg";

/** Any pg connection we can run queries on — a Pool or a checked-out client (used in tests/txns). */
export type Db = Pick<Pool | PoolClient, "query">;

export interface MemberSummary {
  userId: string;
  email: string | null;
  createdAt: string;
  addonCount: number;
  /** true if the member has any addon NOT in default_member_addons() (e.g. a debrid source). */
  hasDebrid: boolean;
}

export interface AddonRow {
  id: number;
  userId: string;
  url: string;
  enabled: boolean;
  sortOrder: number;
  updatedAt: string;
}

export interface MemberDetail {
  userId: string;
  email: string | null;
  addons: AddonRow[];
}

/** Maps a raw member_addon DB row (bigint id arrives as string) to AddonRow. */
export function mapAddonRow(r: {
  id: string | number;
  user_id: string;
  url: string;
  enabled: boolean;
  sort_order: number;
  updated_at: string | Date;
}): AddonRow {
  return {
    id: Number(r.id),
    userId: r.user_id,
    url: r.url,
    enabled: r.enabled,
    sortOrder: r.sort_order,
    updatedAt: new Date(r.updated_at).toISOString(),
  };
}
```

- [ ] **Step 4: Install dependencies**

Run: `npm install`
Expected: installs `pg`, `vitest`, TypeScript, types; creates `package-lock.json`.

- [ ] **Step 5: Commit**

```bash
git add packages/core/package.json packages/core/tsconfig.json packages/core/src/types.ts package-lock.json
git commit -m "feat(core): package skeleton + shared types"
```

---

## Task 3: Test infrastructure (local Postgres + helpers)

**Files:**
- Create: `docker-compose.yml`, `packages/core/test/schema.sql`, `packages/core/test/helpers.ts`, `packages/core/vitest.config.ts`

- [ ] **Step 1: Create `docker-compose.yml`** (local test Postgres on port 5433, schema auto-applied)

```yaml
services:
  test-db:
    image: postgres:16
    environment:
      POSTGRES_PASSWORD: test
      POSTGRES_DB: kevbox_test
    ports:
      - "5433:5432"
    volumes:
      - ./packages/core/test/schema.sql:/docker-entrypoint-initdb.d/schema.sql:ro
```

- [ ] **Step 2: Create `packages/core/test/schema.sql`** (minimal, faithful subset — no Supabase roles/RLS needed for core unit tests; `auth.users` reduced to the columns core reads)

```sql
create extension if not exists pgcrypto;
create schema if not exists auth;

create table auth.users (
  id         uuid primary key default gen_random_uuid(),
  email      text unique,
  created_at timestamptz not null default now()
);

create table public.member_addon (
  id          bigint generated always as identity primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
  url         text not null,
  enabled     boolean not null default true,
  sort_order  int not null default 0,
  updated_at  timestamptz not null default now(),
  unique (user_id, url)
);

create view public.member_addon_v as
  select m.*, u.email as auth_email
  from public.member_addon m join auth.users u on u.id = m.user_id;

-- Mirrors the production default_member_addons() shape. Test URLs (4 distinct rows) — the
-- core logic only depends on there being N default rows, not on the exact prod URLs.
create function public.default_member_addons() returns table (url text, sort_order int)
  language sql immutable as $$
  values
    ('https://v3-cinemeta.strem.io', 0),
    ('https://opensubtitlesv3-pro.example/cfg/manifest.json', 1),
    ('https://opensubtitles-v3.strem.io', 2),
    ('https://netflix-catalog.example/cfg/manifest.json', 3)
$$;
```

- [ ] **Step 3: Create `packages/core/vitest.config.ts`**

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    include: ["test/**/*.test.ts"],
    fileParallelism: false, // share one test DB; rollback gives isolation
  },
});
```

- [ ] **Step 4: Create `packages/core/test/helpers.ts`**

```ts
import pg from "pg";
import type { Db } from "../src/types.js";

const { Pool } = pg;

const TEST_DATABASE_URL =
  process.env.TEST_DATABASE_URL ??
  "postgres://postgres:test@localhost:5433/kevbox_test";

export const pool = new Pool({ connectionString: TEST_DATABASE_URL });

/** Run `fn` inside a transaction that ALWAYS rolls back — keeps tests isolated and side-effect free. */
export async function withRollback<T>(fn: (db: Db) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    return await fn(client);
  } finally {
    await client.query("ROLLBACK");
    client.release();
  }
}

/** Insert a throwaway member into the test auth.users; returns its userId. */
export async function createTestMember(db: Db, email: string): Promise<string> {
  const { rows } = await db.query<{ id: string }>(
    "insert into auth.users (email) values ($1) returning id",
    [email],
  );
  return rows[0].id;
}
```

- [ ] **Step 5: Start the test DB and verify connectivity with a smoke test**

Create `packages/core/test/smoke.test.ts`:

```ts
import { afterAll, expect, test } from "vitest";
import { pool, withRollback, createTestMember } from "./helpers.js";

afterAll(async () => { await pool.end(); });

test("test DB is reachable and schema is applied", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "smoke@test.dev");
    expect(id).toMatch(/^[0-9a-f-]{36}$/);
    const { rows } = await db.query("select count(*)::int as n from public.default_member_addons()");
    expect(rows[0].n).toBe(4);
  });
});
```

- [ ] **Step 6: Run the smoke test**

Run: `docker compose up -d test-db && sleep 3 && npm test -w @kevbox-admin/core -- smoke`
Expected: PASS (DB reachable, 4 default rows, member created+rolled back).

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml packages/core/test/schema.sql packages/core/test/helpers.ts packages/core/vitest.config.ts packages/core/test/smoke.test.ts
git commit -m "test(core): local Postgres harness + rollback helpers"
```

---

## Task 4: `buildTorrentioUrl` + debrid constants (pure, no DB)

**Files:**
- Create: `packages/core/src/defaults.ts`, `packages/core/test/defaults.test.ts`

- [ ] **Step 1: Write the failing test** (`packages/core/test/defaults.test.ts`)

```ts
import { expect, test } from "vitest";
import { buildTorrentioUrl, DEBRID_TORRENTIO_SORT, DEBRID_AIOSTREAMS_SORT } from "../src/defaults.js";

test("buildTorrentioUrl injects the premiumize key and keeps the manifest suffix", () => {
  const url = buildTorrentioUrl("ABC123key");
  expect(url).toContain("premiumize=ABC123key");
  expect(url.startsWith("https://torrentio.strem.fun/")).toBe(true);
  expect(url.endsWith("/manifest.json")).toBe(true);
});

// DRIFT GUARD: this exact string MUST match the Torrentio template in
// MEMBER-DEBRID-ONBOARDING.md (runbook line ~52). The spec (§4.3) names "keep in sync with the
// runbook" as a hazard with no enforcement; this pinned assertion IS the enforcement — if either
// side changes, this test fails and forces both to be updated together.
test("buildTorrentioUrl matches the MEMBER-DEBRID-ONBOARDING.md template byte-for-byte", () => {
  expect(buildTorrentioUrl("KEY")).toBe(
    "https://torrentio.strem.fun/qualityfilter=unknown,cam,4k,scr|limit=5|sizefilter=4GB|" +
      "debridoptions=nodownloadlinks,nocatalog|premiumize=KEY/manifest.json",
  );
});

test("debrid sort positions are 4 (torrentio) and 5 (aiostreams), after the 4 universal defaults (0-3)", () => {
  expect(DEBRID_TORRENTIO_SORT).toBe(4);
  expect(DEBRID_AIOSTREAMS_SORT).toBe(5);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -w @kevbox-admin/core -- defaults`
Expected: FAIL — cannot find module `../src/defaults.js`.

- [ ] **Step 3: Write `packages/core/src/defaults.ts`**

```ts
// Debrid URL templates — MIRROR of MEMBER-DEBRID-ONBOARDING.md (KevBox repo). Keep in sync.
// Torrentio qualityfilter is an EXCLUDE list (filters OUT unknown/cam/4k/scr).

export const DEBRID_TORRENTIO_SORT = 4;
export const DEBRID_AIOSTREAMS_SORT = 5;

/** Build a member's Torrentio (Premiumize) manifest URL from their own key. */
export function buildTorrentioUrl(premiumizeKey: string): string {
  const key = premiumizeKey.trim();
  if (!key) throw new Error("premiumizeKey is required");
  return (
    "https://torrentio.strem.fun/" +
    "qualityfilter=unknown,cam,4k,scr|limit=5|sizefilter=4GB|" +
    "debridoptions=nodownloadlinks,nocatalog|" +
    `premiumize=${key}/manifest.json`
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -w @kevbox-admin/core -- defaults`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add packages/core/src/defaults.ts packages/core/test/defaults.test.ts
git commit -m "feat(core): buildTorrentioUrl + debrid sort constants"
```

---

## Task 5: `listMembers` + `getMember`

**Files:**
- Create: `packages/core/src/members.ts`, `packages/core/test/members.test.ts`

- [ ] **Step 1: Write the failing test** (`packages/core/test/members.test.ts`)

```ts
import { expect, test } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { listMembers, getMember } from "../src/members.js";

test("listMembers reports addon count and hasDebrid", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "a@test.dev");
    await createTestMember(db, "b@test.dev"); // no addons
    // a: one default addon + one extra (debrid-like)
    await db.query(
      "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://v3-cinemeta.strem.io',0),($1,'https://torrentio.strem.fun/x/manifest.json',4)",
      [a],
    );
    const members = await listMembers(db);
    const ma = members.find((m) => m.email === "a@test.dev")!;
    const mb = members.find((m) => m.email === "b@test.dev")!;
    expect(ma.addonCount).toBe(2);
    expect(ma.hasDebrid).toBe(true);
    expect(mb.addonCount).toBe(0);
    expect(mb.hasDebrid).toBe(false);
  });
});

test("getMember resolves by email or userId and returns addons in sort order", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "c@test.dev");
    await db.query(
      "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://b.example',1),($1,'https://a.example',0)",
      [id],
    );
    const byEmail = await getMember(db, "c@test.dev");
    const byId = await getMember(db, id);
    expect(byEmail!.userId).toBe(id);
    expect(byId!.email).toBe("c@test.dev");
    expect(byEmail!.addons.map((x) => x.url)).toEqual(["https://a.example", "https://b.example"]);
  });
});

test("getMember returns null for an unknown member", async () => {
  await withRollback(async (db) => {
    expect(await getMember(db, "nobody@test.dev")).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -w @kevbox-admin/core -- members`
Expected: FAIL — cannot find module `../src/members.js`.

- [ ] **Step 3: Write `packages/core/src/members.ts`**

```ts
import type { Db, MemberSummary, MemberDetail } from "./types.js";
import { mapAddonRow } from "./types.js";

export async function listMembers(db: Db): Promise<MemberSummary[]> {
  // hasDebrid is a COSMETIC overview badge only (no operation gates on it). It is an exact-URL
  // anti-join against default_member_addons(): "has any addon whose url is NOT a current default".
  // Caveat: if a default URL is refreshed in default_member_addons() while a member still holds the
  // old one (the token-rotation case the runbook calls out), that member is transiently flagged
  // hasDebrid=true until resetToDefaults re-seeds them. Acceptable because it is display-only.
  const { rows } = await db.query(
    `select u.id as user_id, u.email, u.created_at,
            (select count(*) from public.member_addon m where m.user_id = u.id)::int as addon_count,
            exists (
              select 1 from public.member_addon m
              where m.user_id = u.id
                and m.url not in (select url from public.default_member_addons())
            ) as has_debrid
       from auth.users u
      order by u.email nulls last`,
  );
  return rows.map((r: any) => ({
    userId: r.user_id,
    email: r.email,
    createdAt: new Date(r.created_at).toISOString(),
    addonCount: r.addon_count,
    hasDebrid: r.has_debrid,
  }));
}

/** ref = email or userId (uuid). Returns null if no such member. */
export async function getMember(db: Db, ref: string): Promise<MemberDetail | null> {
  const { rows: u } = await db.query(
    `select id, email from auth.users where id::text = $1 or email = $1 limit 1`,
    [ref],
  );
  if (u.length === 0) return null;
  const userId = u[0].id as string;
  const { rows } = await db.query(
    `select id, user_id, url, enabled, sort_order, updated_at
       from public.member_addon where user_id = $1 order by sort_order, id`,
    [userId],
  );
  return { userId, email: u[0].email, addons: rows.map(mapAddonRow) };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -w @kevbox-admin/core -- members`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add packages/core/src/members.ts packages/core/test/members.test.ts
git commit -m "feat(core): listMembers + getMember"
```

---

## Task 6: `addAddon`, `updateAddon`, `setEnabled`, `reorder`, `deleteAddon`

**Files:**
- Create: `packages/core/src/addons.ts`, `packages/core/test/addons.test.ts`

- [ ] **Step 1: Write the failing test** (`packages/core/test/addons.test.ts`)

```ts
import { expect, test } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { addAddon, updateAddon, setEnabled, reorder, deleteAddon } from "../src/addons.js";
import { getMember } from "../src/members.js";

test("addAddon appends at next sort_order and upserts on duplicate url", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "a@test.dev");
    const first = await addAddon(db, id, { url: "https://a.example" });
    expect(first.sortOrder).toBe(0);
    const second = await addAddon(db, id, { url: "https://b.example" });
    expect(second.sortOrder).toBe(1);
    // duplicate url upserts (enabled flips), does not create a second row
    const dup = await addAddon(db, id, { url: "https://a.example", enabled: false });
    expect(dup.enabled).toBe(false);
    expect((await getMember(db, id))!.addons).toHaveLength(2);
  });
});

test("updateAddon changes only provided fields; setEnabled flips enabled", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "b@test.dev");
    const row = await addAddon(db, id, { url: "https://a.example" });
    const updated = await updateAddon(db, row.id, { url: "https://a2.example" });
    expect(updated!.url).toBe("https://a2.example");
    expect(updated!.enabled).toBe(true); // unchanged
    const toggled = await setEnabled(db, row.id, false);
    expect(toggled!.enabled).toBe(false);
  });
});

test("reorder rewrites sort_order to match the given id order (scoped to the member)", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "c@test.dev");
    const a = await addAddon(db, id, { url: "https://a.example" });
    const b = await addAddon(db, id, { url: "https://b.example" });
    const c = await addAddon(db, id, { url: "https://c.example" });
    await reorder(db, id, [c.id, a.id, b.id]);
    const urls = (await getMember(db, id))!.addons.map((x) => x.url);
    expect(urls).toEqual(["https://c.example", "https://a.example", "https://b.example"]);
  });
});

test("deleteAddon removes the row", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "d@test.dev");
    const row = await addAddon(db, id, { url: "https://a.example" });
    await deleteAddon(db, row.id);
    expect((await getMember(db, id))!.addons).toHaveLength(0);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -w @kevbox-admin/core -- addons`
Expected: FAIL — cannot find module `../src/addons.js`.

- [ ] **Step 3: Write `packages/core/src/addons.ts`**

```ts
import type { Db, AddonRow } from "./types.js";
import { mapAddonRow } from "./types.js";

const RETURNING = "id, user_id, url, enabled, sort_order, updated_at";

/** A caller-input error. `statusCode` lets the web layer return 400 (not a generic 500). */
function validationError(message: string): Error {
  const e = new Error(message) as Error & { statusCode?: number };
  e.statusCode = 400;
  return e;
}

/**
 * Trim + validate an addon URL: must be a syntactically valid http(s) URL. A bad/typo'd URL is
 * otherwise written straight through and mirrored to the member's TVs with no feedback (spec §4.6
 * only required "non-empty"). This is the single chokepoint both the CLI and web go through.
 */
function normalizeAddonUrl(raw: string): string {
  const url = (raw ?? "").trim();
  if (!url) throw validationError("url is required");
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw validationError(`invalid url: ${raw}`);
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw validationError(`url must be http(s): ${raw}`);
  }
  return url;
}

export async function addAddon(
  db: Db,
  userId: string,
  opts: { url: string; enabled?: boolean; sortOrder?: number },
): Promise<AddonRow> {
  const url = normalizeAddonUrl(opts.url);
  const enabled = opts.enabled ?? true;
  const { rows } = await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, $3,
             coalesce($4, (select coalesce(max(sort_order)+1, 0) from public.member_addon where user_id = $1)))
     on conflict (user_id, url)
       do update set enabled = excluded.enabled, sort_order = excluded.sort_order, updated_at = now()
     returning ${RETURNING}`,
    [userId, url, enabled, opts.sortOrder ?? null],
  );
  return mapAddonRow(rows[0]);
}

export async function updateAddon(
  db: Db,
  addonId: number,
  fields: { url?: string; enabled?: boolean },
): Promise<AddonRow | null> {
  const url = fields.url === undefined ? null : normalizeAddonUrl(fields.url);
  const { rows } = await db.query(
    `update public.member_addon
        set url = coalesce($2, url), enabled = coalesce($3, enabled), updated_at = now()
      where id = $1 returning ${RETURNING}`,
    [addonId, url, fields.enabled ?? null],
  );
  return rows[0] ? mapAddonRow(rows[0]) : null;
}

export async function setEnabled(db: Db, addonId: number, enabled: boolean): Promise<AddonRow | null> {
  const { rows } = await db.query(
    `update public.member_addon set enabled = $2, updated_at = now() where id = $1 returning ${RETURNING}`,
    [addonId, enabled],
  );
  return rows[0] ? mapAddonRow(rows[0]) : null;
}

/** Rewrite sort_order so the member's addons follow `orderedIds`. Scoped to userId for safety. */
export async function reorder(db: Db, userId: string, orderedIds: number[]): Promise<void> {
  await db.query(
    `update public.member_addon m
        set sort_order = x.ord - 1, updated_at = now()
       from unnest($2::bigint[]) with ordinality as x(id, ord)
      where m.id = x.id and m.user_id = $1`,
    [userId, orderedIds],
  );
}

export async function deleteAddon(db: Db, addonId: number): Promise<void> {
  await db.query("delete from public.member_addon where id = $1", [addonId]);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -w @kevbox-admin/core -- addons`
Expected: PASS (all four tests).

- [ ] **Step 5: Commit**

```bash
git add packages/core/src/addons.ts packages/core/test/addons.test.ts
git commit -m "feat(core): addon CRUD (add/update/setEnabled/reorder/delete)"
```

---

## Task 7: `resetToDefaults`

**Files:**
- Create: `packages/core/src/reset.ts`, `packages/core/test/reset.test.ts`

- [ ] **Step 1: Write the failing test** (`packages/core/test/reset.test.ts`)

```ts
import { expect, test } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { resetToDefaults } from "../src/reset.js";
import { addAddon } from "../src/addons.js";
import { getMember } from "../src/members.js";

test("resetToDefaults replaces a member's rows with default_member_addons()", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "a@test.dev");
    await addAddon(db, id, { url: "https://torrentio.strem.fun/x/manifest.json", sortOrder: 9 });
    await resetToDefaults(db, id);
    const { rows } = await db.query("select url, sort_order from public.default_member_addons() order by sort_order");
    const member = (await getMember(db, id))!;
    expect(member.addons.map((a) => a.url)).toEqual(rows.map((r: any) => r.url));
    expect(member.addons.every((a) => a.enabled)).toBe(true);
  });
});

test("resetToDefaults is idempotent + removes debrid rows with no unique violation, never zero addons", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "b@test.dev");
    await resetToDefaults(db, id); // seed the defaults
    await addAddon(db, id, { url: "https://torrentio.strem.fun/x/manifest.json", sortOrder: 4 });
    // Re-run while the member ALREADY holds every default url (the upsert path) — must not throw.
    await resetToDefaults(db, id);
    const { rows } = await db.query("select url from public.default_member_addons() order by sort_order");
    const member = (await getMember(db, id))!;
    expect(member.addons.map((a) => a.url)).toEqual(rows.map((r: any) => r.url)); // debrid row gone
    expect(member.addons.length).toBeGreaterThan(0); // never wiped to zero
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -w @kevbox-admin/core -- reset`
Expected: FAIL — cannot find module `../src/reset.js`.

- [ ] **Step 3: Write `packages/core/src/reset.ts`**

```ts
import type { Db } from "./types.js";

/**
 * Reset a member to the baked-in universal defaults. Reuses the DB's default_member_addons()
 * function (the single source of truth) instead of hardcoding URLs here.
 *
 * ATOMIC + crash-safe: this is ONE statement, so it is safe even when `db` is a pg.Pool (where
 * separate .query() calls run on different connections in autocommit). The CTE upserts the
 * universal defaults and the main DELETE removes only the member's NON-default rows. The two arms
 * touch disjoint row sets (default-url rows vs. everything else), so — unlike a delete-then-insert —
 * a failure can only leave an *incomplete* reset (safely re-runnable), NEVER a member with zero
 * addons. The `on conflict` upsert also makes re-running idempotent (no unique(user_id,url) violation
 * when the member already holds the defaults).
 */
export async function resetToDefaults(db: Db, userId: string): Promise<void> {
  await db.query(
    `with up as (
       insert into public.member_addon (user_id, url, enabled, sort_order)
       select $1, url, true, sort_order from public.default_member_addons()
       on conflict (user_id, url)
         do update set enabled = true, sort_order = excluded.sort_order, updated_at = now()
     )
     delete from public.member_addon
      where user_id = $1
        and url not in (select url from public.default_member_addons())`,
    [userId],
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -w @kevbox-admin/core -- reset`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add packages/core/src/reset.ts packages/core/test/reset.test.ts
git commit -m "feat(core): resetToDefaults via default_member_addons()"
```

---

## Task 8: `onboardDebrid`

**Files:**
- Create: `packages/core/src/debrid.ts`, `packages/core/test/debrid.test.ts`

- [ ] **Step 1: Write the failing test** (`packages/core/test/debrid.test.ts`)

```ts
import { expect, test } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { onboardDebrid } from "../src/debrid.js";
import { getMember } from "../src/members.js";

test("onboardDebrid inserts Torrentio (sort 4) + AIOStreams (sort 5)", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "a@test.dev");
    await onboardDebrid(db, id, {
      premiumizeKey: "KEY123",
      aiostreamsUrl: "https://aiostreams.example/u/cfg/manifest.json",
    });
    const addons = (await getMember(db, id))!.addons;
    const torrentio = addons.find((a) => a.sortOrder === 4)!;
    const aio = addons.find((a) => a.sortOrder === 5)!;
    expect(torrentio.url).toContain("premiumize=KEY123");
    expect(aio.url).toBe("https://aiostreams.example/u/cfg/manifest.json");
  });
});

test("onboardDebrid is idempotent (re-run does not duplicate)", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "b@test.dev");
    const args = { premiumizeKey: "K", aiostreamsUrl: "https://aio.example/m.json" } as const;
    await onboardDebrid(db, id, args);
    await onboardDebrid(db, id, args);
    expect((await getMember(db, id))!.addons).toHaveLength(2);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -w @kevbox-admin/core -- debrid`
Expected: FAIL — cannot find module `../src/debrid.js`.

- [ ] **Step 3: Write `packages/core/src/debrid.ts`**

```ts
import type { Db } from "./types.js";
import { buildTorrentioUrl, DEBRID_TORRENTIO_SORT, DEBRID_AIOSTREAMS_SORT } from "./defaults.js";

/** Add a member's two debrid sources from their OWN keys. Idempotent (on conflict do nothing). */
export async function onboardDebrid(
  db: Db,
  userId: string,
  opts: { premiumizeKey: string; aiostreamsUrl: string },
): Promise<void> {
  const aiostreams = opts.aiostreamsUrl.trim();
  if (!aiostreams) throw new Error("aiostreamsUrl is required");
  const torrentio = buildTorrentioUrl(opts.premiumizeKey);
  await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, true, $4), ($1, $3, true, $5)
     on conflict (user_id, url) do nothing`,
    [userId, torrentio, aiostreams, DEBRID_TORRENTIO_SORT, DEBRID_AIOSTREAMS_SORT],
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -w @kevbox-admin/core -- debrid`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add packages/core/src/debrid.ts packages/core/test/debrid.test.ts
git commit -m "feat(core): onboardDebrid (Torrentio + AIOStreams)"
```

---

## Task 9: `bulkAddAddon` + `bulkSwapUrl` (with confirm guard)

**Files:**
- Create: `packages/core/src/bulk.ts`, `packages/core/test/bulk.test.ts`

- [ ] **Step 1: Write the failing test** (`packages/core/test/bulk.test.ts`)

```ts
import { expect, test } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { bulkAddAddon, bulkSwapUrl, snapshotAllAddons } from "../src/bulk.js";
import { getMember } from "../src/members.js";

test("bulkAddAddon refuses without confirm", async () => {
  await withRollback(async (db) => {
    await expect(bulkAddAddon(db, { url: "https://x.example", sortOrder: 99 }, false)).rejects.toThrow(/confirm/i);
  });
});

test("bulkAddAddon adds the url to every member (idempotent)", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "a@test.dev");
    const b = await createTestMember(db, "b@test.dev");
    const n = await bulkAddAddon(db, { url: "https://x.example", sortOrder: 99 }, true);
    expect(n).toBe(2);
    expect((await getMember(db, a))!.addons.some((x) => x.url === "https://x.example")).toBe(true);
    expect((await getMember(db, b))!.addons.some((x) => x.url === "https://x.example")).toBe(true);
    // re-run: no new rows
    expect(await bulkAddAddon(db, { url: "https://x.example", sortOrder: 99 }, true)).toBe(0);
  });
});

test("bulkSwapUrl swaps everywhere and collapses members who had both", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "a@test.dev");
    const b = await createTestMember(db, "b@test.dev");
    await db.query("insert into public.member_addon (user_id, url, sort_order) values ($1,'https://old.example',0)", [a]);
    // b already has BOTH old and new -> swap must not violate unique(user_id,url)
    await db.query("insert into public.member_addon (user_id, url, sort_order) values ($1,'https://old.example',0),($1,'https://new.example',1)", [b]);
    await bulkSwapUrl(db, { fromUrl: "https://old.example", toUrl: "https://new.example" }, true);
    expect((await getMember(db, a))!.addons.map((x) => x.url)).toEqual(["https://new.example"]);
    const burls = (await getMember(db, b))!.addons.map((x) => x.url);
    expect(burls).toContain("https://new.example");
    expect(burls).not.toContain("https://old.example");
    expect(burls.filter((u) => u === "https://new.example")).toHaveLength(1);
  });
});

test("snapshotAllAddons captures every member's rows with email (the pre-bulk backstop)", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "a@test.dev");
    await db.query("insert into public.member_addon (user_id, url, sort_order) values ($1,'https://old.example',0)", [a]);
    const snap = await snapshotAllAddons(db);
    const mine = snap.filter((r) => r.email === "a@test.dev");
    expect(mine).toHaveLength(1);
    expect(mine[0]).toMatchObject({ url: "https://old.example", enabled: true, sortOrder: 0 });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -w @kevbox-admin/core -- bulk`
Expected: FAIL — cannot find module `../src/bulk.js`.

- [ ] **Step 3: Write `packages/core/src/bulk.ts`**

```ts
import type { Db } from "./types.js";

export interface AddonSnapshotRow {
  userId: string;
  email: string | null;
  url: string;
  enabled: boolean;
  sortOrder: number;
}

/**
 * Capture EVERY member_addon row (with member email) as a plain array — the pre-image to persist
 * BEFORE a destructive bulk op so a wrong swap/add is recoverable (spec §4.6 snapshot-before-bulk,
 * v1). Pure read; the caller owns where the JSON lands (CLI: a local file; web: the response).
 */
export async function snapshotAllAddons(db: Db): Promise<AddonSnapshotRow[]> {
  const { rows } = await db.query(
    `select m.user_id, u.email, m.url, m.enabled, m.sort_order
       from public.member_addon m join auth.users u on u.id = m.user_id
      order by u.email nulls last, m.sort_order, m.id`,
  );
  return rows.map((r: any) => ({
    userId: r.user_id,
    email: r.email,
    url: r.url,
    enabled: r.enabled,
    sortOrder: r.sort_order,
  }));
}

/** Add `url` to EVERY member. Returns rows inserted. Requires confirm=true (no client-side undo). */
export async function bulkAddAddon(
  db: Db,
  opts: { url: string; sortOrder: number },
  confirm: boolean,
): Promise<number> {
  if (confirm !== true) throw new Error("bulkAddAddon requires confirm=true");
  const { rowCount } = await db.query(
    `insert into public.member_addon (user_id, url, sort_order)
     select id, $1, $2 from auth.users
     on conflict (user_id, url) do nothing`,
    [opts.url.trim(), opts.sortOrder],
  );
  return rowCount ?? 0;
}

/** Swap `fromUrl` -> `toUrl` across ALL members, safely handling members who already have toUrl. */
export async function bulkSwapUrl(
  db: Db,
  opts: { fromUrl: string; toUrl: string },
  confirm: boolean,
): Promise<void> {
  if (confirm !== true) throw new Error("bulkSwapUrl requires confirm=true");
  const from = opts.fromUrl.trim();
  const to = opts.toUrl.trim();
  // 1. swap where the member does NOT already have the target (avoids unique violation)
  await db.query(
    `update public.member_addon m set url = $2, updated_at = now()
      where m.url = $1
        and not exists (select 1 from public.member_addon m2 where m2.user_id = m.user_id and m2.url = $2)`,
    [from, to],
  );
  // 2. drop now-redundant originals for members who already had the target
  await db.query("delete from public.member_addon where url = $1", [from]);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -w @kevbox-admin/core -- bulk`
Expected: PASS (all four tests, incl. snapshotAllAddons).

- [ ] **Step 5: Commit**

```bash
git add packages/core/src/bulk.ts packages/core/test/bulk.test.ts
git commit -m "feat(core): bulkAddAddon + bulkSwapUrl (confirm guard) + snapshotAllAddons (pre-bulk backstop)"
```

---

## Task 10: Barrel export + build check

**Files:**
- Create: `packages/core/src/index.ts`

- [ ] **Step 1: Write `packages/core/src/index.ts`**

```ts
export * from "./types.js";
export * from "./defaults.js";
export * from "./members.js";
export * from "./addons.js";
export * from "./reset.js";
export * from "./debrid.js";
export * from "./bulk.js";
```

- [ ] **Step 2: Run the full test suite**

Run: `npm test -w @kevbox-admin/core`
Expected: PASS — all test files green.

- [ ] **Step 3: Type-check / build the package**

Run: `npm run build -w @kevbox-admin/core`
Expected: `dist/` produced, no TS errors.

- [ ] **Step 4: Commit**

```bash
git add packages/core/src/index.ts
git commit -m "feat(core): barrel exports; core package complete"
```

---

## Self-Review

**Spec coverage:** Every `core` operation in spec §4.2 has a task — listMembers/getMember (T5), addAddon/updateAddon/setEnabled/reorder/deleteAddon (T6), resetToDefaults (T7), onboardDebrid (T8), bulkAddAddon/bulkSwapUrl (T9), buildTorrentioUrl (T4). Data-integrity §4.3 (resetToDefaults reuses `default_member_addons()`, idempotent inserts) implemented in T7/T8. Safety §4.6 (bulk confirm guard) in T9. Testing §7 (vitest + rollback) in T3. CLI/web/deploy are deliberately out of scope for Plan 1 (subsequent plans).

**Placeholder scan:** No TBD/TODO; every code step has complete code; test URLs in the test schema are real values chosen for the tests, not placeholders.

**Type consistency:** `Db`, `AddonRow`, `MemberSummary`, `MemberDetail`, `mapAddonRow` defined in T2 and used consistently in T5–T9. `addAddon` returns `AddonRow`; `updateAddon`/`setEnabled` return `AddonRow | null`; `getMember` returns `MemberDetail | null`; `reorder`/`deleteAddon` return `void`; bulk ops take `confirm: boolean`. `DEBRID_TORRENTIO_SORT`/`DEBRID_AIOSTREAMS_SORT` defined in T4, used in T8. All `import` paths use the `.js` extension required by NodeNext ESM.

**Note for executor:** Tasks 5–9 require the test DB running (`docker compose up -d test-db`, started in T3). Run `docker compose down` when finished.
