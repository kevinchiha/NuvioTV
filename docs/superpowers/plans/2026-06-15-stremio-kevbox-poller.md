# Stremio→KevBox C1 Auto-Poller + Credential Vault — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically import the existing Stremio history of the ~320 not-yet-logged-in KevBox members on their first login, with zero per-member operator action, via a cron poller over a pre-loaded Stremio credential vault.

**Architecture:** Two new thin orchestrators (`load-stremio-creds.ts`, `kevbox-poller.ts`) over the **already-proven** Plan-1 import core (`kevbox.ts` + `convert-kevbox.ts` + `watched-decode.ts`, 35 tests green — unchanged). All correctness-bearing decision logic (activity-aware 0-item rule, retry/dead-letter state machine, loader status decision) lives in a new **pure, unit-tested** `poller-core.ts`. The vault stores **plaintext** Stremio creds (decision 2026-06-15: accounts deleted after migration → vault is time-boxed; teardown is a hard deliverable). DB/network stay at thin I/O edges; the operator runs every prod-touching step.

**Tech Stack:** TypeScript, `pg` (node-postgres over the prod Supabase pooler), `axios` (Stremio API, already present), `xlsx`/SheetJS (new devDependency, loader only), `vitest` (existing test runner), cron on persovps.

**Repo:** `~/projects/trakt-stremio-import`, branch `feat/kevbox-poller` (off `master`).

**Spec:** `~/projects/NuvioTV/docs/superpowers/specs/2026-06-14-stremio-kevbox-direct-import-design.md` (§4 component table, §7.1 C1 path, §12 error handling, §16 deliverables, §17 steps 6–7, §18/§19 rev-5 vault decision).

**Boundary (CODE-ONLY):** The operator runs all prod-touching steps (apply SQL, run the loader against prod, install the cron, delete accounts + run teardown). This plan only produces code, tests, and docs, plus local git commits on the feature branch. **No pushing, no prod execution.**

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `sql/member_stremio_creds_setup.sql` | create | vault DDL — RLS-on, no anon/authenticated grants, plaintext password |
| `sql/member_stremio_creds_teardown.sql` | create | `drop table` — run after accounts deleted |
| `src/utils/env.ts` | create | shared `loadEnv()` / `readEnvVar()` / pure `parseEnvVar()` (extracted from `kevbox-import.ts`) |
| `src/utils/accounts.ts` | create | shared `safeName()` / `traktConnectedHint()` (extracted from `kevbox-import.ts`) |
| `src/utils/poller-core.ts` | create | **pure** decision logic: `isSuspectEmpty`, `nextFailureStatus`, `decideLoadStatus`, constants |
| `src/utils/stremio.ts` | modify | add `formatStremioError()`; fix the `[object Object]` throw at line 30 |
| `src/kevbox-import.ts` | modify | import the extracted `env`/`accounts` utils (DRY); behavior unchanged |
| `src/load-stremio-creds.ts` | create | read `Global Vision.xlsx` Sheet1 → resolve owner → upsert vault rows |
| `src/kevbox-poller.ts` | create | one pass per tick: lock → candidates → allowlist+import+verify → mark done/failed/dead |
| `scripts/load_stremio_creds.sh` | create | wrapper (sources `app.env`, `npx ts-node`) |
| `scripts/kevbox_poller.sh` | create | wrapper (sources `app.env`, `npx ts-node`) |
| `KEVBOX-POLLER.md` | create | ops doc: load creds, install cron, monitor, teardown |
| `test/stremio-error.test.ts` | create | `formatStremioError` regression (no `[object Object]`) |
| `test/env.test.ts` | create | `parseEnvVar` |
| `test/poller-core.test.ts` | create | the three pure decision functions |
| `test/load-creds.test.ts` | create | `parseSheetRow` mapping/skip |

**Reused verbatim from Plan 1 (do NOT modify):** `convert-kevbox.ts`, `watched-decode.ts`, and `kevbox.ts`'s `resolveOwner`, `allowlist`, `isAllowlisted`, `buildWatchedEpisodeIndex`, `pushWatchProgress`, `pushWatchedItems`, `pushLibrary`, `verifyCounts`, `createDbClient`.

---

## Task 0: Create the feature branch

**Files:** none (git only)

- [ ] **Step 1: Confirm clean tree and green baseline**

Run:
```bash
cd ~/projects/trakt-stremio-import && git status --short && npx vitest run 2>&1 | tail -3
```
Expected: no output from `git status` (clean); `Tests  35 passed (35)`.

- [ ] **Step 2: Branch off master**

Run:
```bash
cd ~/projects/trakt-stremio-import && git checkout -b feat/kevbox-poller
```
Expected: `Switched to a new branch 'feat/kevbox-poller'`.

---

## Task 1: Fix the `ERROR: [object Object]` Stremio-login nit (TDD)

**Files:**
- Create: `test/stremio-error.test.ts`
- Modify: `src/utils/stremio.ts` (add `formatStremioError`, use it at the `throw new Error(error)` on ~line 30)

**Why:** `updateAuthKeyWithCredentials` does `throw new Error(error)` where `error` is the Stremio API's `{ code, message }` **object**. `new Error(obj)` stringifies to `"[object Object]"`, so a failed login surfaces as `ERROR: [object Object]`. Format it instead.

- [ ] **Step 1: Write the failing test**

Create `test/stremio-error.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { formatStremioError } from "../src/utils/stremio";

describe("formatStremioError", () => {
  it("renders a Stremio {code,message} object as a readable string (never [object Object])", () => {
    const out = formatStremioError({ code: 3, message: "User not found" });
    expect(out).toBe("User not found (code 3)");
    expect(out).not.toContain("[object Object]");
  });

  it("passes a string through unchanged", () => {
    expect(formatStremioError("boom")).toBe("boom");
  });

  it("uses .message for an Error instance", () => {
    expect(formatStremioError(new Error("nope"))).toBe("nope");
  });

  it("JSON-stringifies an unknown object shape (not [object Object])", () => {
    const out = formatStremioError({ foo: "bar" });
    expect(out).toBe('{"foo":"bar"}');
    expect(out).not.toContain("[object Object]");
  });

  it("handles null/undefined without throwing", () => {
    expect(formatStremioError(undefined)).toBe("Stremio API error (no detail)");
    expect(formatStremioError(null)).toBe("Stremio API error (no detail)");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/stremio-error.test.ts`
Expected: FAIL — `formatStremioError` is not exported from `stremio.ts`.

- [ ] **Step 3: Add `formatStremioError` and use it**

In `src/utils/stremio.ts`, add this exported function **above** the `StremioAPIClient` class (after the imports / `StremioCredentials` interface):
```ts
/** Render a Stremio API error (string | {code,message} | Error | unknown) as a readable message.
 *  Stremio returns `error` as an object, so `new Error(error)` would print "[object Object]". */
export function formatStremioError(error: unknown): string {
  if (error == null) return "Stremio API error (no detail)";
  if (typeof error === "string") return error;
  if (error instanceof Error) return error.message;
  const e = error as { message?: unknown; code?: unknown };
  if (typeof e.message === "string") {
    return e.code != null ? `${e.message} (code ${e.code})` : e.message;
  }
  try {
    return JSON.stringify(error);
  } catch {
    return String(error);
  }
}
```

Then change the credentials-login throw inside `updateAuthKeyWithCredentials` from:
```ts
    if (!result || !result.authKey) {
      console.warn(
        "Updating AuthKey - Authenticatation Method: Credentials - FAILED",
      );
      throw new Error(error);
    }
```
to:
```ts
    if (!result || !result.authKey) {
      console.warn(
        "Updating AuthKey - Authenticatation Method: Credentials - FAILED",
      );
      throw new Error(formatStremioError(error));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/stremio-error.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/utils/stremio.ts test/stremio-error.test.ts
git commit -m "fix(stremio): stringify login error object (kills ERROR: [object Object])"
```

---

## Task 2: Extract shared `env.ts` (DRY — needed by poller + loader) (TDD)

**Files:**
- Create: `src/utils/env.ts`
- Create: `test/env.test.ts`
- Modify: `src/kevbox-import.ts` (remove its local `loadEnv`/`readEnvVar`, import from `./utils/env`)

**Why:** `kevbox-import.ts` defines `loadEnv`/`readEnvVar` privately. The poller and loader need the same env resolution. Extract once.

- [ ] **Step 1: Write the failing test**

Create `test/env.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { parseEnvVar } from "../src/utils/env";

describe("parseEnvVar", () => {
  const content = [
    "# a comment",
    'SUPABASE_DB_URL = "postgresql://u:p@h:5432/postgres"',
    "OTHER=plain",
  ].join("\n");

  it("reads a quoted value and strips the surrounding quotes", () => {
    expect(parseEnvVar(content, "SUPABASE_DB_URL")).toBe("postgresql://u:p@h:5432/postgres");
  });

  it("reads an unquoted value", () => {
    expect(parseEnvVar(content, "OTHER")).toBe("plain");
  });

  it("returns undefined for a missing key", () => {
    expect(parseEnvVar(content, "NOPE")).toBeUndefined();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/env.test.ts`
Expected: FAIL — cannot find module `../src/utils/env`.

- [ ] **Step 3: Create `src/utils/env.ts`**

```ts
import * as fs from "fs";
import * as os from "os";
import * as path from "path";

/** Pure: extract KEY from `.env`-style file content (strips surrounding quotes). */
export function parseEnvVar(content: string, key: string): string | undefined {
  for (const line of content.split("\n")) {
    const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*)\s*$/);
    if (m && m[1] === key) return m[2].replace(/^["']|["']$/g, "");
  }
  return undefined;
}

export function readEnvVar(file: string, key: string): string | undefined {
  if (!fs.existsSync(file)) return undefined;
  return parseEnvVar(fs.readFileSync(file, "utf8"), key);
}

/** Resolve SUPABASE_DB_URL from the process env or ~/.config/stremio-kevbox-migration/app.env. */
export function loadEnv(): { SUPABASE_DB_URL: string } {
  const p = path.join(os.homedir(), ".config", "stremio-kevbox-migration", "app.env");
  const url = process.env.SUPABASE_DB_URL ?? readEnvVar(p, "SUPABASE_DB_URL");
  if (!url) throw new Error(`SUPABASE_DB_URL not set (env or ${p})`);
  return { SUPABASE_DB_URL: url };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/env.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Update `kevbox-import.ts` to use the shared util**

In `src/kevbox-import.ts`, **delete** the local `loadEnv` and `readEnvVar` functions (the two functions spanning the `function loadEnv()` and `function readEnvVar(...)` definitions), and **remove** the now-unused `import * as os from "os";` only if `os` is no longer referenced (it is used solely by `loadEnv`, so remove it). Add to the existing imports near the top:
```ts
import { loadEnv } from "./utils/env";
```
The existing call `const env = loadEnv();` in `main()` is unchanged. Keep the `fs`/`path` imports (still used by `archive`, `traktConnectedHint`, `safeName`).

- [ ] **Step 6: Run the full suite to verify nothing regressed**

Run: `npx vitest run`
Expected: `Tests  43 passed` (35 baseline + 5 stremio-error + 3 env).

- [ ] **Step 7: Verify `kevbox-import.ts` still type-checks**

Run: `npx tsc --noEmit`
Expected: no errors (in particular, no "unused import" or "cannot find name os").

- [ ] **Step 8: Commit**

```bash
git add src/utils/env.ts test/env.test.ts src/kevbox-import.ts
git commit -m "refactor: extract shared env loader to utils/env (DRY for poller+loader)"
```

---

## Task 3: Extract shared `accounts.ts` (DRY — loader Trakt skip) (no new behavior)

**Files:**
- Create: `src/utils/accounts.ts`
- Modify: `src/kevbox-import.ts` (remove its local `safeName`/`traktConnectedHint`, import from `./utils/accounts`)

**Why:** The loader (Task 5) needs `traktConnectedHint` to skip Trakt-connected members. Extract the CLI's copy. **Path note:** moving into `src/utils/` changes the relative path to the repo-root `accounts/` dir from `../accounts` to `../../accounts` — the new file uses `__dirname, "..", ".."`.

- [ ] **Step 1: Create `src/utils/accounts.ts`**

```ts
import * as fs from "fs";
import * as path from "path";

/** Filesystem-safe form of a member ref (email or uid) for accounts/<ref>.json. */
export function safeName(ref: string): string {
  return ref.replace(/[^a-zA-Z0-9._@-]/g, "_");
}

/** Best-effort: a legacy Trakt-migration archive (accounts/<ref>.json with a trakt_accesstoken) means the
 *  member is/was Trakt-driven → Supabase restore is gated OFF client-side. Returns false if it can't tell.
 *  (accounts/ lives at the repo root; this file is src/utils/, hence "..","..".) */
export function traktConnectedHint(ref: string): boolean {
  try {
    const f = path.join(__dirname, "..", "..", "accounts", `${safeName(ref)}.json`);
    if (!fs.existsSync(f)) return false;
    return !!JSON.parse(fs.readFileSync(f, "utf8")).trakt_accesstoken;
  } catch {
    return false;
  }
}
```

- [ ] **Step 2: Update `kevbox-import.ts` to use the shared util**

In `src/kevbox-import.ts`, **delete** the local `safeName` function and the local `traktConnectedHint` function. Add to the imports near the top:
```ts
import { safeName, traktConnectedHint } from "./utils/accounts";
```
The existing call sites (`safeName(args.memberRef)` in `archive`, `traktConnectedHint(args.memberRef)` in `main`) are unchanged.

- [ ] **Step 3: Verify the full suite + types still pass**

Run: `npx vitest run && npx tsc --noEmit`
Expected: `Tests  43 passed`; no type errors.

> Note: the CLI's `traktConnectedHint` path was `../accounts` (called from `src/`); the shared one resolves from `src/utils/` via `../../accounts`. Both point at the same repo-root `accounts/`. No behavior change.

- [ ] **Step 4: Commit**

```bash
git add src/utils/accounts.ts src/kevbox-import.ts
git commit -m "refactor: extract safeName/traktConnectedHint to utils/accounts (DRY for loader)"
```

---

## Task 4: Pure poller decision logic — `poller-core.ts` (TDD)

**Files:**
- Create: `src/utils/poller-core.ts`
- Create: `test/poller-core.test.ts`

**Why:** All correctness-bearing decisions are isolated here so they are unit-tested without a DB: the activity-aware 0-item rule (§12), the retry→dead-letter state machine, and the loader's status decision.

- [ ] **Step 1: Write the failing test**

Create `test/poller-core.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import {
  isSuspectEmpty,
  nextFailureStatus,
  decideLoadStatus,
  MAX_ATTEMPTS,
} from "../src/utils/poller-core";

describe("isSuspectEmpty (activity-aware 0-item rule, spec §12)", () => {
  it("flags 0 converted items for a known-active member as suspect", () => {
    expect(isSuspectEmpty({ watchProgress: 0, watchedItems: 0, library: 0 }, 7200)).toBe(true);
  });
  it("does NOT flag 0 items for a clean member (no watch time)", () => {
    expect(isSuspectEmpty({ watchProgress: 0, watchedItems: 0, library: 0 }, 0)).toBe(false);
  });
  it("does NOT flag a member who converted at least one item", () => {
    expect(isSuspectEmpty({ watchProgress: 0, watchedItems: 1, library: 0 }, 7200)).toBe(false);
  });
});

describe("nextFailureStatus (retry → dead-letter)", () => {
  it("stays 'failed' while under MAX_ATTEMPTS", () => {
    expect(nextFailureStatus(1, 3)).toBe("failed");
    expect(nextFailureStatus(2, 3)).toBe("failed");
  });
  it("becomes 'dead' at MAX_ATTEMPTS", () => {
    expect(nextFailureStatus(3, 3)).toBe("dead");
    expect(nextFailureStatus(4, 3)).toBe("dead");
  });
  it("exposes MAX_ATTEMPTS = 3", () => {
    expect(MAX_ATTEMPTS).toBe(3);
  });
});

describe("decideLoadStatus (loader initial status)", () => {
  it("already-allowlisted member → 'done' (handled in the manual phase)", () => {
    expect(decideLoadStatus({ isKevin: false, isAllowlisted: true, isTraktConnected: false, isExplicitlySkipped: false })).toBe("done");
  });
  it("allowlisted takes precedence even if also Trakt-connected (e.g. alecco)", () => {
    expect(decideLoadStatus({ isKevin: false, isAllowlisted: true, isTraktConnected: true, isExplicitlySkipped: false })).toBe("done");
  });
  it("Kevin (operator, deltaInitialized) → 'skip'", () => {
    expect(decideLoadStatus({ isKevin: true, isAllowlisted: false, isTraktConnected: false, isExplicitlySkipped: false })).toBe("skip");
  });
  it("Trakt-connected, not yet handled → 'skip'", () => {
    expect(decideLoadStatus({ isKevin: false, isAllowlisted: false, isTraktConnected: true, isExplicitlySkipped: false })).toBe("skip");
  });
  it("explicitly skipped (operator --skip list) → 'skip'", () => {
    expect(decideLoadStatus({ isKevin: false, isAllowlisted: false, isTraktConnected: false, isExplicitlySkipped: true })).toBe("skip");
  });
  it("a fresh never-handled member → 'pending'", () => {
    expect(decideLoadStatus({ isKevin: false, isAllowlisted: false, isTraktConnected: false, isExplicitlySkipped: false })).toBe("pending");
  });
  it("Kevin in prod is ALSO allowlisted → 'done' (allowlisted precedence; both 'done' and 'skip' exclude him)", () => {
    // Kevin's real prod state is allowlisted=true, so decideLoadStatus returns 'done' before the isKevin check.
    // Behaviorally safe — both terminal statuses exclude him from the pending/failed candidate filter, and the
    // poller has a separate KEVIN_UID guard. This pins the precedence so a future reorder can't silently regress it.
    expect(decideLoadStatus({ isKevin: true, isAllowlisted: true, isTraktConnected: false, isExplicitlySkipped: false })).toBe("done");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/poller-core.test.ts`
Expected: FAIL — cannot find module `../src/utils/poller-core`.

- [ ] **Step 3: Create `src/utils/poller-core.ts`**

```ts
// Pure decision logic for the C1 poller + creds loader. No I/O — unit-tested in isolation.

export type CredStatus = "pending" | "failed" | "dead" | "done" | "skip";

export interface ImportCounts {
  watchProgress: number;
  watchedItems: number;
  library: number;
}

/** Dead-letter after this many failed attempts (spec §12 "bounded retries; dead-letter after N"). */
export const MAX_ATTEMPTS = 3;

/** Max members processed per cron tick (bounds wall-clock per run). */
export const TICK_LIMIT = 10;

/**
 * Activity-aware 0-item rule (spec §12). A KNOWN-ACTIVE member (watch_seconds > 0) who converts to ZERO
 * importable items is almost certainly a failed read (stale token / empty datastoreGet), NOT a legitimately
 * empty account → must be treated as a failure, never a false "done". A CLEAN member (0 watch_seconds) with
 * 0 items is a genuine empty restore and is fine.
 */
export function isSuspectEmpty(converted: ImportCounts, watchSeconds: number): boolean {
  const total = converted.watchProgress + converted.watchedItems + converted.library;
  return total === 0 && watchSeconds > 0;
}

/**
 * Retry / dead-letter state machine. `attempts` is the NEW attempt count AFTER incrementing for the
 * just-failed run. At or beyond maxAttempts the row is dead-lettered (stop retrying).
 */
export function nextFailureStatus(attempts: number, maxAttempts: number): "failed" | "dead" {
  return attempts >= maxAttempts ? "dead" : "failed";
}

/**
 * Initial vault status the loader assigns to a member. Already-allowlisted members were handled in the
 * manual backfill phase → 'done' (excluded from the poller). Kevin (operator, deltaInitialized), Trakt-connected,
 * and operator-skipped members → 'skip'. Everyone else → 'pending' (the C1 auto cohort).
 */
export function decideLoadStatus(o: {
  isKevin: boolean;
  isAllowlisted: boolean;
  isTraktConnected: boolean;
  isExplicitlySkipped: boolean;
}): CredStatus {
  if (o.isAllowlisted) return "done";
  if (o.isKevin || o.isExplicitlySkipped || o.isTraktConnected) return "skip";
  return "pending";
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/poller-core.test.ts`
Expected: PASS (13 tests).

- [ ] **Step 5: Commit**

```bash
git add src/utils/poller-core.ts test/poller-core.test.ts
git commit -m "feat(poller): pure decision core — activity-aware 0-item, retry/dead-letter, load status"
```

---

## Task 5: Vault SQL — `member_stremio_creds` setup + teardown

**Files:**
- Create: `sql/member_stremio_creds_setup.sql`
- Create: `sql/member_stremio_creds_teardown.sql`

**Why:** The vault table the loader populates and the poller reads. Plaintext password by design (rev 5 — accounts deleted after migration); RLS-on + no grants as transient-window defense-in-depth; teardown is mandatory.

- [ ] **Step 1: Create `sql/member_stremio_creds_setup.sql`**

```sql
-- Stremio credential vault for the C1 auto-poller (Plan 2).
-- PLAINTEXT password BY DESIGN (rev 5, 2026-06-15): the operator deletes every Stremio account permanently once
-- the migration tail is drained, so this is a strictly TIME-BOXED operational table. Column-level encryption buys
-- nothing against an operator who holds the cleartext creds and destroys the source accounts. RLS-on + zero
-- anon/authenticated grants are defense-in-depth for the transient window. A teardown
-- (member_stremio_creds_teardown.sql) is a MANDATORY follow-up once the accounts are deleted.
-- Spec: NuvioTV/docs/superpowers/specs/2026-06-14-stremio-kevbox-direct-import-design.md §7.1/§16/§18/§19 (rev 5).

create table if not exists public.member_stremio_creds (
  user_id          uuid primary key,
  stremio_email    text not null,
  stremio_password text not null,                 -- plaintext (see header)
  status           text not null default 'pending'
                     check (status in ('pending','failed','dead','done','skip')),
  attempts         int  not null default 0,
  last_run         timestamptz,
  last_error       text
);

alter table public.member_stremio_creds enable row level security;
-- With RLS enabled and NO policy created, anon/authenticated receive ZERO rows. The admin pg connection
-- (table owner / postgres) bypasses RLS and is the only reader/writer (loader + poller).
-- `public` is included to match the proven admin-only-table precedent (sync_canary_members) — `public` is the
-- default grantee in Postgres and any future/inherited role would otherwise pick up a grant. Load-bearing here
-- because the table holds PLAINTEXT passwords.
revoke all on public.member_stremio_creds from public, anon, authenticated;
```

- [ ] **Step 2: Create `sql/member_stremio_creds_teardown.sql`**

```sql
-- Run ONCE after the migration tail is drained AND every Stremio account has been deleted.
-- Destroys the plaintext credential vault. Irreversible.
-- Spec rev 5 §19.
drop table if exists public.member_stremio_creds;
```

- [ ] **Step 3: Sanity-check the SQL parses (offline, no DB)**

Run:
```bash
cd ~/projects/trakt-stremio-import && for f in sql/member_stremio_creds_*.sql; do echo "== $f =="; grep -c ';' "$f"; done
```
Expected: `setup` shows `3` (table + alter + revoke statements end in `;`); `teardown` shows `1`. (This is a presence check, not a DB apply — the operator applies it against prod.)

- [ ] **Step 4: Commit**

```bash
git add sql/member_stremio_creds_setup.sql sql/member_stremio_creds_teardown.sql
git commit -m "feat(vault): member_stremio_creds setup + teardown SQL (plaintext, RLS-on, time-boxed)"
```

---

## Task 6: Creds loader — `load-stremio-creds.ts` + wrapper (TDD on the pure parse)

**Files:**
- Modify: `package.json` (add `xlsx` devDependency)
- Create: `src/load-stremio-creds.ts`
- Create: `scripts/load_stremio_creds.sh`
- Create/extend: `test/load-creds.test.ts`

**Why:** Reads `~/Downloads/Global Vision.xlsx` Sheet1 (cols `Stremio_Email` == KevBox email, `Stremio_Pasword` — note the misspelling), resolves each to a KevBox owner uuid, and upserts vault rows with the status from `decideLoadStatus`. Operator-run against prod.

- [ ] **Step 1: Add the `xlsx` devDependency**

Run:
```bash
cd ~/projects/trakt-stremio-import && npm install -D xlsx
```
Expected: `xlsx` added under `devDependencies` in `package.json`; install succeeds.

- [ ] **Step 2: Write the failing test for the pure row parser**

Create `test/load-creds.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { parseSheetRow, parseSkipList } from "../src/load-stremio-creds";

describe("parseSheetRow (Global Vision Sheet1 → cred | skip)", () => {
  it("maps Stremio_Email + Stremio_Pasword (sic) and lowercases/trims the email", () => {
    const out = parseSheetRow({ Stremio_Email: "  Foo@Gmail.com ", Stremio_Pasword: "secret " });
    expect(out).toEqual({ email: "foo@gmail.com", password: "secret" });
  });
  it("returns null when the email is missing/blank", () => {
    expect(parseSheetRow({ Stremio_Email: "", Stremio_Pasword: "secret" })).toBeNull();
    expect(parseSheetRow({ Stremio_Pasword: "secret" })).toBeNull();
  });
  it("returns null when the password is missing/blank", () => {
    expect(parseSheetRow({ Stremio_Email: "foo@gmail.com", Stremio_Pasword: "  " })).toBeNull();
    expect(parseSheetRow({ Stremio_Email: "foo@gmail.com" })).toBeNull();
  });
  it("does NOT trim inside the password (only leading/trailing)", () => {
    expect(parseSheetRow({ Stremio_Email: "a@b.com", Stremio_Pasword: " p a s s " })?.password).toBe("p a s s");
  });
});

describe("parseSkipList", () => {
  it("splits a comma-separated email list, lowercased and trimmed", () => {
    expect(parseSkipList("A@x.com, b@y.com ,")).toEqual(new Set(["a@x.com", "b@y.com"]));
  });
  it("returns an empty set for undefined", () => {
    expect(parseSkipList(undefined)).toEqual(new Set());
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `npx vitest run test/load-creds.test.ts`
Expected: FAIL — cannot find module `../src/load-stremio-creds`.

- [ ] **Step 4: Create `src/load-stremio-creds.ts`**

```ts
import * as XLSX from "xlsx";
import { loadEnv } from "./utils/env";
import { createDbClient, isAllowlisted } from "./utils/kevbox";
import { traktConnectedHint } from "./utils/accounts";
import { decideLoadStatus } from "./utils/poller-core";

// Operator (cloud-restore canary, deltaInitialized) — never auto-import.
const KEVIN_UID = "8d87687a-945a-4529-833e-d904e6eddf8f";

export interface SheetCred {
  email: string;
  password: string;
}

/** Pure: map a raw Sheet1 row to a cred, or null to skip (missing email/password). NB: the sheet column is
 *  misspelled "Stremio_Pasword". */
export function parseSheetRow(row: Record<string, unknown>): SheetCred | null {
  const email = String(row["Stremio_Email"] ?? "").trim().toLowerCase();
  const password = String(row["Stremio_Pasword"] ?? "").trim();
  if (!email || !password) return null;
  return { email, password };
}

/** Pure: operator-supplied comma-separated skip list → a lowercased Set. */
export function parseSkipList(raw: string | undefined): Set<string> {
  const set = new Set<string>();
  if (!raw) return set;
  for (const e of raw.split(",")) {
    const t = e.trim().toLowerCase();
    if (t) set.add(t);
  }
  return set;
}

export function readSheet(filePath: string): SheetCred[] {
  const wb = XLSX.readFile(filePath);
  const ws = wb.Sheets["Sheet1"];
  if (!ws) throw new Error(`Sheet1 not found in ${filePath} (sheets: ${wb.SheetNames.join(", ")})`);
  const rows = XLSX.utils.sheet_to_json<Record<string, unknown>>(ws, { defval: "" });
  return rows.map(parseSheetRow).filter((c): c is SheetCred => c !== null);
}

// isAllowlisted is imported from ./utils/kevbox (reused verbatim — no local copy, DRY).

async function main() {
  const xlsxPath = process.argv[2];
  if (!xlsxPath) {
    throw new Error('Usage: load-stremio-creds <path-to-xlsx> [--skip "email1,email2"]');
  }
  const skipIdx = process.argv.indexOf("--skip");
  const skip = parseSkipList(skipIdx >= 0 ? process.argv[skipIdx + 1] : undefined);

  const env = loadEnv();
  const db = createDbClient(env.SUPABASE_DB_URL);
  await db.connect();
  try {
    const creds = readSheet(xlsxPath);
    let pending = 0, done = 0, skipped = 0, noMember = 0;
    for (const c of creds) {
      const res = await db.query<{ id: string }>(
        "select id from auth.users where lower(email) = $1",
        [c.email],
      );
      if (res.rows.length === 0) {
        noMember++;
        console.warn(`no KevBox member for ${c.email} — skip`);
        continue;
      }
      const owner = res.rows[0].id;
      const status = decideLoadStatus({
        isKevin: owner === KEVIN_UID,
        isAllowlisted: await isAllowlisted(db, owner),
        isTraktConnected: traktConnectedHint(c.email),
        isExplicitlySkipped: skip.has(c.email),
      });
      if (status === "pending") pending++;
      else if (status === "done") done++;
      else skipped++;
      // status is set ONLY on first insert; on re-run we refresh the creds but never clobber an
      // in-flight/terminal status (a 'done'/'failed'/'dead'/'skip' row keeps its status).
      await db.query(
        `insert into public.member_stremio_creds (user_id, stremio_email, stremio_password, status)
         values ($1, $2, $3, $4)
         on conflict (user_id) do update
           set stremio_email = excluded.stremio_email,
               stremio_password = excluded.stremio_password`,
        [owner, c.email, c.password, status],
      );
    }
    console.log(`loaded ${creds.length} sheet cred(s): pending=${pending} done=${done} skip=${skipped} no-member=${noMember}`);
  } finally {
    await db.end();
  }
}

if (require.main === module) {
  main().catch((e) => {
    console.error("ERROR:", (e as Error).message);
    process.exit(1);
  });
}
```

> **Note:** `main()` is guarded by `require.main === module` so importing this file in the test does NOT execute it.

- [ ] **Step 5: Run test to verify it passes**

Run: `npx vitest run test/load-creds.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 6: Create the wrapper `scripts/load_stremio_creds.sh`**

```bash
#!/usr/bin/env bash
# Loader for the Stremio credential vault. Reads SUPABASE_DB_URL from app.env.
# Usage: load_stremio_creds.sh <path-to-xlsx> [--skip "email1,email2"]
set -euo pipefail
ENV_FILE="${HOME}/.config/stremio-kevbox-migration/app.env"
[ -f "$ENV_FILE" ] && set -a && source "$ENV_FILE" && set +a
TOOL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$TOOL_DIR"
exec npx ts-node src/load-stremio-creds.ts "$@"
```

- [ ] **Step 7: Make the wrapper executable + type-check the new file**

Run:
```bash
cd ~/projects/trakt-stremio-import && chmod +x scripts/load_stremio_creds.sh && npx tsc --noEmit
```
Expected: no type errors.

- [ ] **Step 8: Commit**

```bash
git add package.json package-lock.json src/load-stremio-creds.ts scripts/load_stremio_creds.sh test/load-creds.test.ts
git commit -m "feat(vault): xlsx creds loader → member_stremio_creds (skip Kevin/Trakt/allowlisted)"
```

---

## Task 7: The poller — `kevbox-poller.ts` + wrapper

**Files:**
- Create: `src/kevbox-poller.ts`
- Create: `scripts/kevbox_poller.sh`

**Why:** One pass per cron tick: take an advisory lock (overlap-safe), select candidates (live `member_device` join = inherent cohort-drift sweep), and for each: allowlist + run the import core + verify + mark `done`/`failed`/`dead`. Reuses Plan-1's `kevbox.ts` functions and the pure `poller-core.ts` decisions. No foreground `sleep` — cron is the clock, one pass per invocation.

**Design notes baked into the code below:**
- **Allowlist AFTER a successful push+verify** (not before). The C1 cohort is clean (no local history), and verify reads base tables (not owner-gated), so deferring the allowlist avoids ever leaving a member allowlisted-but-empty (which would restore-empty).
- **No merge-race exposure for the C1 cohort.** These ~320 members have *not installed the app yet* — when they do, they get the current build (0.8.5+, which already has Option B / client union), so their first pull unions rather than replaces. The only devices that may run a pre-Option-B build are the ~17 already-imported members, and those are excluded from C1 (the loader marks allowlisted members `done`). So the login→watch→poll race does not apply here.
- **Per-member try/catch** so one bad member never aborts the tick.
- **`status in ('pending','failed')`** candidate filter (with a 30-min backoff on retries) so failed rows retry until dead-lettered.

- [ ] **Step 1: Create `src/kevbox-poller.ts`**

```ts
import { Client } from "pg";
import { loadEnv } from "./utils/env";
import * as kb from "./utils/kevbox";
import { StremioAPIClient } from "./utils/stremio";
import { convertStremioToKevbox } from "./utils/convert-kevbox";
import { VerifyCounts } from "./utils/kevbox-types";
import { isSuspectEmpty, nextFailureStatus, MAX_ATTEMPTS, TICK_LIMIT } from "./utils/poller-core";

// Operator (cloud-restore canary, deltaInitialized) — never auto-import (defense-in-depth; loader also marks 'skip').
const KEVIN_UID = "8d87687a-945a-4529-833e-d904e6eddf8f";
// Arbitrary fixed key so overlapping cron ticks don't double-process.
const POLLER_LOCK_KEY = 728492;
// Per-member wall-clock cap. buildWatchedEpisodeIndex fetches Cinemeta SERIALLY (one blocking call per watched
// series) and axios has no timeout in the reused code, so a heavy watcher / slow Cinemeta could otherwise hang
// the whole tick AND hold the advisory lock. A timeout fails that member (→ retry) instead of stalling everyone.
const PER_MEMBER_TIMEOUT_MS = 120_000;

/** Reject if `p` doesn't settle within `ms`. Clears the timer either way so the process can still exit. */
function withTimeout<T>(p: Promise<T>, ms: number, label: string): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(`timed out after ${ms}ms (${label})`)), ms);
  });
  return Promise.race([p, timeout]).finally(() => clearTimeout(timer));
}

interface Candidate {
  user_id: string;
  stremio_email: string;
  stremio_password: string;
  attempts: number;
  watch_seconds: number;
}

async function fetchCandidates(db: Client): Promise<Candidate[]> {
  const res = await db.query<Candidate>(
    `select c.user_id, c.stremio_email, c.stremio_password, c.attempts,
            coalesce((select sum(a.watch_seconds) from public.member_activity_daily a
                      where a.user_id = c.user_id), 0)::bigint as watch_seconds
       from public.member_stremio_creds c
      where c.status in ('pending','failed')
        and c.attempts < $1
        and c.user_id <> $2::uuid
        and exists (select 1 from public.member_device d where d.user_id = c.user_id)
        -- retry backoff: a fresh 'pending' row (last_run null) is eligible immediately; a 'failed' row waits
        -- 30 min so a transient Stremio/Cinemeta/network outage can't burn all attempts in ~15 min (every-5-min cron).
        and (c.last_run is null or c.last_run < now() - interval '30 minutes')
      order by c.attempts asc, c.user_id
      limit $3`,
    [MAX_ATTEMPTS, KEVIN_UID, TICK_LIMIT],
  );
  // pg returns bigint as string; coerce watch_seconds to number for the pure rule.
  return res.rows.map((r) => ({ ...r, watch_seconds: Number(r.watch_seconds) }));
}

/** Run the full import core for one member. Throws on any failure (caught by the per-member handler). */
async function importOne(db: Client, c: Candidate): Promise<VerifyCounts> {
  const authKey = await StremioAPIClient.updateAuthKeyWithCredentials({
    email: c.stremio_email,
    password: c.stremio_password,
  });
  // NB: do NOT blanket-throw on an empty library (spec §12 — the manual CLI's blanket-throw must NOT be inherited
  // by the poller). getLibrary already re-logs-in with the vaulted email+pass on a stale token, so an empty result
  // here means a genuinely empty Stremio account. For a CLEAN member (watch_seconds==0) that's a legitimate empty
  // restore → mark 'done'. Only the activity-aware rule below fails a *known-active* 0-item read.
  const { result: library } = await StremioAPIClient.getLibrary(authKey);
  const { index, driftIds } = await kb.buildWatchedEpisodeIndex(library);
  const payloads = convertStremioToKevbox(library, index, {
    includeLibrary: true, // C1 cohort is clean — plain restore, library always included
    bitfieldDriftIds: driftIds,
  });

  // Activity-aware 0-item rule (§12) BEFORE writing: a KNOWN-ACTIVE member (telemetry watch_seconds>0) converting
  // to nothing is almost certainly a failed read → fail (retry). A clean member converting to 0 is a genuine empty
  // restore and proceeds to a 0-row push + 'done'. (watch_seconds is daily-aggregated telemetry, so it only flags
  // members active on a prior day — a best-effort heuristic, not a safety guarantee.)
  const counts = {
    watchProgress: payloads.watchProgress.length,
    watchedItems: payloads.watchedItems.length,
    library: payloads.library.length,
  };
  if (isSuspectEmpty(counts, c.watch_seconds)) {
    throw new Error(
      `0 items converted for a known-active member (watch_seconds=${c.watch_seconds}) — likely stale token/empty read`,
    );
  }

  await kb.pushWatchProgress(db, c.user_id, payloads.watchProgress);
  await kb.pushWatchedItems(db, c.user_id, payloads.watchedItems);
  await kb.pushLibrary(db, c.user_id, payloads.library);

  // Allowlist AFTER a verified write (see Design notes) — enables device restore.
  await kb.allowlist(db, c.user_id);
  return kb.verifyCounts(db, c.user_id);
}

async function markDone(db: Client, userId: string): Promise<void> {
  // Clear the plaintext password on success — the poller never needs it again after 'done'. This shrinks the
  // plaintext blast radius from "all ~320 for the ~10-month tail" to "only not-yet-imported members". A later
  // top-up (member watched more in Stremio) just re-runs the loader, which refreshes the password via on-conflict.
  // (stremio_password is NOT NULL → set to '' rather than NULL; email is kept for monitoring/audit.)
  await db.query(
    "update public.member_stremio_creds set status='done', last_run=now(), last_error=null, stremio_password='' where user_id=$1",
    [userId],
  );
}

async function markFailure(db: Client, c: Candidate, message: string): Promise<"failed" | "dead"> {
  const attempts = c.attempts + 1;
  const status = nextFailureStatus(attempts, MAX_ATTEMPTS);
  await db.query(
    "update public.member_stremio_creds set status=$2, attempts=$3, last_run=now(), last_error=$4 where user_id=$1",
    [c.user_id, status, attempts, message.slice(0, 2000)],
  );
  return status;
}

async function main() {
  const env = loadEnv();
  const db = kb.createDbClient(env.SUPABASE_DB_URL);
  await db.connect();
  try {
    const lock = await db.query<{ locked: boolean }>("select pg_try_advisory_lock($1) as locked", [POLLER_LOCK_KEY]);
    if (!lock.rows[0].locked) {
      console.log("poller: another tick holds the advisory lock — exiting");
      return;
    }
    try {
      const candidates = await fetchCandidates(db);
      console.log(`poller tick: ${candidates.length} candidate(s)`);
      let done = 0, failed = 0, dead = 0;
      for (const c of candidates) {
        try {
          const v = await withTimeout(importOne(db, c), PER_MEMBER_TIMEOUT_MS, c.stremio_email);
          await markDone(db, c.user_id);
          done++;
          console.log(`DONE ${c.stremio_email} (${c.user_id}) wp=${v.watchProgress} wi=${v.watchedItems} lib=${v.library}`);
        } catch (e) {
          const message = (e as Error).message;
          const status = await markFailure(db, c, message);
          if (status === "dead") {
            dead++;
            console.error(`DEAD ${c.stremio_email} (${c.user_id}) after ${c.attempts + 1} attempts: ${message}`);
          } else {
            failed++;
            console.error(`FAILED ${c.stremio_email} (${c.user_id}) attempt ${c.attempts + 1}: ${message}`);
          }
        }
      }
      console.log(`poller summary: done=${done} failed=${failed} dead=${dead}`);
      // Both FAILED and DEAD are written to stderr (console.error) above, so with the cron line redirecting only
      // stdout to the logfile (NOT 2>&1 — see KEVBOX-POLLER.md §3) cron mails the operator on EVERY failure, not
      // just terminal dead-letters. The non-zero exit code is a belt-and-suspenders signal for a healthcheck wrapper.
      if (dead > 0) process.exitCode = 2;
    } finally {
      await db.query("select pg_advisory_unlock($1)", [POLLER_LOCK_KEY]);
    }
  } finally {
    await db.end();
  }
}

main().catch((e) => {
  console.error("ERROR:", (e as Error).message);
  process.exit(1);
});
```

- [ ] **Step 2: Type-check the poller**

Run: `cd ~/projects/trakt-stremio-import && npx tsc --noEmit`
Expected: no errors. (In particular, confirm `convertStremioToKevbox`, `buildWatchedEpisodeIndex`, `verifyCounts`, and `VerifyCounts` are used with the same signatures as `kevbox-import.ts`.)

- [ ] **Step 3: Create the wrapper `scripts/kevbox_poller.sh`**

```bash
#!/usr/bin/env bash
# C1 auto-poller: one pass per invocation. Install on persovps via cron (see KEVBOX-POLLER.md).
# Reads SUPABASE_DB_URL from app.env.
set -euo pipefail
ENV_FILE="${HOME}/.config/stremio-kevbox-migration/app.env"
[ -f "$ENV_FILE" ] && set -a && source "$ENV_FILE" && set +a
TOOL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$TOOL_DIR"
exec npx ts-node src/kevbox-poller.ts
```

- [ ] **Step 4: Make the wrapper executable**

Run: `cd ~/projects/trakt-stremio-import && chmod +x scripts/kevbox_poller.sh`
Expected: success (no output).

- [ ] **Step 5: Run the full suite (no regressions; poller has no DB unit test by design)**

Run: `npx vitest run`
Expected: `Tests  62 passed` (35 baseline + 5 stremio-error + 3 env + 13 poller-core + 6 load-creds). The poller has no DB unit test by design; its decisions are covered by poller-core. All green.

- [ ] **Step 6: Commit**

```bash
git add src/kevbox-poller.ts scripts/kevbox_poller.sh
git commit -m "feat(poller): C1 auto-poller — lock, candidate scan, import core, retry/dead-letter"
```

---

## Task 8: Ops doc — `KEVBOX-POLLER.md`

**Files:**
- Create: `KEVBOX-POLLER.md`
- Modify: `KEVBOX-IMPORT.md` (add a one-line pointer to the poller doc)

**Why:** The operator needs exact, ordered commands for the prod-touching steps this plan deliberately does NOT run: apply the vault SQL, load creds, deploy + schedule the poller, monitor, and tear down.

- [ ] **Step 1: Create `KEVBOX-POLLER.md`**

````markdown
# KevBox C1 Auto-Poller + Stremio Credential Vault (Plan 2)

Automates the Stremio→KevBox import for the ~320 not-yet-logged-in members. On first KevBox login a
`public.member_device` row appears; a cron poller on persovps then imports that member's vaulted Stremio
history with no per-member action.

Design spec: `~/projects/NuvioTV/docs/superpowers/specs/2026-06-14-stremio-kevbox-direct-import-design.md`
(§7.1, §12, §16, §17 steps 6–7, §18/§19 rev 5). Plan: `.../plans/2026-06-15-stremio-kevbox-poller.md`.

> **Precondition (satisfied):** Option B (client union-on-first-pull) shipped in **0.8.5-beta (versionCode 1033)**
> and is the current build on tv.kevbox.dev. The ~320 C1 members have not installed the app yet, so they fetch
> 0.8.5+ on first install — no merge-race exposure. The only pre-Option-B devices are the ~17 already-imported
> members, who are excluded from C1 (the loader marks allowlisted members `done`).

## 1. Apply the vault schema (prod)
```bash
psql "$SUPABASE_DB_URL" -f ~/projects/trakt-stremio-import/sql/member_stremio_creds_setup.sql
```
(`SUPABASE_DB_URL` = the value in `~/projects/NuvioTV/local.properties`, also in `app.env`.)

## 2. Load the credential vault (prod)
Source: `~/Downloads/Global Vision.xlsx` → **Sheet1**, columns `Stremio_Email` (= the KevBox email) and
`Stremio_Pasword` (note the misspelling). Members with no Stremio creds are skipped automatically.
```bash
cd ~/projects/trakt-stremio-import
scripts/load_stremio_creds.sh "$HOME/Downloads/Global Vision.xlsx" \
  --skip "karimassad723@gmail.com"        # optional: members to NOT auto-import (e.g. still Trakt-connected)
```
The loader sets each row's status: **already-allowlisted** members (the manual-17 + abdallah) → `done`;
**Kevin / Trakt-connected / --skip** → `skip`; everyone else → `pending`. Re-running refreshes creds but never
clobbers a `done`/`failed`/`dead`/`skip` row.

> **Top-ups & cleared passwords:** the poller **nulls `stremio_password`** when a member is marked `done` (so
> plaintext creds don't linger in prod — see §5). To re-import a `done` member later (they watched more in
> Stremio), re-run the loader (it restores the password via on-conflict) **and** re-queue them:
> `update public.member_stremio_creds set status='pending', attempts=0 where stremio_email=$1;`

Verify the load:
```sql
select status, count(*) from public.member_stremio_creds group by status order by status;
```

## 3. Deploy the poller to persovps
Check out the repo on persovps, install deps, create `app.env`, then schedule the wrapper. One pass per
invocation; cron is the clock.
```bash
# on persovps, in the repo checkout:
npm ci
printf 'SUPABASE_DB_URL=%s\n' '<the prod pooler URL>' > ~/.config/stremio-kevbox-migration/app.env
# dry-check: one manual tick (safe — idempotent; writes only for first-login members with pending creds)
scripts/kevbox_poller.sh
```
Add to crontab (every 5 minutes; the few-minute lag is absorbed by KevBox re-pulling on app start):
```cron
MAILTO=you@example.com
*/5 * * * * /home/<user>/trakt-stremio-import/scripts/kevbox_poller.sh >> /var/log/kevbox-poller.log
```
**Note the redirect is stdout-only (no `2>&1`).** The poller writes normal progress to stdout (→ logfile) and
every `FAILED`/`DEAD` line to **stderr**, which cron leaves unredirected → cron mails it to `MAILTO` on any
failure, not just terminal dead-letters. (With `2>&1` everything goes to the file and cron would never mail —
that was the bug.) The non-zero exit (code 2) on dead-letters is an extra signal for a healthcheck wrapper.

## 4. Monitor
```sql
-- progress
select status, count(*) from public.member_stremio_creds group by status order by status;
-- anything failing / dead-lettered, with the last error
select stremio_email, status, attempts, last_run, last_error
from public.member_stremio_creds where status in ('failed','dead') order by last_run desc;
-- members who have logged in (member_device row) but still aren't done (cohort-drift / lag check)
select c.stremio_email, c.status, c.attempts
from public.member_stremio_creds c
where exists (select 1 from public.member_device d where d.user_id = c.user_id)
  and c.status <> 'done';
```
Re-queue a dead-lettered member after fixing the cause:
```sql
update public.member_stremio_creds set status='pending', attempts=0, last_error=null where stremio_email=$1;
```

## 5. Teardown (MANDATORY — plaintext creds)
After the tail is drained **and every Stremio account has been deleted**, destroy the vault:
```bash
psql "$SUPABASE_DB_URL" -f ~/projects/trakt-stremio-import/sql/member_stremio_creds_teardown.sql
```
Optionally remove the cron line and the persovps checkout.

## Kill-switches (two distinct things)
- **Stop the poller** (stop reading creds / logging into Stremio / writing rows): disable the cron line
  (`crontab -e`, remove it) and/or `update public.member_stremio_creds set status='skip';`. This is the real
  poller off-switch — `fetchCandidates` reads `member_stremio_creds`, not the allowlist.
- **Stop on-device restore** (the sync gate): `truncate public.sync_canary_members;` disables all sync instantly;
  delete one row to disable one member. ⚠️ This does **not** stop a running poller — the cron would keep importing
  and re-adding allowlist rows on success. Stop the cron first if you want both. The poller never ungates; it only
  adds allowlist rows on success.
````

- [ ] **Step 2: Add a pointer from `KEVBOX-IMPORT.md`**

Append to the end of `KEVBOX-IMPORT.md`:
```markdown

## Automatic path (the ~320 not-yet-logged-in members)
The hands-off C1 auto-poller + Stremio credential vault is documented separately in **`KEVBOX-POLLER.md`**.
```

- [ ] **Step 3: Commit**

```bash
git add KEVBOX-POLLER.md KEVBOX-IMPORT.md
git commit -m "docs(poller): KEVBOX-POLLER.md ops runbook (apply SQL, load, schedule, monitor, teardown)"
```

---

## Task 9: Final verification

**Files:** none

- [ ] **Step 1: Full suite green**

Run: `cd ~/projects/trakt-stremio-import && npx vitest run`
Expected: all green. Count = 35 (baseline) + 5 (stremio-error) + 3 (env) + 13 (poller-core) + 6 (load-creds) = **62 passed**.

- [ ] **Step 2: Full type-check**

Run: `npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 3: Confirm the branch diff is the expected surface**

Run: `git log --oneline master..feat/kevbox-poller && git diff --stat master..feat/kevbox-poller`
Expected: 8 feature/doc commits; changed files exactly the set in the File Structure table (no stray edits to `convert-kevbox.ts`, `watched-decode.ts`, or the `kevbox.ts` core functions).

- [ ] **Step 4: Report to the operator**

Summarize: the branch is ready; list the prod-touching steps the operator must run (from `KEVBOX-POLLER.md` §1–§3), and that teardown (§5) is mandatory after accounts are deleted. **Do not push or run any prod step.**

---

## Self-Review (completed by plan author)

**Spec coverage:**
- §4 component table (auto runner `kevbox-poller.ts` + `kevbox_poller.sh`) → Task 7. ✅
- §7.1 trigger = `member_device` row; candidate = creds + pending + device row → Task 7 `fetchCandidates`. ✅
- §7.1 allowlist → import → verify → mark done/failed → Task 7 `importOne`/`markDone`/`markFailure`. ✅
- §7.1 credential vault (plaintext email+pass, RLS, no app grants) → Task 5 SQL + Task 6 loader. ✅
- §7.1 re-snapshot sweep / cohort drift → inherent in the live `member_device` join (Task 7); documented. ✅
- §12 RPC-not-deployed / EXECUTE-denied branching → inherited from Plan-1 `kevbox.ts` `pushBatched` (unchanged). ✅
- §12 activity-aware 0-item rule → Task 4 `isSuspectEmpty` + Task 7 use. ✅
- §12 bounded retries + dead-letter after N → Task 4 `nextFailureStatus` + Task 7 `markFailure`. ✅
- §12 no foreground sleep → cron-driven, one pass per tick (Task 7). ✅
- §16 monitoring/alert on failed + members-with-device-but-no-done → Task 8 monitor queries + non-zero exit on dead. ✅
- §16 teardown deliverable → Task 5 `_teardown.sql` + Task 8 §5. ✅
- §17 step 6 (vault + loader) → Tasks 5–6; step 7 (poller) → Task 7. ✅
- §18/§19 rev-5 plaintext + teardown decision → Task 5 SQL header + spec already updated. ✅
- Excludes: 17 done (loader `decideLoadStatus` allowlisted→done), Kevin (KEVIN_UID skip in loader + poller), Trakt-connected (loader `traktConnectedHint`→skip + `--skip`) → Tasks 4/6/7. ✅
- The `[object Object]` nit → Task 1. ✅
- "Do NOT reintroduce skip-removed" → no converter changes; `convert-kevbox.ts`/`buildWatchedEpisodeIndex` reused verbatim. ✅

**Placeholder scan:** none — every step has concrete code/commands and expected output.

**Type consistency:** `CredStatus` (poller-core) used by loader + poller; `ImportCounts`/`VerifyCounts` distinct and used consistently; `decideLoadStatus` arg object identical between Task 4 definition, its test, and the Task 6 call site; `MAX_ATTEMPTS`/`TICK_LIMIT` defined once in poller-core and imported. `parseSheetRow`/`parseSkipList`/`SheetCred` exported from `load-stremio-creds.ts` and imported by its test. ✅

---

## Gap-analysis amendments (2026-06-15, ultracode workflow — 12 confirmed, 1 dropped by operator, 11 folded in)

A multi-agent adversarial gap analysis (7 dimensions → per-finding refutation) ran against this plan before coding. 17 findings were refuted (reuse-contracts, types, TDD steps, allowlist-after-verify safety all verified correct). The confirmed gaps and how each was folded in:
- **[dropped] login→watch→poll merge race** — non-issue: the ~320 C1 members install fresh (get 0.8.5+ with Option B); only the ~17 already-imported members may run older builds and they're excluded from C1. (Task 7 Design notes + ops-doc precondition updated to state this.)
- **[2] MED** removed the poller's blanket `library.length===0` throw (§12 says don't inherit it) → clean-empty members resolve to `done`. (Task 7 `importOne`)
- **[3] MED** cron line is stdout-only (no `2>&1`) so `FAILED`/`DEAD` on stderr actually mail via `MAILTO`. (Task 8 §3)
- **[4] MED** every failure (not just dead-letters) surfaces via stderr→mail. (Task 7 summary comment + Task 8 §3)
- **[5] MED** `markDone` nulls `stremio_password` → plaintext blast radius shrinks to not-yet-imported members; top-up note added. (Task 7 `markDone`, Task 8 §2)
- **[6] MED** 30-min retry backoff predicate in `fetchCandidates` → a transient outage can't burn all 3 attempts in 15 min. (Task 7)
- **[7] MED** per-member `withTimeout` (120s) wrapper → a hung Cinemeta fetch can't stall the tick / hold the advisory lock. (Task 7)
- **[8]+[12] LOW** added the 13th `poller-core` test (`isKevin && isAllowlisted → done`) → the "13 tests / 62 passed" counts are now accurate, and Kevin's real prod precedence is pinned. (Task 4)
- **[9] LOW** loader imports `isAllowlisted` from `utils/kevbox` instead of a duplicate. (Task 6)
- **[10] LOW** vault `revoke` includes the `public` role, matching the `sync_canary_members` precedent. (Task 5)
- **[11] LOW** ops doc distinguishes the two kill-switches (stop-cron vs the restore gate). (Task 8)
