# KevBox Member Enrollment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Manage the AIOStreams member allowlist from the `admin.kevbox.dev` dashboard — enroll/rename/rotate/un-enroll a member, store their encrypted Premiumize key, and hot-write a `members.json` the kevbox container reads with no restart.

**Architecture:** Sidecar tables (`kevbox_member`, `kevbox_allowlist_extra`) keyed to `auth.users`. A core renderer writes a lean `members.json` atomically (group-readable, empty-set safety floor) into a shared dir. Mutations run under a transaction-scoped advisory lock (auto-released at txn end) + transaction, rendering inside the txn before commit. Premiumize keys are AES-256-GCM encrypted (versioned, AAD = user_id). A one-off migration imports the legacy 261 names verbatim. The dead debrid onboarding is deleted.

**Tech Stack:** TypeScript ESM monorepo (`packages/core`, `apps/web` Fastify+React SPA, `packages/cli` commander). Raw `pg`. vitest against a real Postgres at `localhost:5433` (`packages/core/test/schema.sql`, `withRollback`). Repo: `/home/kevin/projects/NuvioTV/kevbox-admin`.

**Prerequisite:** Deploy the AIOStreams file-source plan (`2026-06-12-aiostreams-members-file-source.md`) FIRST. With `members.json` absent, the container falls back to `KEVBOX_MEMBERS` env, so this plan can be built + the migration dry-run-tested before any cutover.

**Spec:** `docs/superpowers/specs/2026-06-11-kevbox-member-enrollment-design.md` (rev. 2026-06-12). Hole IDs (C1–C5, H1–H6, M1–M4) referenced per task.

---

## File Structure

**Create (core):**
- `packages/core/src/crypto.ts` — `loadEncKey`, `encryptSecret`, `decryptSecret` (AES-256-GCM, versioned, AAD).
- `packages/core/src/kevboxAllowlist.ts` — `renderMembersFile` (renderer + empty-set floor + atomic group-readable write).
- `packages/core/src/kevboxMember.ts` — name helpers + pure-SQL mutations (`enrollMember`, `renameMember`, `unenrollMember`, `rotateKey`, `getKevbox`, `buildInstallUrl`, `reapplyKevboxAddon`).
- `packages/core/src/kevboxWrite.ts` — `withKevboxWrite` orchestrator (Pool-or-client adaptive lock+txn+render) + `KevboxConfig`.
- `packages/core/src/migrate261.ts` — `migrate261` one-off importer.
- `packages/core/src/kevboxAudit.ts` — `writeAudit` audit-trail helper (Task 8b, spec §13).

**Create (server/web/cli):**
- `apps/web/src/server/routes/kevbox.ts` — GET install-url, PUT, DELETE.
- `apps/web/src/web/components/KevboxTab.tsx` — the new tab.

**Create (deploy):**
- `deploy/kevbox_member_setup.sql` — prod DDL (mirrors test schema additions).

**Modify:** `packages/core/src/{types,index,members}.ts`, `packages/core/test/schema.sql`, `apps/web/src/server/{config,app,index}.ts`, `apps/web/src/server/routes/members.ts`, `apps/web/test/server/helpers.ts`, `apps/web/src/web/lib/api.ts`, `apps/web/src/web/App.tsx`, `apps/web/src/web/components/{MemberDetail,MemberList}.tsx`, `packages/cli/src/{index,actions,format}.ts`, `deploy/{env.example,kevbox-admin.service,README.md}`.

**Delete (Task 11):** `packages/core/src/debrid.ts`, `packages/core/test/debrid.test.ts`, `apps/web/src/web/components/DebridForm.tsx`, and the debrid bits of `defaults.ts`, `index.ts`, `actions.ts` (cli + server), `api.ts`.

---

### Task 1: Schema — sidecar tables + partial unique index

**Files:**
- Modify: `packages/core/test/schema.sql` (append after the telemetry block, end of file)
- Create: `deploy/kevbox_member_setup.sql`
- Test: `packages/core/test/kevboxSchema.test.ts`

- [ ] **Step 1: Write the failing test**

Create `packages/core/test/kevboxSchema.test.ts`:

```ts
import { afterAll, expect, test } from "vitest";
import { pool, withRollback, createTestMember } from "./helpers.js";

afterAll(async () => { await pool.end(); });

test("kevbox_member + kevbox_allowlist_extra exist with expected constraints", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "schema-kvm@test.dev");
    // insert is accepted
    await db.query(
      `insert into public.kevbox_member (user_id, aiostreams_name, premiumize_key_enc)
       values ($1, $2, $3)`,
      [uid, "schema-kvm", "v1.aaa.bbb.ccc"],
    );
    const { rows } = await db.query(
      "select aiostreams_name, enrolled, premiumize_key_enc from public.kevbox_member where user_id = $1",
      [uid],
    );
    expect(rows[0].enrolled).toBe(true);
    expect(rows[0].aiostreams_name).toBe("schema-kvm");

    // bad name rejected by the check constraint
    const uid2 = await createTestMember(db, "schema-bad@test.dev");
    await expect(
      db.query(`insert into public.kevbox_member (user_id, aiostreams_name) values ($1, $2)`, [
        uid2,
        "BAD UPPER",
      ]),
    ).rejects.toThrow();

    // extras table accepts a verbatim name
    await db.query(`insert into public.kevbox_allowlist_extra (aiostreams_name) values ($1)`, [
      "legacy.only",
    ]);
    const { rows: ex } = await db.query(
      "select aiostreams_name from public.kevbox_allowlist_extra where aiostreams_name = 'legacy.only'",
    );
    expect(ex).toHaveLength(1);
  });
});

test("active-name uniqueness binds enrolled rows only (name reuse after un-enroll, H5)", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "reuse-a@test.dev");
    const b = await createTestMember(db, "reuse-b@test.dev");
    // member A enrolled as "shared"
    await db.query(
      `insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1, 'shared', true)`,
      [a],
    );
    // a SECOND enrolled "shared" must fail
    await expect(
      db.query(
        `insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1, 'shared', true)`,
        [b],
      ),
    ).rejects.toThrow();
    // but once A is un-enrolled, B may take the name
    await db.query(`update public.kevbox_member set enrolled = false where user_id = $1`, [a]);
    await db.query(
      `insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1, 'shared', true)`,
      [b],
    );
    const { rows } = await db.query(
      "select count(*)::int as n from public.kevbox_member where aiostreams_name = 'shared'",
    );
    expect(rows[0].n).toBe(2);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/kevboxSchema.test.ts`
Expected: FAIL — relation `public.kevbox_member` does not exist.

- [ ] **Step 3: Add the tables to the test schema**

Append to `packages/core/test/schema.sql` (after the telemetry section, at EOF):

```sql
-- ===== KevBox member enrollment (spec §4) =====
-- Sidecar per auth.users member. aiostreams_name is the CANONICAL verbatim live
-- allowlist token (never re-derived from email at migration time, C1). Name
-- uniqueness binds ACTIVE members only (partial index, H5) so a departed member's
-- name can be reused. premiumize_key_enc nullable (name-only after a backfill miss).
create table public.kevbox_member (
  user_id            uuid primary key references auth.users(id) on delete cascade,
  aiostreams_name    text not null
                       check (aiostreams_name ~ '^[a-z0-9._+-]{1,64}$'),
  premiumize_key_enc text,
  enrolled           boolean not null default true,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
create unique index kevbox_member_name_active
  on public.kevbox_member (aiostreams_name) where enrolled;

-- Safety net for any legacy allowlist name that does not resolve to an auth.users row.
create table public.kevbox_allowlist_extra (
  aiostreams_name text primary key
                    check (aiostreams_name ~ '^[a-z0-9._+-]{1,64}$'),
  note            text,
  created_at      timestamptz not null default now()
);
```

- [ ] **Step 4: Reload the test schema and run the test**

Run (loads schema into the test DB, then runs the test):
```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
PGPASSWORD=test psql -h localhost -p 5433 -U postgres -d kevbox_test -f packages/core/test/schema.sql
npx vitest run --root packages/core test/kevboxSchema.test.ts
```
Expected: PASS. (If the test DB is provisioned by a script/container init, re-run that instead of `psql`; the project applies `schema.sql` to the `kevbox_test` DB at `:5433`.)

- [ ] **Step 5: Create the production setup SQL**

Create `deploy/kevbox_member_setup.sql` (KEEP the table DDL byte-identical to the schema.sql block above; add prod-only grants for the least-priv `kevbox_admin` role).

Run it once as POSTGRES (the superuser/owner) in the Supabase SQL editor or via
`psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f deploy/kevbox_member_setup.sql` — the
least-priv `kevbox_admin` role CANNOT `CREATE TABLE`/`GRANT`, so applying this as kevbox_admin
will fail mid-file.

```sql
-- KevBox member enrollment — PRODUCTION setup.
-- Run ONCE as POSTGRES in the Supabase SQL editor (kevbox_admin cannot CREATE TABLE/GRANT).
-- Prefer: psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f deploy/kevbox_member_setup.sql
-- Table DDL is byte-identical to packages/core/test/schema.sql (M2 drift guard).

-- Guard: the grants below target role kevbox_admin; fail loudly if it doesn't exist.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'kevbox_admin') then
    raise exception 'role kevbox_admin does not exist — create it before running this setup';
  end if;
end
$$;

create table if not exists public.kevbox_member (
  user_id            uuid primary key references auth.users(id) on delete cascade,
  aiostreams_name    text not null
                       check (aiostreams_name ~ '^[a-z0-9._+-]{1,64}$'),
  premiumize_key_enc text,
  enrolled           boolean not null default true,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
create unique index if not exists kevbox_member_name_active
  on public.kevbox_member (aiostreams_name) where enrolled;

create table if not exists public.kevbox_allowlist_extra (
  aiostreams_name text primary key
                    check (aiostreams_name ~ '^[a-z0-9._+-]{1,64}$'),
  note            text,
  created_at      timestamptz not null default now()
);

-- Defense in depth: these sidecar tables hold encrypted keys + allowlist names. Even though
-- kevbox_admin is BYPASSRLS, enable RLS and strip the Supabase default-grants to anon/authenticated
-- so a public view or a stray PostgREST request can never read them (mirrors member_access).
alter table public.kevbox_member enable row level security;
alter table public.kevbox_allowlist_extra enable row level security;
revoke all on public.kevbox_member from anon, authenticated;
revoke all on public.kevbox_allowlist_extra from anon, authenticated;

-- The dashboard connects as the least-privileged kevbox_admin role (deploy/env.example).
grant select, insert, update, delete on public.kevbox_member to kevbox_admin;
grant select, insert, update, delete on public.kevbox_allowlist_extra to kevbox_admin;
-- Advisory locks need no table grant. kevbox_admin is BYPASSRLS (like member_access).
```

- [ ] **Step 6: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add packages/core/test/schema.sql packages/core/test/kevboxSchema.test.ts deploy/kevbox_member_setup.sql
git commit -m "feat(core): kevbox_member + kevbox_allowlist_extra schema (partial unique, C1/H5)"
```

---

### Task 2: Crypto — versioned AES-256-GCM with AAD

**Files:**
- Create: `packages/core/src/crypto.ts`
- Test: `packages/core/test/crypto.test.ts`

- [ ] **Step 1: Write the failing test**

Create `packages/core/test/crypto.test.ts`:

```ts
import { expect, test } from "vitest";
import { loadEncKey, encryptSecret, decryptSecret } from "../src/crypto.js";

const KEY_HEX = "0".repeat(64); // 32 zero bytes; fine for tests
const key = loadEncKey(KEY_HEX);
const USER = "11111111-1111-1111-1111-111111111111";

test("round-trips a secret", () => {
  const enc = encryptSecret("pm-key-abc", USER, key);
  expect(enc.startsWith("v1.")).toBe(true);
  expect(decryptSecret(enc, USER, key)).toBe("pm-key-abc");
});

test("ciphertexts are non-deterministic (random IV)", () => {
  expect(encryptSecret("x", USER, key)).not.toBe(encryptSecret("x", USER, key));
});

test("wrong AAD (user_id) fails authentication (row-swap defense) — NOT a 400", () => {
  const enc = encryptSecret("secret", USER, key);
  let thrown: (Error & { statusCode?: number }) | undefined;
  try { decryptSecret(enc, "22222222-2222-2222-2222-222222222222", key); }
  catch (e) { thrown = e as Error & { statusCode?: number }; }
  expect(thrown).toBeInstanceOf(Error);
  // auth failure is an internal (key/data) fault, not a client input error → no 400 tag
  expect(thrown!.statusCode).toBeUndefined();
});

test("tampered ciphertext is rejected (auth failure, NOT a 400)", () => {
  const enc = encryptSecret("secret", USER, key);
  const parts = enc.split(".");
  parts[3] = Buffer.from("tampered").toString("base64");
  let thrown: (Error & { statusCode?: number }) | undefined;
  try { decryptSecret(parts.join("."), USER, key); }
  catch (e) { thrown = e as Error & { statusCode?: number }; }
  expect(thrown).toBeInstanceOf(Error);
  expect(thrown!.statusCode).toBeUndefined(); // collapses to a generic 500, not a 400
});

test("malformed input throws a clean 400, not a raw crypto exception", () => {
  const cases = ["garbage", "v2.a.b.c"];
  for (const bad of cases) {
    let thrown: (Error & { statusCode?: number }) | undefined;
    try { decryptSecret(bad, USER, key); }
    catch (e) { thrown = e as Error & { statusCode?: number }; }
    expect(thrown).toBeInstanceOf(Error);
    expect(thrown!.message).toMatch(/malformed/);
    expect(thrown!.statusCode).toBe(400); // malformed input IS a client fault
  }
});

test("loadEncKey: 64 hex chars OR base64, must be 32 bytes", () => {
  expect(loadEncKey(KEY_HEX).length).toBe(32);
  expect(loadEncKey(Buffer.alloc(32, 7).toString("base64")).length).toBe(32);
  expect(() => loadEncKey("tooshort")).toThrow();
  expect(() => loadEncKey(Buffer.alloc(16).toString("base64"))).toThrow(/32 bytes/);
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/crypto.test.ts`
Expected: FAIL — cannot find module `../src/crypto.js`.

- [ ] **Step 3: Implement crypto**

Create `packages/core/src/crypto.ts`:

```ts
import { randomBytes, createCipheriv, createDecipheriv } from "node:crypto";

const VERSION = "v1";

/**
 * A typed error so the web layer never leaks a raw node:crypto exception. MALFORMED input
 * (bad structure / wrong version / wrong field lengths) is a client fault → statusCode 400.
 * Authentication failure (createDecipheriv/final rejects: wrong key, corrupted data, AAD
 * mismatch) is NOT a client-correctable input error — leave statusCode UNSET so the web layer
 * collapses it to a generic 500 "internal error" and logs it at error level (a key/data fault
 * must not masquerade as a 400 "bad request").
 */
function malformedError(message: string): Error {
  const e = new Error(message) as Error & { statusCode?: number };
  e.statusCode = 400;
  return e;
}
function authFailureError(message: string): Error {
  // No statusCode → the Fastify error handler maps it to 500 (internal), logged at error level.
  return new Error(message);
}

/**
 * Decode KEVBOX_ENC_KEY → a 32-byte key. Encoding rule (no ambiguity, §5):
 * exactly 64 hex chars → hex; else base64. Result MUST be 32 bytes.
 */
export function loadEncKey(raw: string): Buffer {
  const s = (raw ?? "").trim();
  const key = /^[0-9a-fA-F]{64}$/.test(s) ? Buffer.from(s, "hex") : Buffer.from(s, "base64");
  if (key.length !== 32) {
    throw new Error("KEVBOX_ENC_KEY must decode to 32 bytes (64 hex chars or base64)");
  }
  return key;
}

/** AES-256-GCM encrypt, AAD-bound to userId. Format: v1.b64(iv).b64(tag).b64(ct). */
export function encryptSecret(plain: string, userId: string, key: Buffer): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(userId, "utf8"));
  const ct = Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return [VERSION, iv.toString("base64"), tag.toString("base64"), ct.toString("base64")].join(".");
}

/** Reverse of encryptSecret. Validates structure + AAD; throws a typed error on any mismatch. */
export function decryptSecret(enc: string, userId: string, key: Buffer): string {
  const parts = (enc ?? "").split(".");
  if (parts.length !== 4 || parts[0] !== VERSION) throw malformedError("malformed ciphertext");
  const iv = Buffer.from(parts[1], "base64");
  const tag = Buffer.from(parts[2], "base64");
  const ct = Buffer.from(parts[3], "base64");
  if (iv.length !== 12 || tag.length !== 16) throw malformedError("malformed ciphertext");
  try {
    const decipher = createDecipheriv("aes-256-gcm", key, iv);
    decipher.setAAD(Buffer.from(userId, "utf8"));
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(ct), decipher.final()]).toString("utf8");
  } catch {
    // NOT a 400: a key/data/AAD fault collapses to a generic internal error (500), logged at error.
    throw authFailureError("ciphertext authentication failed");
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/crypto.test.ts`
Expected: PASS (all 6 tests; the two auth-failure cases assert `statusCode` is UNSET — a 500-class
fault — while the malformed case asserts `statusCode === 400`).

- [ ] **Step 5: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add packages/core/src/crypto.ts packages/core/test/crypto.test.ts
git commit -m "feat(core): versioned AES-256-GCM secret crypto with AAD=user_id (§5)"
```

---

### Task 3: Renderer — `members.json` with safety floor + atomic group-readable write

**Files:**
- Create: `packages/core/src/kevboxAllowlist.ts`
- Test: `packages/core/test/kevboxAllowlist.test.ts`

- [ ] **Step 1: Write the failing test**

Create `packages/core/test/kevboxAllowlist.test.ts`:

```ts
import { afterAll, afterEach, expect, test } from "vitest";
import { readFileSync, rmSync, statSync, mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { pool, withRollback, createTestMember } from "./helpers.js";
import { renderMembersFile } from "../src/kevboxAllowlist.js";

afterAll(async () => { await pool.end(); });

const tmpFiles: string[] = [];
function tmpMembersPath(): string {
  const dir = mkdtempSync(join(tmpdir(), "kevbox-render-"));
  const p = join(dir, "members.json");
  tmpFiles.push(p);
  return p;
}
afterEach(() => { for (const p of tmpFiles.splice(0)) { try { rmSync(p); } catch { /* ignore */ } } });

test("renders enrolled members + extras, sorted, deduped", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "rend-a@test.dev");
    const b = await createTestMember(db, "rend-b@test.dev");
    await db.query(`insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'bravo',true),($2,'alpha',true)`, [a, b]);
    await db.query(`insert into public.kevbox_allowlist_extra (aiostreams_name) values ('alpha'),('zulu')`); // 'alpha' dup
    const file = tmpMembersPath();

    const names = await renderMembersFile(db, file);
    expect(names).toEqual(["alpha", "bravo", "zulu"]);
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["alpha", "bravo", "zulu"]);
  });
});

test("excludes un-enrolled members", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "rend-off@test.dev");
    await db.query(`insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'ghost',false)`, [a]);
    await db.query(`insert into public.kevbox_allowlist_extra (aiostreams_name) values ('kept')`);
    const file = tmpMembersPath();
    expect(await renderMembersFile(db, file)).toEqual(["kept"]);
  });
});

test("empty set refuses to write (C2 safety floor) and leaves any prior file intact", async () => {
  await withRollback(async (db) => {
    const file = tmpMembersPath();
    // no rows at all → must throw, must not create the file
    await expect(renderMembersFile(db, file)).rejects.toThrow(/empty/i);
    expect(() => statSync(file)).toThrow();
  });
});

test("written file is group-readable (mode 0664, C4)", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "rend-perm@test.dev");
    await db.query(`insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'permname',true)`, [a]);
    const file = tmpMembersPath();
    await renderMembersFile(db, file);
    // low 9 perm bits == rw-rw-r-- (0o664). umask may strip group-write on tmp; assert at least group-read.
    const mode = statSync(file).mode & 0o060;
    expect(mode & 0o040).toBe(0o040); // group read bit set
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/kevboxAllowlist.test.ts`
Expected: FAIL — cannot find module `../src/kevboxAllowlist.js`.

- [ ] **Step 3: Implement the renderer**

Create `packages/core/src/kevboxAllowlist.ts`:

```ts
import { open, rename } from "node:fs/promises";
import { dirname, join } from "node:path";
import type { Db } from "./types.js";

const NAME_RE = /^[a-z0-9._+-]{1,64}$/;

/**
 * Atomic, group-readable write: write a sibling .tmp, fchmod 0664, fsync, rename over the
 * target (so a reader never sees a half-written file). The shared dir should be setgid so the
 * tmp inherits group `kevbox`; we still fchmod 0664 explicitly so a tight umask can't make the
 * file unreadable to the container's group (C4).
 */
async function atomicWriteFile(filePath: string, contents: string): Promise<void> {
  const tmp = join(dirname(filePath), `${".tmp-"}${process.pid}-members.json`);
  const fh = await open(tmp, "w", 0o664);
  try {
    await fh.writeFile(contents, "utf8");
    await fh.chmod(0o664);
    await fh.sync();
  } finally {
    await fh.close();
  }
  await rename(tmp, filePath);
}

/**
 * Render the full allowlist snapshot from the DB and atomically write it to `filePath`.
 * Returns the rendered names. EMPTY-SET SAFETY FLOOR (C2): if the resolved set is empty,
 * THROW and do not write — a bad query / half-applied migration must never blank the
 * allowlist and lock everyone out; the previous file stays intact.
 */
export async function renderMembersFile(db: Db, filePath: string): Promise<string[]> {
  const { rows } = await db.query<{ aiostreams_name: string }>(
    `select aiostreams_name from public.kevbox_member where enrolled = true
     union
     select aiostreams_name from public.kevbox_allowlist_extra
     order by 1`,
  );
  const names = rows.map((r) => r.aiostreams_name).filter((n) => NAME_RE.test(n));
  if (names.length === 0) {
    throw new Error(
      "refusing to write empty members.json (safety floor): rendered 0 names; previous file left intact",
    );
  }
  await atomicWriteFile(filePath, JSON.stringify(names));
  return names;
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/kevboxAllowlist.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add packages/core/src/kevboxAllowlist.ts packages/core/test/kevboxAllowlist.test.ts
git commit -m "feat(core): members.json renderer with empty-set floor + atomic group-readable write (C2/C4)"
```

---

### Task 4: Core mutations + name helpers (pure SQL)

**Files:**
- Create: `packages/core/src/kevboxMember.ts`
- Modify: `packages/core/src/types.ts` (add `KevboxConfig`, `KevboxState`)
- Test: `packages/core/test/kevboxMember.test.ts`

- [ ] **Step 1: Add shared types**

Append to `packages/core/src/types.ts`:

```ts
/** Config the kevbox mutations + renderer need (built from env in server/cli). */
export interface KevboxConfig {
  encKey: Buffer;
  membersFile: string;
  streamsBaseUrl: string; // e.g. https://streams.kevbox.dev (no trailing slash)
  addonSort: number; // KEVBOX_ADDON_SORT, default 4
}

/** Non-secret enrollment view returned to the dashboard (never the key or install URL). */
export interface KevboxState {
  name: string;
  enrolled: boolean;
  hasKey: boolean;
}
```

- [ ] **Step 2: Write the failing test**

Create `packages/core/test/kevboxMember.test.ts`:

```ts
import { afterAll, expect, test } from "vitest";
import { mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { KevboxConfig } from "../src/types.js";
import { pool, withRollback, createTestMember } from "./helpers.js";
import { loadEncKey } from "../src/crypto.js";
import {
  localPart,
  enrollMember,
  renameMember,
  rotateKey,
  unenrollMember,
  getKevbox,
  buildInstallUrl,
} from "../src/kevboxMember.js";

afterAll(async () => { await pool.end(); });

function cfg(): KevboxConfig {
  return {
    encKey: loadEncKey("0".repeat(64)),
    membersFile: join(mkdtempSync(join(tmpdir(), "kvm-")), "members.json"),
    streamsBaseUrl: "https://streams.kevbox.dev",
    addonSort: 4,
  };
}

const kevboxRows = (db: any, uid: string) =>
  db.query(`select url from public.member_addon where user_id = $1 and url like 'https://streams.kevbox.dev/stremio/k/%'`, [uid]).then((r: any) => r.rows.map((x: any) => x.url));

test("localPart derives the email local-part, lowercased", () => {
  expect(localPart("John.Doe@example.com")).toBe("john.doe");
  expect(localPart(null)).toBe("");
});

test("enroll stores verbatim name, encrypts key, adds the member_addon URL", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "Enroll.One@test.dev");
    const c = cfg();
    const { installUrl, name } = await enrollMember(db, uid, { premiumizeKey: "PMKEY" }, c);
    expect(name).toBe("enroll.one"); // defaulted from email local-part
    expect(installUrl).toBe("https://streams.kevbox.dev/stremio/k/enroll.one/PMKEY/manifest.json");

    const state = await getKevbox(db, uid, c);
    expect(state).toEqual({ name: "enroll.one", enrolled: true, hasKey: true });
    expect(await kevboxRows(db, uid)).toEqual([installUrl]);
    // the stored key round-trips via buildInstallUrl
    expect(await buildInstallUrl(db, uid, c)).toBe(installUrl);
  });
});

test("enroll honors an explicit verbatim name (C1)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "john@test.dev");
    const c = cfg();
    const { name } = await enrollMember(db, uid, { aiostreamsName: "john2", premiumizeKey: "K" }, c);
    expect(name).toBe("john2");
    expect((await getKevbox(db, uid, c))!.name).toBe("john2");
  });
});

test("enroll rejects a duplicate active name", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "dupe-a@test.dev");
    const b = await createTestMember(db, "dupe-b@test.dev");
    const c = cfg();
    await enrollMember(db, a, { aiostreamsName: "samename", premiumizeKey: "K" }, c);
    await expect(
      enrollMember(db, b, { aiostreamsName: "samename", premiumizeKey: "K" }, c),
    ).rejects.toThrow(/in use/i);
  });
});

test("rotateKey replaces the URL with the new key", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "rot@test.dev");
    const c = cfg();
    await enrollMember(db, uid, { aiostreamsName: "rotuser", premiumizeKey: "OLD" }, c);
    const { installUrl } = await rotateKey(db, uid, "NEW", c);
    expect(installUrl).toBe("https://streams.kevbox.dev/stremio/k/rotuser/NEW/manifest.json");
    expect(await kevboxRows(db, uid)).toEqual([installUrl]); // exactly one, the new one
  });
});

test("renameMember updates name + URL (H1)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "ren@test.dev");
    const c = cfg();
    await enrollMember(db, uid, { aiostreamsName: "oldname", premiumizeKey: "K" }, c);
    const { installUrl } = await renameMember(db, uid, "newname", c);
    expect(installUrl).toBe("https://streams.kevbox.dev/stremio/k/newname/K/manifest.json");
    expect((await getKevbox(db, uid, c))!.name).toBe("newname");
    expect(await kevboxRows(db, uid)).toEqual([installUrl]);
  });
});

test("unenroll flips enrolled=false and removes the kevbox URL (key retained)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "un@test.dev");
    const c = cfg();
    await enrollMember(db, uid, { aiostreamsName: "ununun", premiumizeKey: "K" }, c);
    await unenrollMember(db, uid, c);
    expect((await getKevbox(db, uid, c))).toEqual({ name: "ununun", enrolled: false, hasKey: true });
    expect(await kevboxRows(db, uid)).toEqual([]);
  });
});

test("getKevbox returns null for a never-enrolled member", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "none@test.dev");
    expect(await getKevbox(db, uid, cfg())).toBeNull();
  });
});

test("enroll throws for an unknown userId", async () => {
  await withRollback(async (db) => {
    await expect(
      enrollMember(db, "00000000-0000-0000-0000-0000000000ff", { premiumizeKey: "K" }, cfg()),
    ).rejects.toThrow(/not found/i);
  });
});
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/kevboxMember.test.ts`
Expected: FAIL — cannot find module `../src/kevboxMember.js`.

- [ ] **Step 4: Implement the mutations**

Create `packages/core/src/kevboxMember.ts`:

```ts
import type { Db, KevboxConfig, KevboxState } from "./types.js";
import { encryptSecret, decryptSecret } from "./crypto.js";

const NAME_RE = /^[a-z0-9._+-]{1,64}$/;

function validationError(message: string): Error {
  const e = new Error(message) as Error & { statusCode?: number };
  e.statusCode = 400;
  return e;
}
function notFound(message: string): Error {
  const e = new Error(message) as Error & { statusCode?: number };
  e.statusCode = 404;
  return e;
}

/** Lowercased email local-part (the default aiostreams_name). "" when email is null. */
export function localPart(email: string | null): string {
  if (!email) return "";
  return email.split("@")[0]!.trim().toLowerCase();
}

function validateName(raw: string): string {
  const name = (raw ?? "").trim();
  if (!NAME_RE.test(name)) {
    throw validationError(`invalid aiostreams name: "${raw}" (must match ${NAME_RE})`);
  }
  return name;
}

/** Load a member's email or throw 404. */
async function memberEmail(db: Db, userId: string): Promise<string | null> {
  const { rows } = await db.query<{ email: string | null }>(
    "select email from public.kevbox_auth_users where id = $1",
    [userId],
  );
  if (rows.length === 0) throw notFound(`member not found: ${userId}`);
  return rows[0].email;
}

/** Throw if `name` is already used by a DIFFERENT enrolled member. */
async function assertActiveNameFree(db: Db, name: string, userId: string): Promise<void> {
  const { rows } = await db.query(
    "select 1 from public.kevbox_member where aiostreams_name = $1 and enrolled and user_id <> $2",
    [name, userId],
  );
  if (rows.length > 0) throw validationError(`aiostreams name already in use: ${name}`);
}

function installUrl(cfg: KevboxConfig, name: string, key: string): string {
  return `${cfg.streamsBaseUrl}/stremio/k/${name}/${key}/manifest.json`;
}

/** Delete this member's kevbox member_addon row(s) (URL prefix match on the streams base). */
async function deleteKevboxAddon(db: Db, userId: string, cfg: KevboxConfig): Promise<void> {
  await db.query("delete from public.member_addon where user_id = $1 and url like $2", [
    userId,
    `${cfg.streamsBaseUrl}/stremio/k/%`,
  ]);
}

/** Load the kevbox_member row (or null). */
async function loadRow(db: Db, userId: string) {
  const { rows } = await db.query<{
    aiostreams_name: string;
    premiumize_key_enc: string | null;
    enrolled: boolean;
  }>(
    "select aiostreams_name, premiumize_key_enc, enrolled from public.kevbox_member where user_id = $1",
    [userId],
  );
  return rows[0] ?? null;
}

/** Non-secret enrollment view (name/enrolled/hasKey), or null if never enrolled. */
export async function getKevbox(db: Db, userId: string, _cfg: KevboxConfig): Promise<KevboxState | null> {
  const row = await loadRow(db, userId);
  if (!row) return null;
  return { name: row.aiostreams_name, enrolled: row.enrolled, hasKey: row.premiumize_key_enc !== null };
}

/** Build the key-bearing install URL (decrypts the stored key). null if no key stored. */
export async function buildInstallUrl(db: Db, userId: string, cfg: KevboxConfig): Promise<string | null> {
  const row = await loadRow(db, userId);
  if (!row || row.premiumize_key_enc === null) return null;
  const key = decryptSecret(row.premiumize_key_enc, userId, cfg.encKey);
  return installUrl(cfg, row.aiostreams_name, key);
}

/** Enroll (or re-enroll) a member. Requires a Premiumize key. Returns the install URL + stored name. */
export async function enrollMember(
  db: Db,
  userId: string,
  opts: { aiostreamsName?: string; premiumizeKey: string },
  cfg: KevboxConfig,
): Promise<{ installUrl: string; name: string }> {
  const key = (opts.premiumizeKey ?? "").trim();
  if (!key) throw validationError("premiumizeKey is required");
  const email = await memberEmail(db, userId);
  const name = validateName(opts.aiostreamsName ?? localPart(email));
  await assertActiveNameFree(db, name, userId);

  const enc = encryptSecret(key, userId, cfg.encKey);
  await db.query(
    `insert into public.kevbox_member (user_id, aiostreams_name, premiumize_key_enc, enrolled, updated_at)
     values ($1, $2, $3, true, now())
     on conflict (user_id) do update
       set aiostreams_name = excluded.aiostreams_name,
           premiumize_key_enc = excluded.premiumize_key_enc,
           enrolled = true, updated_at = now()`,
    [userId, name, enc],
  );

  const url = installUrl(cfg, name, key);
  await deleteKevboxAddon(db, userId, cfg); // drop any stale kevbox row first (name/key may have changed)
  await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, true, $3)
     on conflict (user_id, url) do nothing`,
    [userId, url, cfg.addonSort],
  );
  return { installUrl: url, name };
}

/** Re-key an enrolled member; replaces the kevbox URL. Member must already have a row. */
export async function rotateKey(
  db: Db,
  userId: string,
  premiumizeKey: string,
  cfg: KevboxConfig,
): Promise<{ installUrl: string }> {
  const key = (premiumizeKey ?? "").trim();
  if (!key) throw validationError("premiumizeKey is required");
  const row = await loadRow(db, userId);
  if (!row) throw notFound(`member is not enrolled: ${userId}`);
  const enc = encryptSecret(key, userId, cfg.encKey);
  await db.query(
    "update public.kevbox_member set premiumize_key_enc = $2, updated_at = now() where user_id = $1",
    [userId, enc],
  );
  const url = installUrl(cfg, row.aiostreams_name, key);
  await deleteKevboxAddon(db, userId, cfg);
  await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, true, $3) on conflict (user_id, url) do nothing`,
    [userId, url, cfg.addonSort],
  );
  return { installUrl: url };
}

/** Rename an enrolled member (name is in the URL path). Rebuilds the URL when a key is stored. */
export async function renameMember(
  db: Db,
  userId: string,
  newName: string,
  cfg: KevboxConfig,
): Promise<{ installUrl: string | null; keyless: boolean }> {
  const name = validateName(newName);
  const row = await loadRow(db, userId);
  if (!row) throw notFound(`member is not enrolled: ${userId}`);
  await assertActiveNameFree(db, name, userId);
  await db.query(
    "update public.kevbox_member set aiostreams_name = $2, updated_at = now() where user_id = $1",
    [userId, name],
  );
  if (row.premiumize_key_enc === null) {
    await deleteKevboxAddon(db, userId, cfg); // no key → can't rebuild; drop the stale URL
    return { installUrl: null, keyless: true };
  }
  const key = decryptSecret(row.premiumize_key_enc, userId, cfg.encKey);
  const url = installUrl(cfg, name, key);
  await deleteKevboxAddon(db, userId, cfg);
  await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, true, $3) on conflict (user_id, url) do nothing`,
    [userId, url, cfg.addonSort],
  );
  return { installUrl: url, keyless: false };
}

/** Un-enroll: flip enrolled=false, drop the kevbox URL. Encrypted key retained (default, §8). */
export async function unenrollMember(db: Db, userId: string, cfg: KevboxConfig): Promise<void> {
  const row = await loadRow(db, userId);
  if (!row) throw notFound(`member is not enrolled: ${userId}`);
  await db.query(
    "update public.kevbox_member set enrolled = false, updated_at = now() where user_id = $1",
    [userId],
  );
  await deleteKevboxAddon(db, userId, cfg);
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/kevboxMember.test.ts`
Expected: PASS (all tests).

- [ ] **Step 6: Make `resetToDefaults` kevbox-aware (reset must not silently strip an enrolled member's URL)**

`packages/core/src/reset.ts` deletes EVERY non-default `member_addon` row for the member — including
the kevbox addon URL at sort 4. So after a Reset-to-defaults, an enrolled member loses their kevbox
addon until the next enroll/rotate. Fix it by re-adding the kevbox URL after the reset when the
member is enrolled and has a stored key.

Add a core helper to `packages/core/src/kevboxMember.ts` that rebuilds + re-inserts the URL from the
stored key (reusing the private `installUrl`/`deleteKevboxAddon` helpers already in this file):

```ts
/**
 * Re-add the kevbox member_addon URL after a reset-to-defaults wiped it. No-op when the member is
 * not enrolled or has no stored key. Rebuilds `/stremio/k/<name>/<key>/manifest.json` from the
 * stored (decrypted) key so a Reset never silently strips an enrolled member's kevbox addon.
 */
export async function reapplyKevboxAddon(db: Db, userId: string, cfg: KevboxConfig): Promise<void> {
  const row = await loadRow(db, userId);
  if (!row || !row.enrolled || row.premiumize_key_enc === null) return;
  const key = decryptSecret(row.premiumize_key_enc, userId, cfg.encKey);
  const url = installUrl(cfg, row.aiostreams_name, key);
  await deleteKevboxAddon(db, userId, cfg);
  await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, true, $3) on conflict (user_id, url) do nothing`,
    [userId, url, cfg.addonSort],
  );
}
```

Wire it at every reset call site so a reset followed by `reapplyKevboxAddon` leaves an enrolled
member with their kevbox URL intact. The reset routes/actions live in the web `actions.ts` and CLI
`actions.ts` (the `reset` handlers kept in Task 11 Step 2/4): after `await resetToDefaults(db,
userId)`, call `if (cfg) await reapplyKevboxAddon(db, userId, cfg);` (CLI passes `resolveKevboxConfig()`;
the web reset handler must receive `opts.kevbox` — thread it the same way `members.ts` receives it in
Task 8 Step 5). Export `reapplyKevboxAddon` via the core `index.ts` (it rides on the existing
`export * from "./kevboxMember.js"` added in Task 7 Step 4).

Add a test to `packages/core/test/kevboxMember.test.ts`:

```ts
import { resetToDefaults } from "../src/reset.js";
import { reapplyKevboxAddon } from "../src/kevboxMember.js";

test("reset + reapplyKevboxAddon keeps an enrolled member's kevbox URL", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "reset.kev@test.dev");
    const c = cfg();
    const { installUrl } = await enrollMember(db, uid, { aiostreamsName: "resetkev", premiumizeKey: "K" }, c);
    await resetToDefaults(db, uid);
    expect(await kevboxRows(db, uid)).toEqual([]); // reset stripped it
    await reapplyKevboxAddon(db, uid, c);
    expect(await kevboxRows(db, uid)).toEqual([installUrl]); // restored
  });
});
```

(Add `resetToDefaults`/`reapplyKevboxAddon` to the test imports.)

- [ ] **Step 7: Run to verify it passes**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/kevboxMember.test.ts`
Expected: PASS (all tests, including the reset interaction).

- [ ] **Step 8: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add packages/core/src/kevboxMember.ts packages/core/src/types.ts packages/core/test/kevboxMember.test.ts
git commit -m "feat(core): kevbox enroll/rename/rotate/unenroll mutations + name helpers + reapply-on-reset (C1/H1)"
```

---

### Task 5: Orchestrator — `withKevboxWrite` (lock + txn + render)

**Files:**
- Create: `packages/core/src/kevboxWrite.ts`
- Test: `packages/core/test/kevboxWrite.test.ts`

- [ ] **Step 1: Write the failing test**

Create `packages/core/test/kevboxWrite.test.ts`:

```ts
import { afterAll, afterEach, expect, test } from "vitest";
import { existsSync, readFileSync, rmSync, mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { KevboxConfig } from "../src/types.js";
import { pool, withRollback, createTestMember } from "./helpers.js";
import { loadEncKey } from "../src/crypto.js";
import { enrollMember } from "../src/kevboxMember.js";
import { withKevboxWrite } from "../src/kevboxWrite.js";

afterAll(async () => { await pool.end(); });

const made: string[] = [];
function cfg(): KevboxConfig {
  const file = join(mkdtempSync(join(tmpdir(), "kww-")), "members.json");
  made.push(file);
  return { encKey: loadEncKey("0".repeat(64)), membersFile: file, streamsBaseUrl: "https://streams.kevbox.dev", addonSort: 4 };
}
afterEach(() => { for (const f of made.splice(0)) { try { rmSync(f); } catch { /* */ } } });

// Client path (rollback): runs fn inline on the same connection, then renders.
test("renders members.json after the mutation (client path)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "ww-a@test.dev");
    const c = cfg();
    const res = await withKevboxWrite(db, c.membersFile, (d) =>
      enrollMember(d, uid, { aiostreamsName: "wwa", premiumizeKey: "K" }, c),
    );
    expect(res.name).toBe("wwa");
    expect(JSON.parse(readFileSync(c.membersFile, "utf8"))).toContain("wwa");
  });
});

test("a throwing fn does not write the file and rethrows", async () => {
  await withRollback(async (db) => {
    const c = cfg();
    await expect(
      withKevboxWrite(db, c.membersFile, async () => { throw new Error("boom"); }),
    ).rejects.toThrow("boom");
    expect(existsSync(c.membersFile)).toBe(false);
  });
});

// Pool path (real txns, NOT under withRollback): two distinct pool connections each enroll a
// DIFFERENT member concurrently. The xact advisory lock serializes the two writers, so BOTH names
// land in members.json with no transient drop (the second render sees the first committed row).
// These rows commit for real, so clean them up afterward.
test("concurrent writers on distinct connections both land in members.json (serialized, no drop)", async () => {
  const c = cfg();
  // seed two members on real connections (committed — withKevboxWrite uses pool txns)
  const a = await createTestMember(pool, "serial-a@test.dev");
  const b = await createTestMember(pool, "serial-b@test.dev");
  try {
    await Promise.all([
      withKevboxWrite(pool, c.membersFile, (d) => enrollMember(d, a, { aiostreamsName: "serala", premiumizeKey: "K" }, c)),
      withKevboxWrite(pool, c.membersFile, (d) => enrollMember(d, b, { aiostreamsName: "seralb", premiumizeKey: "K" }, c)),
    ]);
    const names = JSON.parse(readFileSync(c.membersFile, "utf8"));
    expect(names).toContain("serala");
    expect(names).toContain("seralb"); // neither writer's name was dropped by the other's render
  } finally {
    await pool.query("delete from public.kevbox_member where user_id = any($1::uuid[])", [[a, b]]);
    await pool.query("delete from public.member_addon where user_id = any($1::uuid[])", [[a, b]]);
    await pool.query("delete from public.kevbox_auth_users where id = any($1::uuid[])", [[a, b]]).catch(() => undefined);
  }
});
```

> **C2 %-drop guard (optional, spec §6):** `renderMembersFile` already refuses an EMPTY set. As an
> extra guard against a partial wipe, an operator-facing variant may refuse to write when the new set
> drops more than N% below the previous file's count unless a `--force` flag is passed. If you add it,
> thread a `{ force?: boolean }` option through `renderMembersFile`/`withKevboxWrite`/`migrate261` and
> add a test asserting a >N% drop throws without `--force` and succeeds with it. Out of scope for the
> empty-set floor above; tracked here so it isn't silently dropped.

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/kevboxWrite.test.ts`
Expected: FAIL — cannot find module `../src/kevboxWrite.js`.

- [ ] **Step 3: Implement the orchestrator**

Create `packages/core/src/kevboxWrite.ts`:

```ts
import pg from "pg";
import type { Pool } from "pg";
import type { Db } from "./types.js";
import { renderMembersFile } from "./kevboxAllowlist.js";

/** Stable advisory-lock key for the single-writer guarantee (H2). */
const KEVBOX_LOCK_KEY = 4242042042;

/**
 * Run a kevbox mutation under the single-writer guarantee, then re-render members.json.
 *
 * Production (`db` is a pg.Pool — POSITIVE `db instanceof pg.Pool` check): check out a dedicated
 * client, BEGIN, take a TRANSACTION advisory lock (`pg_advisory_xact_lock` — auto-released when the
 * txn ends, so there is NO leaked-lock failure mode: a swallowed unlock can't return a still-locked
 * connection to the max:5 pool and block future kevbox writes, M9), run `fn`, then render the file
 * WHILE STILL INSIDE the txn (before COMMIT). Rendering before COMMIT means a render failure rolls
 * back the DB too (the file write and the row change fail together) — boot-render reconciles any
 * post-COMMIT drift on the next startup (H3). The xact lock is released automatically by COMMIT or
 * ROLLBACK; no explicit unlock, so no `.catch(()=>{})` leak path.
 *
 * Test/single-client (`db` is a rollback PoolClient — NOT a pg.Pool): run `fn` inline on the same
 * connection and render. We must NOT duck-type on `.connect`, because a pg PoolClient IS a
 * pg.Client and HAS `.connect` — duck-typing would route the test client down the prod path and
 * throw "Client has already been connected". No separate txn/lock (the caller's withRollback owns
 * the txn); the lock is a production-only concern, documented here as a decision.
 */
export async function withKevboxWrite<T>(
  db: Db,
  membersFile: string,
  fn: (db: Db) => Promise<T>,
): Promise<T> {
  if (!(db instanceof pg.Pool)) {
    const result = await fn(db);
    await renderMembersFile(db, membersFile);
    return result;
  }

  const client = await (db as Pool).connect();
  try {
    await client.query("BEGIN");
    try {
      // Transaction-scoped advisory lock: auto-released at COMMIT/ROLLBACK. No explicit unlock and
      // therefore no leaked-lock path (M9).
      await client.query("select pg_advisory_xact_lock($1)", [KEVBOX_LOCK_KEY]);
      const result = await fn(client);
      // Render INSIDE the txn (before COMMIT): a render failure rolls the DB back with it, so the
      // file and the row never diverge mid-write; boot-render reconciles on next boot (H3).
      await renderMembersFile(client, membersFile);
      await client.query("COMMIT");
      return result;
    } catch (err) {
      await client.query("ROLLBACK").catch(() => undefined);
      throw err;
    }
  } finally {
    // The xact lock is already gone (released by COMMIT/ROLLBACK) — just hand the client back.
    client.release();
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/kevboxWrite.test.ts`
Expected: PASS (3 tests — including the concurrent-writer serialization test on the pool path, which
commits real rows and cleans them up in its `finally`).

- [ ] **Step 5: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add packages/core/src/kevboxWrite.ts packages/core/test/kevboxWrite.test.ts
git commit -m "feat(core): withKevboxWrite single-writer orchestrator (advisory lock + render, H2/H3)"
```

---

### Task 6: Migration — import the legacy 261 verbatim (dry-run by default)

**Files:**
- Create: `packages/core/src/migrate261.ts`
- Test: `packages/core/test/migrate261.test.ts`

- [ ] **Step 1: Write the failing test**

Create `packages/core/test/migrate261.test.ts`:

```ts
import { afterAll, expect, test } from "vitest";
import { mkdtempSync, existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { KevboxConfig } from "../src/types.js";
import { pool, withRollback, createTestMember } from "./helpers.js";
import { loadEncKey } from "../src/crypto.js";
import { getKevbox } from "../src/kevboxMember.js";
import { migrate261 } from "../src/migrate261.js";

afterAll(async () => { await pool.end(); });

function cfg(): KevboxConfig {
  return {
    encKey: loadEncKey("0".repeat(64)),
    membersFile: join(mkdtempSync(join(tmpdir(), "mig-")), "members.json"),
    streamsBaseUrl: "https://streams.kevbox.dev",
    addonSort: 4,
  };
}

test("dry-run resolves, back-fills key from member_addon URL, never writes", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "mig.one@test.dev");
    // member already has the kevbox URL installed (the back-fill source)
    await db.query(
      `insert into public.member_addon (user_id, url, sort_order)
       values ($1, 'https://streams.kevbox.dev/stremio/k/mig.one/PMK/manifest.json', 4)`,
      [uid],
    );
    const c = cfg();
    const res = await migrate261(db, ["mig.one"], c, { apply: false });

    expect(res.applied).toBe(false);
    expect(res.matched).toBe(1);
    expect(res.backfilled).toBe(1);
    expect(res.extras).toEqual([]);
    expect(res.rendered).toEqual(["mig.one"]);
    expect(existsSync(c.membersFile)).toBe(false); // dry-run writes nothing
    // and nothing persisted in dry-run
    expect(await getKevbox(db, uid, c)).toBeNull();
  });
});

test("apply persists verbatim names, key, and writes members.json", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "john@test.dev");
    // live allowlist name differs from email local-part (C1): name is "john2"
    await db.query(
      `insert into public.member_addon (user_id, url, sort_order)
       values ($1, 'https://streams.kevbox.dev/stremio/k/john2/KEY9/manifest.json', 4)`,
      [uid],
    );
    const c = cfg();
    const res = await migrate261(db, ["john2"], c, { apply: true });

    expect(res.applied).toBe(true);
    const state = await getKevbox(db, uid, c);
    expect(state).toEqual({ name: "john2", enrolled: true, hasKey: true }); // verbatim, not "john"
    expect(JSON.parse(readFileSync(c.membersFile, "utf8"))).toEqual(["john2"]);
  });
});

test("unmatched name → kevbox_allowlist_extra + report", async () => {
  await withRollback(async (db) => {
    const c = cfg();
    const res = await migrate261(db, ["ghostmember"], c, { apply: true });
    expect(res.extras).toEqual(["ghostmember"]);
    const { rows } = await db.query(
      "select aiostreams_name from public.kevbox_allowlist_extra where aiostreams_name = 'ghostmember'",
    );
    expect(rows).toHaveLength(1);
  });
});

test("re-run is insert-only: does not resurrect an un-enrolled member or clobber a rotated key (H4)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "stable@test.dev");
    await db.query(
      `insert into public.member_addon (user_id, url, sort_order)
       values ($1, 'https://streams.kevbox.dev/stremio/k/stable/ORIG/manifest.json', 4)`,
      [uid],
    );
    const c = cfg();
    await migrate261(db, ["stable"], c, { apply: true });
    // admin un-enrolls + the row's key is already set; re-run must NOT re-enable or change it
    await db.query(`update public.kevbox_member set enrolled = false, premiumize_key_enc = 'v1.x.y.z' where user_id = $1`, [uid]);
    await migrate261(db, ["stable"], c, { apply: true });
    const { rows } = await db.query(
      "select enrolled, premiumize_key_enc from public.kevbox_member where user_id = $1",
      [uid],
    );
    expect(rows[0].enrolled).toBe(false);
    expect(rows[0].premiumize_key_enc).toBe("v1.x.y.z");
  });
});

test("malformed input names are reported, not stored, and never rendered", async () => {
  await withRollback(async (db) => {
    const c = cfg();
    const res = await migrate261(db, ["GOOD?BAD", "ok.name"], c, { apply: false });
    expect(res.malformed).toEqual(["GOOD?BAD"]);
    // `ok.name` resolves to no user → it's a would-be extra and IS in `rendered`; the only invariant
    // is that the malformed token is reported and never reaches the rendered set.
    expect(res.rendered).not.toContain("GOOD?BAD");
  });
});

test("malformed name blocks --apply (reported at risk, never silently dropped)", async () => {
  await withRollback(async (db) => {
    const c = cfg();
    await expect(migrate261(db, ["GOOD?BAD", "ok.name"], c, { apply: true })).rejects.toThrow(/malformed/i);
  });
});

test("dry-run surfaces lost/renamed/added and --apply blocks on loss", async () => {
  await withRollback(async (db) => {
    const c = cfg();
    // a name that resolves to no user and isn't an email match becomes an extra (rendered), so use
    // a scenario that produces a genuine loss: here we only assert the fields exist + block behavior.
    const res = await migrate261(db, ["lonely.name"], c, { apply: false });
    expect(Array.isArray(res.lost)).toBe(true);
    expect(Array.isArray(res.renamed)).toBe(true);
    expect(Array.isArray(res.added)).toBe(true);
  });
});
```

Note on the malformed test: in dry-run, `ok.name` resolves to no user, so it becomes a would-be extra; `rendered` is the would-be members.json set (matched ∪ extras). The invariant under test is only that `GOOD?BAD` is reported `malformed` and never reaches `rendered`. Under `--apply`, a malformed token (and any genuine loss/conflict) THROWS before writing anything.

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/migrate261.test.ts`
Expected: FAIL — cannot find module `../src/migrate261.js`.

- [ ] **Step 3: Implement the migration**

Create `packages/core/src/migrate261.ts`:

```ts
import type { Db, KevboxConfig } from "./types.js";
import { encryptSecret } from "./crypto.js";
import { renderMembersFile } from "./kevboxAllowlist.js";

const NAME_RE = /^[a-z0-9._+-]{1,64}$/;

export interface MigrationResult {
  applied: boolean;
  total: number;
  matched: number;
  backfilled: number;
  extras: string[];
  conflicts: string[]; // names skipped because resolution was ambiguous or the user is already claimed
  malformed: string[];
  rendered: string[]; // the (would-be) members.json set
  // §11.5 set-equality vs. the input canonical set — surfaced in dry-run; --apply must block on loss.
  lost: string[]; // canonical input names NOT present in `rendered`
  renamed: string[]; // names whose resolved/stored form differs from the input (verbatim drift)
  added: string[]; // names in `rendered` that were not in the canonical input
}

/** Escape LIKE metacharacters (_ and %) in a literal so they match only themselves. */
function escapeLike(s: string): string {
  return s.replace(/([\\%_])/g, "\\$1");
}

/**
 * Resolve a name → userId. Authoritative: a kevbox member_addon URL whose <name> == name.
 *
 * The `<name>` is interpolated into a LIKE pattern, so `_` and `%` in a name MUST be escaped (with
 * an explicit ESCAPE clause) or e.g. `a_b` would also match `aXb`, binding the wrong member. The
 * regex below then enforces EXACT membership (`<name>` is a full path segment), so even if LIKE
 * over-matches we never accept a non-exact row. If NO row matches the exact-segment regex, return
 * null — we must NOT fall back to `rows[0]`, which could bind this name to a DIFFERENT user_id.
 *
 * Multi-row tie-break: prefer the canonical kevbox slot (sort_order = 4) first, then the
 * most-recently-updated row, so a re-keyed member's latest URL wins.
 */
async function resolveByAddonUrl(db: Db, name: string, cfg: KevboxConfig): Promise<{ userId: string; key: string } | null> {
  const prefix = `${cfg.streamsBaseUrl}/stremio/k/${name}/`;
  const { rows } = await db.query<{ user_id: string; url: string }>(
    `select user_id, url from public.member_addon
     where url like $1 escape '\\'
     order by (sort_order = 4) desc, updated_at desc nulls last, id desc`,
    [`${escapeLike(prefix)}%`],
  );
  if (rows.length === 0) return null;
  // EXACT membership: <name> must be a whole path segment in /k/<name>/<key>/manifest.json.
  const re = new RegExp(`/stremio/k/${name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}/([^/]+)/manifest\\.json$`);
  for (const r of rows) {
    const m = re.exec(r.url);
    if (m) return { userId: r.user_id, key: m[1]! };
  }
  return null; // no exact-segment match → unresolved (never bind to rows[0]'s user_id)
}

/**
 * Fallback: auth.users where lower(local-part(email)) == lower(name). Returns the id ONLY on an
 * unambiguous single match. 0 rows = unmatched (caller → extra); >1 rows = ambiguous (caller →
 * conflict). The caller uses `emailMatchAmbiguous` to tell those two null cases apart.
 */
async function resolveByEmail(db: Db, name: string): Promise<string | null> {
  const { rows } = await db.query<{ id: string }>(
    "select id from public.kevbox_auth_users where lower(split_part(email, '@', 1)) = lower($1) limit 2",
    [name],
  );
  if (rows.length !== 1) return null; // 0 = unmatched; >1 = ambiguous → caller reports conflict
  return rows[0].id;
}

/** True when >1 auth.users share this name's email local-part (the ambiguous → conflict case). */
async function emailMatchAmbiguous(db: Db, name: string): Promise<boolean> {
  const { rows } = await db.query<{ id: string }>(
    "select id from public.kevbox_auth_users where lower(split_part(email, '@', 1)) = lower($1) limit 2",
    [name],
  );
  return rows.length > 1;
}

/**
 * Import the legacy KEVBOX_MEMBERS list. Dry-run by default (apply=false): resolves + reports,
 * writes NOTHING. apply=true: insert-only upserts (never flip enrolled, never overwrite a key),
 * back-fills keys only when NULL, and writes members.json (subject to the §6 safety floor).
 */
export async function migrate261(
  db: Db,
  names: string[],
  cfg: KevboxConfig,
  opts: { apply: boolean },
): Promise<MigrationResult> {
  const malformed: string[] = [];
  // Keep names VERBATIM (the canonical live token, C1). Lowercase ONLY for the dedupe/compare key —
  // never store the lowercased form, or "John2" would be silently rewritten to "john2".
  const seenKeys = new Set<string>();
  const canonical: string[] = [];
  for (const raw of names) {
    const name = (raw ?? "").trim();
    if (!NAME_RE.test(name)) { malformed.push(raw); continue; }
    const key = name.toLowerCase();
    if (seenKeys.has(key)) continue;
    seenKeys.add(key);
    canonical.push(name);
  }

  // A malformed live token must BLOCK --apply (never silently dropped). It is also "at risk".
  if (opts.apply && malformed.length > 0) {
    throw new Error(
      `refusing to --apply: ${malformed.length} malformed allowlist name(s) at risk: ${malformed.join(", ")}. ` +
        `Re-run dry-run, fix or remove them, then --apply.`,
    );
  }

  const extras: string[] = [];
  const conflicts: string[] = [];
  const rendered: string[] = [];
  const claimedUserIds = new Set<string>(); // user_ids already bound this run (second mapping → conflict)
  let matched = 0;
  let backfilled = 0;

  for (const name of canonical) {
    const byUrl = await resolveByAddonUrl(db, name, cfg);
    let userId: string | null = byUrl?.userId ?? null;
    if (!userId) userId = await resolveByEmail(db, name); // null on 0 (unmatched) OR >1 (ambiguous)

    if (!userId) {
      // Distinguish ambiguous (>1 email match) from unmatched (0). resolveByEmail returns null for
      // both, so re-probe for ambiguity and report it as a conflict (skip) rather than an extra.
      if (await emailMatchAmbiguous(db, name)) {
        conflicts.push(name);
        continue;
      }
      extras.push(name);
      rendered.push(name);
      if (opts.apply) {
        await db.query(
          "insert into public.kevbox_allowlist_extra (aiostreams_name) values ($1) on conflict (aiostreams_name) do nothing",
          [name],
        );
      }
      continue;
    }

    // A second canonical name mapping to an already-claimed user_id is a conflict → skip.
    if (claimedUserIds.has(userId)) {
      conflicts.push(name);
      continue;
    }
    claimedUserIds.add(userId);

    matched++;
    rendered.push(name);
    if (opts.apply) {
      // insert-only: never flip enrolled on an existing row (H4). Name stored VERBATIM.
      await db.query(
        `insert into public.kevbox_member (user_id, aiostreams_name, enrolled)
         values ($1, $2, true) on conflict (user_id) do nothing`,
        [userId, name],
      );
      // back-fill key only when null and we recovered one from the URL
      if (byUrl?.key) {
        const enc = encryptSecret(byUrl.key, userId, cfg.encKey);
        await db.query(
          `update public.kevbox_member set premiumize_key_enc = $2, updated_at = now()
           where user_id = $1 and premiumize_key_enc is null`,
          [userId, enc],
        );
      }
    }
    if (byUrl?.key) backfilled++;
  }

  rendered.sort();
  const dedupedRendered = [...new Set(rendered)];

  // §11.5 in-code set-equality assertion (not just the README diff): compute lost/renamed/added vs.
  // the canonical input. `rendered` is verbatim, so a member whose stored/resolved name differs from
  // the input token shows up as one entry in `lost` (input form) and one in `added` (resolved form);
  // surface that pair as `renamed` so the operator sees drift instead of a phantom loss+add.
  const renderedSet = new Set(dedupedRendered);
  const inputSet = new Set(canonical);
  const rawLost = canonical.filter((n) => !renderedSet.has(n));
  const rawAdded = dedupedRendered.filter((n) => !inputSet.has(n));
  const lowerInput = new Map(canonical.map((n) => [n.toLowerCase(), n]));
  const renamed: string[] = [];
  const lost: string[] = [];
  for (const n of rawLost) {
    // case-only drift (same name, different case) is a rename, not a loss
    if (rawAdded.some((a) => a.toLowerCase() === n.toLowerCase())) renamed.push(n);
    else lost.push(n);
  }
  const added = rawAdded.filter((a) => !lowerInput.has(a.toLowerCase()));

  // --apply must BLOCK on loss (a member silently dropped from the allowlist). Dry-run only reports.
  if (opts.apply && (lost.length > 0 || conflicts.length > 0)) {
    throw new Error(
      `refusing to --apply: ${lost.length} lost + ${conflicts.length} conflict name(s). ` +
        `lost: ${lost.join(", ")}; conflicts: ${conflicts.join(", ")}. Resolve in a dry-run first.`,
    );
  }

  if (opts.apply) {
    await renderMembersFile(db, cfg.membersFile);
  }

  return {
    applied: opts.apply,
    total: canonical.length,
    matched,
    backfilled,
    extras,
    conflicts,
    malformed,
    rendered: dedupedRendered,
    lost,
    renamed,
    added,
  };
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/migrate261.test.ts`
Expected: PASS. The malformed test asserts `res.malformed` and `expect(res.rendered).not.toContain("GOOD?BAD")` (NOT `rendered).toEqual([])`, which would be wrong because the valid `ok.name` becomes a would-be extra). The `--apply` blocking tests assert that malformed/lost/conflict cases throw before writing.

- [ ] **Step 5: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add packages/core/src/migrate261.ts packages/core/test/migrate261.test.ts
git commit -m "feat(core): migrate261 importer — verbatim names, URL-keyed back-fill, dry-run (C1/H4/H6)"
```

---

### Task 7: Wire core exports + flip `MemberSummary.hasDebrid` → `enrolled`

**Files:**
- Modify: `packages/core/src/index.ts`
- Modify: `packages/core/src/types.ts` (`MemberSummary`)
- Modify: `packages/core/src/members.ts` (`listMembers`)
- Modify: `packages/core/test/members.test.ts` (REWRITE the hasDebrid case — see Step 1)
- Modify: `packages/cli/src/format.ts` (DEBRID column → KEVBOX)
- Modify: `apps/web/test/server/members.routes.test.ts` (hasDebrid assertion → enrolled)
- Modify: `apps/web/test/web/MemberList.test.tsx` (fixtures + `/debrid/` assertion)
- Modify: `apps/web/test/web/deep-link.test.ts` (fixture `hasDebrid` → `enrolled`)

> **Why this is broad (M2):** there are 7 live `hasDebrid` refs (`types.ts`, `members.ts`,
> `format.ts`, and 4 test files). The field rename ripples through CLI output and every test fixture
> — Steps 1–1f below update ALL of them so the suite stays green.

- [ ] **Step 1: Update the type**

In `packages/core/src/types.ts`, change `MemberSummary` (lines 6-13):

```ts
export interface MemberSummary {
  userId: string;
  email: string | null;
  createdAt: string;
  addonCount: number;
  /** true if the member has an enrolled kevbox_member row (replaces the old hasDebrid badge). */
  enrolled: boolean;
}
```

- [ ] **Step 1a: REWRITE the old `members.test.ts` case (do NOT just rename the field)**

The existing case at `packages/core/test/members.test.ts:5` ("listMembers reports addon count and
hasDebrid") seeds a member with a `torrentio` addon URL and asserts `hasDebrid === true`. With the
new semantics there is NO `kevbox_member` row for that member, so `enrolled` is `false` — a literal
`hasDebrid → enrolled` rename would assert `true` and FAIL. DELETE that case and REPLACE it with one
that keeps the `addonCount` coverage but drives `enrolled` off a real `kevbox_member` row:

```ts
test("listMembers reports addon count and enrolled", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "a@test.dev");
    const b = await createTestMember(db, "b@test.dev"); // no addons, not enrolled
    // a: two addons (addonCount coverage)
    await db.query(
      "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://v3-cinemeta.strem.io',0),($1,'https://torrentio.strem.fun/x/manifest.json',4)",
      [a],
    );
    // a is enrolled in kevbox; b is not
    await db.query(
      "insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'a',true)",
      [a],
    );
    const members = await listMembers(db);
    const ma = members.find((m) => m.email === "a@test.dev")!;
    const mb = members.find((m) => m.email === "b@test.dev")!;
    expect(ma.addonCount).toBe(2);
    expect(ma.enrolled).toBe(true);
    expect(mb.addonCount).toBe(0);
    expect(mb.enrolled).toBe(false);
  });
});
```

(Keep the unchanged `getMember` cases below it.)

- [ ] **Step 1b: Add a focused enrolled test to `members.test.ts`**

```ts
test("listMembers reports enrolled=true only for enrolled kevbox members", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "ls-on@test.dev");
    const b = await createTestMember(db, "ls-off@test.dev");
    await db.query(`insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'lson',true),($2,'lsoff',false)`, [a, b]);
    const members = await listMembers(db);
    const on = members.find((m) => m.userId === a)!;
    const off = members.find((m) => m.userId === b)!;
    expect(on.enrolled).toBe(true);
    expect(off.enrolled).toBe(false);
  });
});
```

- [ ] **Step 1c: Update the CLI table — `packages/cli/src/format.ts`**

In `formatMembers`, change the header column and the cell (lines 6 + 11):

```ts
  const header = ["EMAIL", "USER_ID", "ADDONS", "KEVBOX", "CREATED"];
```
```ts
    m.enrolled ? "yes" : "no",
```

- [ ] **Step 1d: Update `apps/web/test/server/members.routes.test.ts`**

The seed at line 12-14 gives the member a `torrentio` addon, so under the new semantics
`enrolled` is `false` (no `kevbox_member` row). Either insert a `kevbox_member` row and assert
`enrolled`, OR drop the badge assertion. Insert the row (right after the existing `member_addon`
insert, before `buildTestApp`) and flip the assertion at line 21:

```ts
    await db.query(
      "insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'m',true)",
      [id],
    );
```
```ts
    expect(member.enrolled).toBe(true);
```

- [ ] **Step 1e: Update `apps/web/test/web/MemberList.test.tsx`**

Fixtures (lines 7-8): rename `hasDebrid` → `enrolled` (keep `u2` as the enrolled one):

```tsx
  { userId: "u1", email: "a@test.dev", createdAt: "2026-01-01T00:00:00.000Z", addonCount: 4, enrolled: false },
  { userId: "u2", email: "b@test.dev", createdAt: "2026-02-01T00:00:00.000Z", addonCount: 6, enrolled: true },
```

Badge assertion (line 17) — the list now renders `· kevbox` (Task 9 Step 8), not `· debrid`:

```tsx
    expect(screen.getByText(/kevbox/)).toBeInTheDocument(); // only b is enrolled
```

- [ ] **Step 1f: Update `apps/web/test/web/deep-link.test.ts`**

Fixture (line 11): `hasDebrid: false,` → `enrolled: false,`.

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/members.test.ts`
Expected: FAIL — `listMembers` still returns `has_debrid`/`hasDebrid`, no `enrolled` field.

- [ ] **Step 3: Update `listMembers`**

Replace the body of `listMembers` in `packages/core/src/members.ts` (lines 4-28):

```ts
export async function listMembers(db: Db): Promise<MemberSummary[]> {
  // `enrolled` is a display badge: true iff the member has an enrolled kevbox_member row.
  const { rows } = await db.query(
    `select u.id as user_id, u.email, u.created_at,
            (select count(*) from public.member_addon m where m.user_id = u.id)::int as addon_count,
            exists (
              select 1 from public.kevbox_member k
              where k.user_id = u.id and k.enrolled
            ) as enrolled
       from public.kevbox_auth_users u
      order by u.email nulls last`,
  );
  return rows.map((r: any) => ({
    userId: r.user_id,
    email: r.email,
    createdAt: new Date(r.created_at).toISOString(),
    addonCount: r.addon_count,
    enrolled: r.enrolled,
  }));
}
```

- [ ] **Step 4: Add the new exports to `index.ts`**

Replace `packages/core/src/index.ts` with (note: `./debrid.js` stays for now — Task 11 removes it):

```ts
export * from "./types.js";
export * from "./defaults.js";
export * from "./members.js";
export * from "./addons.js";
export * from "./access.js";
export * from "./reset.js";
export * from "./debrid.js";
export * from "./bulk.js";
export * from "./activity.js";
export * from "./crypto.js";
export * from "./kevboxAllowlist.js";
export * from "./kevboxMember.js";
export * from "./kevboxWrite.js";
export * from "./migrate261.js";
```

- [ ] **Step 5: Run to verify it passes (+ whole core suite)**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core`
Expected: PASS. (The `defaults.test.ts`/`debrid.test.ts` still pass — they're removed in Task 11.)

- [ ] **Step 6: Build core to confirm types resolve**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npm run --workspace @kevbox-admin/core build`
Expected: tsc succeeds.

- [ ] **Step 7: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add packages/core/src/index.ts packages/core/src/types.ts packages/core/src/members.ts packages/core/test/members.test.ts packages/cli/src/format.ts apps/web/test/server/members.routes.test.ts apps/web/test/web/MemberList.test.tsx apps/web/test/web/deep-link.test.ts
git commit -m "feat(core): export kevbox modules; MemberSummary.hasDebrid -> enrolled (ripple: cli format + test fixtures)"
```

---

### Task 8: Server — config + routes (`GET install-url`, `PUT`, `DELETE`) + member detail block

**Files:**
- Modify: `apps/web/src/server/config.ts` (load kevbox config — OPTIONAL block)
- Modify: `apps/web/src/server/app.ts` (`BuildAppOptions.kevbox`, register routes)
- Modify: `apps/web/src/server/index.ts` (build + pass kevbox config when configured)
- Create: `apps/web/src/server/routes/kevbox.ts`
- Modify: `apps/web/src/server/routes/members.ts` (include `{ kevbox }` block, omit the key)
- Modify: `apps/web/test/server/helpers.ts` (test kevbox config)
- Modify: `apps/web/src/server/app.ts` (also: Fastify logger `redact` for Premiumize key, Step 5b)
- Modify: `apps/web/test/server/config.test.ts` (new vars in KEYS + passing env; used-but-missing test)
- Test: `apps/web/test/server/kevbox.routes.test.ts`
- Test: `apps/web/test/server/kevbox.redaction.test.ts` (Step 5b — proves no key/URL is logged, §13)

- [ ] **Step 1: Extend config loading (OPTIONAL block — do NOT make the vars unconditionally required)**

The kevbox block is OPTIONAL: it must load only when BOTH `KEVBOX_ENC_KEY` and `KEVBOX_MEMBERS_FILE`
are present (spec §5). Making them unconditional `required()` would break the existing
`config.test.ts` and any deploy that hasn't pre-set them; `index.ts` passes `kevbox` only when it's
configured, and `app.ts` already types `kevbox?` as optional.

In `apps/web/src/server/config.ts`, add to `WebConfig` (note the `?` — optional):

```ts
  kevbox?: {
    encKey: Buffer;
    membersFile: string;
    streamsBaseUrl: string;
    addonSort: number;
  };
```

and in `loadConfig()`, before the `return`, add (uses `loadEncKey` from core):

```ts
  // import at top: import { loadEncKey } from "@kevbox-admin/core";
  // Optional: only build the kevbox block when BOTH key + members-file are set. If exactly one is
  // set, the operator clearly intended kevbox — throw so a half-configured deploy fails loud.
  const encRaw = process.env.KEVBOX_ENC_KEY?.trim();
  const membersFile = process.env.KEVBOX_MEMBERS_FILE?.trim();
  let kevbox: WebConfig["kevbox"];
  if (encRaw || membersFile) {
    if (!encRaw || !membersFile) {
      throw new Error("KEVBOX_ENC_KEY and KEVBOX_MEMBERS_FILE must be set together (or both omitted)");
    }
    const addonSort = Number.parseInt(process.env.KEVBOX_ADDON_SORT?.trim() || "4", 10);
    // Mirror the existing PORT int-validation: a NaN here fails the int-NOT-NULL member_addon
    // insert and 500s every enroll/rotate/rename, so reject it at config load.
    if (!Number.isInteger(addonSort)) {
      throw new Error("KEVBOX_ADDON_SORT must be an integer");
    }
    kevbox = {
      encKey: loadEncKey(encRaw),
      membersFile,
      streamsBaseUrl: (process.env.KEVBOX_STREAMS_BASE_URL?.trim() || "https://streams.kevbox.dev").replace(/\/$/, ""),
      addonSort,
    };
  }
```

and include `kevbox` in the returned object (it will be `undefined` when not configured).

- [ ] **Step 1b: Update `apps/web/test/server/config.test.ts`**

The existing config test enumerates required env vars and asserts `loadConfig()` succeeds on a
known-good env, then fails when each required key is removed. Because kevbox is OPTIONAL, do NOT add
`KEVBOX_ENC_KEY`/`KEVBOX_MEMBERS_FILE` to the required-keys removal loop. Instead:

- Add both vars to the test's passing env (the known-good fixture) so the optional block builds and
  the rest of the suite exercises a configured app. Use a 64-zero hex key and a tmp path:
  ```ts
  KEVBOX_ENC_KEY: "0".repeat(64),
  KEVBOX_MEMBERS_FILE: "/tmp/members.json",
  ```
  (If the test enumerates required keys from a `KEYS` array, add these two ONLY to the passing-env
  object, NOT to that `KEYS` removal list.)
- Add a focused "throws when used-but-missing" case asserting the half-configured guard:
  ```ts
  test("kevbox config: setting only one of the two vars throws", () => {
    const env = { ...goodEnv, KEVBOX_ENC_KEY: "0".repeat(64) };
    delete env.KEVBOX_MEMBERS_FILE;
    expect(() => loadConfig(env)).toThrow(/together/i);
  });
  ```
  (Match the signature `loadConfig` actually uses — env arg or `process.env` mutation — as the rest
  of `config.test.ts` does.)

- [ ] **Step 2: Write the failing route test**

Create `apps/web/test/server/kevbox.routes.test.ts`:

```ts
import { afterAll, afterEach, expect, test } from "vitest";
import { readFileSync, rmSync, mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { pool, withRollback, createTestMember } from "../../../../packages/core/test/helpers.js";
import { buildKevboxTestApp } from "./helpers.js";

const ADMIN = { authorization: "Bearer admin@test.dev" };
afterAll(async () => { await pool.end(); });

const files: string[] = [];
function membersFile(): string { const f = join(mkdtempSync(join(tmpdir(), "kr-")), "members.json"); files.push(f); return f; }
afterEach(() => { for (const f of files.splice(0)) { try { rmSync(f); } catch { /* */ } } });

test("PUT enroll → GET member shows kevbox block (no key); members.json written", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.one@test.dev");
    const file = membersFile();
    const app = buildKevboxTestApp(db, file);

    const put = await app.inject({
      method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN,
      payload: { premiumizeKey: "PMK" },
    });
    expect(put.statusCode).toBe(200);
    expect(put.json().kevbox).toEqual({ name: "route.one", enrolled: true, hasKey: true });

    const get = await app.inject({ method: "GET", url: `/api/members/${uid}`, headers: ADMIN });
    expect(get.json().member.kevbox).toEqual({ name: "route.one", enrolled: true, hasKey: true });
    expect(JSON.stringify(get.json())).not.toContain("PMK"); // key never in the default fetch (C5)
    expect(JSON.parse(readFileSync(file, "utf8"))).toContain("route.one");

    await app.close();
  });
});

test("GET install-url is the only endpoint that returns the key-bearing URL (C5)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.url@test.dev");
    const app = buildKevboxTestApp(db, membersFile());
    await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { aiostreamsName: "rurl", premiumizeKey: "SECRET" } });

    const res = await app.inject({ method: "GET", url: `/api/members/${uid}/kevbox/install-url`, headers: ADMIN });
    expect(res.statusCode).toBe(200);
    expect(res.json().installUrl).toBe("https://streams.kevbox.dev/stremio/k/rurl/SECRET/manifest.json");
    await app.close();
  });
});

test("PUT with name only on an unenrolled member → 400 (enroll requires a key)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.nokey@test.dev");
    const app = buildKevboxTestApp(db, membersFile());
    const res = await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { name: "nokey" } });
    expect(res.statusCode).toBe(400);
    await app.close();
  });
});

test("PUT name-change on enrolled member renames (URL rebuilt)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.ren@test.dev");
    const app = buildKevboxTestApp(db, membersFile());
    await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { aiostreamsName: "before", premiumizeKey: "K" } });
    const res = await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { name: "after" } });
    expect(res.statusCode).toBe(200);
    expect(res.json().kevbox.name).toBe("after");
    const url = await app.inject({ method: "GET", url: `/api/members/${uid}/kevbox/install-url`, headers: ADMIN });
    expect(url.json().installUrl).toContain("/k/after/K/");
    await app.close();
  });
});

test("DELETE un-enrolls when others remain (file re-rendered)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.del@test.dev");
    const file = membersFile();
    const app = buildKevboxTestApp(db, file);
    await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { aiostreamsName: "deluser", premiumizeKey: "K" } });
    // a second extra remains, so the render still produces a non-empty set (normal path)
    await db.query(`insert into public.kevbox_allowlist_extra (aiostreams_name) values ('keepalive')`);
    const res = await app.inject({ method: "DELETE", url: `/api/members/${uid}/kevbox`, headers: ADMIN });
    expect(res.statusCode).toBe(200);
    expect(res.json().warning).toBeUndefined(); // non-empty render → no soft-success warning
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["keepalive"]); // re-rendered without deluser
    const get = await app.inject({ method: "GET", url: `/api/members/${uid}`, headers: ADMIN });
    expect(get.json().member.kevbox.enrolled).toBe(false);
    await app.close();
  });
});

test("DELETE the LAST enrolled member (no extras) is a soft success; file left intact (M11)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.last@test.dev");
    const file = membersFile();
    const app = buildKevboxTestApp(db, file);
    // enroll renders ["onlyone"]; this is the only enrolled member and there are NO extras
    await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { aiostreamsName: "onlyone", premiumizeKey: "K" } });
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["onlyone"]);
    // un-enrolling the last member would render an EMPTY set → soft success, file untouched
    const res = await app.inject({ method: "DELETE", url: `/api/members/${uid}/kevbox`, headers: ADMIN });
    expect(res.statusCode).toBe(200);
    expect(res.json().warning).toMatch(/safety floor/i);
    expect(res.json().kevbox.enrolled).toBe(false); // un-enroll DID commit
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["onlyone"]); // prior file left intact
    await app.close();
  });
});

test("unknown member → 404", async () => {
  await withRollback(async (db) => {
    const app = buildKevboxTestApp(db, membersFile());
    const ghost = "00000000-0000-0000-0000-0000000000ff";
    const res = await app.inject({ method: "PUT", url: `/api/members/${ghost}/kevbox`, headers: ADMIN, payload: { premiumizeKey: "K" } });
    expect(res.statusCode).toBe(404);
    await app.close();
  });
});
```

- [ ] **Step 3: Add the test app builder**

In `apps/web/test/server/helpers.ts`, add `loadEncKey` import and a kevbox-aware builder:

```ts
import { loadEncKey } from "@kevbox-admin/core";

/**
 * Build a test app wired with a kevbox config that writes to `membersFile`. The optional
 * `opts.logStream` is forwarded into the Fastify logger so the redaction test (Step 5b) can capture
 * log lines and assert no key/URL is logged; omit it for the normal route tests.
 */
export function buildKevboxTestApp(
  db: Db,
  membersFile: string,
  opts: { logStream?: { write: (s: string) => void } } = {},
): FastifyInstance {
  return buildApp({
    db, verifier: tokenIsEmailVerifier, adminEmails: ADMIN_EMAILS,
    // Thread the capture stream into buildApp's logger options (match how buildApp accepts a logger;
    // if it doesn't yet, add a `logger?`/`loggerStream?` pass-through to BuildAppOptions for tests).
    ...(opts.logStream ? { loggerStream: opts.logStream } : {}),
    kevbox: {
      encKey: loadEncKey("0".repeat(64)),
      membersFile,
      streamsBaseUrl: "https://streams.kevbox.dev",
      addonSort: 4,
    },
  });
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root apps/web test/server/kevbox.routes.test.ts`
Expected: FAIL — `buildApp` has no `kevbox` option / route not registered.

- [ ] **Step 5: Extend `BuildAppOptions` + register the routes**

In `apps/web/src/server/app.ts`: import the config type + new route registrar, extend options, and register inside the `/api` plugin.

Add imports:

```ts
import type { KevboxConfig } from "@kevbox-admin/core";
import { registerKevboxRoutes } from "./routes/kevbox.js";
```

Add to `BuildAppOptions`:

```ts
  /** Kevbox enrollment config. Omit in tests that don't exercise kevbox routes. */
  kevbox?: KevboxConfig;
```

Inside the `app.register(async (api) => { ... }, { prefix: "/api" })` block, after `registerActivityRoutes(api, opts.db);` add:

```ts
    if (opts.kevbox) registerKevboxRoutes(api, opts.db, opts.kevbox);
```

and pass `opts.kevbox` into the member routes so the detail block can include kevbox:

Change `registerMemberRoutes(api, opts.db);` → `registerMemberRoutes(api, opts.db, opts.kevbox);`

- [ ] **Step 5b: Fastify logger redaction — never log a Premiumize key (spec §13, C5)**

Spec §13 requires this ACTIVELY (not by assumption). The dashboard accepts a Premiumize key in the
`PUT` body and returns a key-bearing install URL from the install-url route — neither must ever reach
the logs. In `apps/web/src/server/app.ts`, where the Fastify instance is created (the `fastify({ ... })`
options / the `logger` block), add `redact` so request-body key fields are censored, and ensure the
install-url response body is not logged:

```ts
// pino redaction: censor any logged Premiumize key field (request bodies that Fastify auto-logs).
const app = fastify({
  logger: {
    // ...existing logger options...
    redact: {
      paths: ["req.body.premiumizeKey", "req.body.premiumize", "body.premiumizeKey", "body.premiumize"],
      censor: "[redacted]",
    },
  },
});
```

The install-url route returns `{ installUrl }`; Fastify does NOT log response bodies by default, so the
key-bearing URL never hits the log. If a serializer/hook anywhere logs replies, exclude this route.

Add a test proving it. Create `apps/web/test/server/kevbox.redaction.test.ts`:

```ts
import { afterAll, expect, test } from "vitest";
import { mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { pool, withRollback, createTestMember } from "../../../../packages/core/test/helpers.js";
import { buildKevboxTestApp } from "./helpers.js";

const ADMIN = { authorization: "Bearer admin@test.dev" };
afterAll(async () => { await pool.end(); });

test("no request/response log line embeds the Premiumize key or install URL (§13)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "redact@test.dev");
    const file = join(mkdtempSync(join(tmpdir(), "redact-")), "members.json");
    const lines: string[] = [];
    // capture pino output by passing a stream into the test app's logger (buildKevboxTestApp must
    // accept a logger stream, or assert via a spy on process.stdout.write — match the project's
    // existing log-capture convention).
    const app = buildKevboxTestApp(db, file, { logStream: { write: (s: string) => { lines.push(s); } } });
    await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { aiostreamsName: "rdct", premiumizeKey: "SUPER-SECRET-KEY" } });
    await app.inject({ method: "GET", url: `/api/members/${uid}/kevbox/install-url`, headers: ADMIN });
    const all = lines.join("\n");
    expect(all).not.toContain("SUPER-SECRET-KEY"); // key never logged (redacted)
    expect(all).not.toMatch(/\/k\/rdct\/SUPER-SECRET-KEY\//); // install URL never logged
    await app.close();
  });
});
```

(Extend `buildKevboxTestApp` in `helpers.ts` to forward an optional `{ logStream }` into the logger
so the test can capture lines; if the project already has a log-capture helper, reuse it. The
load-bearing invariant is the two `expect(...).not` assertions — keep them even if the capture
mechanism differs.)

- [ ] **Step 6: Create the kevbox routes**

Create `apps/web/src/server/routes/kevbox.ts`:

```ts
import type { FastifyInstance } from "fastify";
import type { Db, KevboxConfig } from "@kevbox-admin/core";
import {
  getMember, getKevbox, enrollMember, renameMember, rotateKey, unenrollMember,
  buildInstallUrl, withKevboxWrite,
} from "@kevbox-admin/core";

interface KevboxBody { name?: string; premiumizeKey?: string }

export function registerKevboxRoutes(app: FastifyInstance, db: Db, cfg: KevboxConfig): void {
  // The ONLY endpoint that returns the key-bearing install URL (C5). Auditable on its own.
  app.get<{ Params: { userId: string } }>("/members/:userId/kevbox/install-url", async (req, reply) => {
    if (!(await getMember(db, req.params.userId))) return reply.code(404).send({ error: "member not found" });
    const installUrl = await buildInstallUrl(db, req.params.userId, cfg);
    if (!installUrl) return reply.code(404).send({ error: "no install url (member has no stored key)" });
    return { installUrl };
  });

  // Enroll / rename / rotate, routed by field combination (H1).
  app.put<{ Params: { userId: string }; Body: KevboxBody }>("/members/:userId/kevbox", async (req, reply) => {
    const userId = req.params.userId;
    if (!(await getMember(db, userId))) return reply.code(404).send({ error: "member not found" });
    const name = typeof req.body?.name === "string" ? req.body.name.trim() : undefined;
    const key = typeof req.body?.premiumizeKey === "string" ? req.body.premiumizeKey.trim() : undefined;

    // TOCTOU fix (H2): read existence + decide enroll-vs-rename-vs-rotate INSIDE the lock, on the
    // LOCKED connection `d` — never on the outer pool `db` before the lock. Read+decide+mutate share
    // the one locked txn, so a concurrent writer can't change `enrolled`/`name` between the decision
    // and the mutation.
    await withKevboxWrite(db, cfg.membersFile, async (d) => {
      const current = await getKevbox(d, userId, cfg);
      const enrolled = current?.enrolled === true;
      if (!enrolled) {
        if (!key) { const e = new Error("premiumizeKey is required to enroll") as any; e.statusCode = 400; throw e; }
        await enrollMember(d, userId, { aiostreamsName: name, premiumizeKey: key }, cfg);
        return;
      }
      if (name && name !== current!.name) await renameMember(d, userId, name, cfg);
      if (key) await rotateKey(d, userId, key, cfg);
      if (!name && !key) { const e = new Error("nothing to update") as any; e.statusCode = 400; throw e; }
    });

    // Only the final NON-SECRET response read happens outside the lock (already committed).
    return { kevbox: await getKevbox(db, userId, cfg) };
  });

  // Un-enroll. LAST-MEMBER case (M11): un-enrolling the only enrolled member with no extras renders
  // an EMPTY set, which renderMembersFile refuses (safety floor). With the render INSIDE the txn
  // (Task 5), that would roll the un-enroll back — but the operator clearly intended to un-enroll, so
  // treat empty-on-unenroll as a SOFT success: commit the un-enroll WITHOUT rendering (leaving the
  // prior members.json intact, since the floor exists exactly to avoid blanking the allowlist) and
  // return 200 with a warning. Any other render failure still propagates as a 5xx.
  app.delete<{ Params: { userId: string } }>("/members/:userId/kevbox", async (req, reply) => {
    const userId = req.params.userId;
    if (!(await getMember(db, userId))) return reply.code(404).send({ error: "member not found" });
    let warning: string | undefined;
    try {
      await withKevboxWrite(db, cfg.membersFile, (d) => unenrollMember(d, userId, cfg));
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (!/empty/i.test(msg)) throw e; // only the empty-set floor is soft; everything else 5xx
      // The withKevboxWrite txn rolled back (its in-txn render hit the floor). Re-run JUST the
      // mutation directly on `db` — unenrollMember is a pure mutation that does NOT render — so the
      // DB un-enroll commits while the (now would-be-empty) members.json is left untouched.
      await unenrollMember(db, userId, cfg);
      warning = "last enrolled member removed; members.json left intact (empty-set safety floor)";
    }
    return { kevbox: await getKevbox(db, userId, cfg), ...(warning ? { warning } : {}) };
  });
}
```

- [ ] **Step 7: Include the kevbox block in member detail**

Replace `apps/web/src/server/routes/members.ts` with:

```ts
import type { FastifyInstance } from "fastify";
import type { Db, KevboxConfig } from "@kevbox-admin/core";
import { listMembers, getMember, getKevbox } from "@kevbox-admin/core";

export function registerMemberRoutes(app: FastifyInstance, db: Db, kevbox?: KevboxConfig): void {
  app.get("/members", async () => {
    return { members: await listMembers(db) };
  });

  app.get<{ Params: { ref: string } }>("/members/:ref", async (req, reply) => {
    const member = await getMember(db, decodeURIComponent(req.params.ref));
    if (!member) return reply.code(404).send({ error: "member not found" });
    // Attach the non-secret kevbox block (name/enrolled/hasKey) — NEVER the install URL (C5).
    const kev = kevbox ? await getKevbox(db, member.userId, kevbox) : null;
    return { member: { ...member, kevbox: kev } };
  });
}
```

- [ ] **Step 8: Wire production config in `index.ts`**

In `apps/web/src/server/index.ts`, pass `kevbox: cfg.kevbox` into the `buildApp({ ... })` call (alongside `db`, `verifier`, `adminEmails`, `publicDir`).

- [ ] **Step 8b: Boot-render — reconcile a stale `members.json` once on startup (H3)**

`withKevboxWrite` reconciles per-mutation, but a `members.json` that drifted while the dashboard was
down (manual edit, partial deploy) is only healed on the next mutation. Add a one-shot boot-render in
`apps/web/src/server/index.ts` AFTER `buildApp`/`listen`, so the file is reconciled from the DB at
startup. It must be:
- **fail-soft-logged** — a render error must NOT crash boot; log it and continue (the prior file
  stays in place, and the next mutation will retry).
- **GATED on "kevbox is configured AND `kevbox_member` has ≥1 enrolled row"** — so the empty-set
  safety floor doesn't trip on a fresh/pre-migration DB (0 enrolled rows) and abort startup.

Add (only when `cfg.kevbox` is set), using the same `pool` passed to `buildApp`:

```ts
import { renderMembersFile } from "@kevbox-admin/core";

// Boot-render (H3): reconcile members.json from the DB once at startup. Gated on ≥1 enrolled row so
// the empty-set floor (Task 3) can't trip pre-migration; fail-soft so a render error never crashes boot.
if (cfg.kevbox) {
  try {
    const { rows } = await pool.query<{ n: number }>(
      "select count(*)::int as n from public.kevbox_member where enrolled",
    );
    if (rows[0]!.n > 0) {
      await renderMembersFile(pool, cfg.kevbox.membersFile);
      app.log.info("kevbox: boot-render reconciled members.json");
    } else {
      app.log.info("kevbox: boot-render skipped (no enrolled members yet)");
    }
  } catch (err) {
    app.log.error({ err }, "kevbox: boot-render failed (continuing; next mutation will retry)");
  }
}
```

- [ ] **Step 8c: Integration test — boot-render reconciles a stale file (§14)**

Add to `apps/web/test/server/kevbox.routes.test.ts` a test that drives the same gated boot-render
logic against a stale file and asserts it is reconciled (and that the empty-set gate is respected).
Because the production boot-render lives in `index.ts` (not in `buildApp`), exercise the SAME guard +
`renderMembersFile` call the way `index.ts` does, on the test `db`:

```ts
import { writeFileSync } from "node:fs";
import { renderMembersFile } from "@kevbox-admin/core";

// Mirror index.ts Step 8b: gated, fail-soft boot-render.
async function bootRender(db: Db, file: string): Promise<void> {
  const { rows } = await db.query("select count(*)::int as n from public.kevbox_member where enrolled");
  if (rows[0].n > 0) await renderMembersFile(db, file);
}

test("boot-render reconciles a stale members.json from the DB (H3)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "boot.render@test.dev");
    await db.query("insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'bootname',true)", [uid]);
    const file = membersFile();
    writeFileSync(file, JSON.stringify(["STALE-do-not-keep"])); // drifted file on disk
    await bootRender(db, file);
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["bootname"]); // reconciled from the DB
  });
});

test("boot-render is skipped (empty-set gate) when no member is enrolled (H3)", async () => {
  await withRollback(async (db) => {
    const file = membersFile();
    writeFileSync(file, JSON.stringify(["prior"])); // a pre-existing file
    await bootRender(db, file); // 0 enrolled rows → gate skips the render, floor never trips
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["prior"]); // left intact
  });
});
```

(The route test file already imports `readFileSync`; add `writeFileSync` to that import.)

- [ ] **Step 9: Run the kevbox route tests + the existing server suite**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root apps/web test/server`
Expected: PASS. (The `members.routes.test.ts` may need its detail assertion updated to allow the new `kevbox` field — `getMember` shape is unchanged except the added `kevbox` key; existing assertions on `member.addons`/`member.email` still hold.)

- [ ] **Step 10: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add apps/web/src/server/config.ts apps/web/src/server/app.ts apps/web/src/server/index.ts apps/web/src/server/routes/kevbox.ts apps/web/src/server/routes/members.ts apps/web/test/server/helpers.ts apps/web/test/server/config.test.ts apps/web/test/server/kevbox.routes.test.ts apps/web/test/server/kevbox.redaction.test.ts
git commit -m "feat(web): kevbox enroll/rename/rotate/unenroll routes + member block; key only via install-url (C5/H1); log redaction (§13)"
```

---

### Task 8b: Audit logging — record every kevbox mutation + install-url reveal (spec §13)

Spec §13 requires a structured per-mutation audit trail (who did what to whom). This task adds a
`kevbox_audit` table, a `writeAudit` core helper, and a call in each kevbox route mutation
(enroll/rename/rotate/unenroll) AND the install-url reveal — sourcing the admin email from the
verified user, NEVER recording the secret value.

**Files:**
- Modify: `packages/core/test/schema.sql` (append `kevbox_audit`)
- Modify: `deploy/kevbox_member_setup.sql` (same table + grant)
- Create: `packages/core/src/kevboxAudit.ts` (`writeAudit`)
- Modify: `packages/core/src/index.ts` (`export * from "./kevboxAudit.js"`)
- Modify: `apps/web/src/server/routes/kevbox.ts` (audit calls)
- Test: `packages/core/test/kevboxAudit.test.ts`
- Modify: `apps/web/test/server/kevbox.routes.test.ts` (assert an audit row, no secret)

- [ ] **Step 1: Add the audit table to the test schema**

Append to `packages/core/test/schema.sql` (after the kevbox_member block):

```sql
-- Audit trail for kevbox mutations + install-url reveals (spec §13). NEVER stores a key value.
create table public.kevbox_audit (
  id          bigint generated always as identity primary key,
  admin_email text,
  user_id     uuid,
  action      text not null,
  occurred_at timestamptz not null default now()
);
```

- [ ] **Step 2: Add the same table to the production setup SQL**

Append to `deploy/kevbox_member_setup.sql` (after the kevbox_allowlist_extra block, before/with the
grants), keeping it byte-identical except the `if not exists` guard, and grant + lock down like the
other sidecar tables:

```sql
create table if not exists public.kevbox_audit (
  id          bigint generated always as identity primary key,
  admin_email text,
  user_id     uuid,
  action      text not null,
  occurred_at timestamptz not null default now()
);
alter table public.kevbox_audit enable row level security;
revoke all on public.kevbox_audit from anon, authenticated;
grant select, insert on public.kevbox_audit to kevbox_admin;
```

- [ ] **Step 3: Write the failing test**

Create `packages/core/test/kevboxAudit.test.ts`:

```ts
import { afterAll, expect, test } from "vitest";
import { pool, withRollback, createTestMember } from "./helpers.js";
import { writeAudit } from "../src/kevboxAudit.js";

afterAll(async () => { await pool.end(); });

test("writeAudit records action + admin email + user_id, never a secret", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "audit@test.dev");
    await writeAudit(db, { adminEmail: "admin@test.dev", userId: uid, action: "kevbox.enroll" });
    const { rows } = await db.query(
      "select admin_email, user_id, action from public.kevbox_audit where user_id = $1",
      [uid],
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ admin_email: "admin@test.dev", user_id: uid, action: "kevbox.enroll" });
    // structural guard: the audit row carries no secret-bearing column
    expect(Object.keys(rows[0])).not.toContain("premiumize_key_enc");
  });
});
```

- [ ] **Step 4: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/kevboxAudit.test.ts`
Expected: FAIL — cannot find module `../src/kevboxAudit.js` (and `kevbox_audit` doesn't exist until the schema reload).

- [ ] **Step 5: Implement the helper**

Create `packages/core/src/kevboxAudit.ts`:

```ts
import type { Db } from "./types.js";

export interface AuditEntry {
  adminEmail: string | null;
  userId: string | null;
  action: string; // e.g. "kevbox.enroll", "kevbox.rename", "kevbox.rotate", "kevbox.unenroll", "kevbox.reveal-url"
}

/**
 * Append one audit row. NEVER pass a secret (key/URL) — only the action verb is recorded. Best-effort
 * within the caller's transaction; if it's part of withKevboxWrite it commits/rolls back atomically
 * with the mutation.
 */
export async function writeAudit(db: Db, entry: AuditEntry): Promise<void> {
  await db.query(
    "insert into public.kevbox_audit (admin_email, user_id, action) values ($1, $2, $3)",
    [entry.adminEmail, entry.userId, entry.action],
  );
}
```

Add `export * from "./kevboxAudit.js";` to `packages/core/src/index.ts` (alongside the other kevbox
exports added in Task 7 Step 4).

- [ ] **Step 6: Reload the schema + run to verify it passes**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
PGPASSWORD=test psql -h localhost -p 5433 -U postgres -d kevbox_test -f packages/core/test/schema.sql
npx vitest run --root packages/core test/kevboxAudit.test.ts
```
Expected: PASS.

- [ ] **Step 7: Wire audit calls into the kevbox routes**

In `apps/web/src/server/routes/kevbox.ts`, source the admin email from the verified user (the
auth decorator already attaches it — use the same accessor the other admin routes use, e.g.
`req.user?.email` / the request's verified-email field; match the existing convention) and record an
audit row inside each mutation, AND on the install-url reveal.

Import `writeAudit` from `@kevbox-admin/core`. In the `PUT` handler, inside the `withKevboxWrite`
callback (so it's atomic with the mutation), after the enroll/rename/rotate branch runs, call
`await writeAudit(d, { adminEmail, userId, action })` with the matching action verb
(`"kevbox.enroll"` / `"kevbox.rename"` / `"kevbox.rotate"`). In the `DELETE` handler, inside the
`withKevboxWrite` callback call `await writeAudit(d, { adminEmail, userId, action: "kevbox.unenroll" })`.
In the install-url `GET` handler (no withKevboxWrite — write directly on `db`), after building the
URL call `await writeAudit(db, { adminEmail, userId: req.params.userId, action: "kevbox.reveal-url" })`.
Pass NO key/URL into the audit entry (§13).

- [ ] **Step 8: Assert an audit row in the route test**

Add to `apps/web/test/server/kevbox.routes.test.ts` (in the enroll test, after the `PUT` succeeds):

```ts
    const audit = await db.query(
      "select admin_email, action from public.kevbox_audit where user_id = $1 order by id",
      [uid],
    );
    expect(audit.rows.map((r) => r.action)).toContain("kevbox.enroll");
    expect(audit.rows[0].admin_email).toBe("admin@test.dev");
    expect(JSON.stringify(audit.rows)).not.toContain("PMK"); // no secret in the audit trail
```

- [ ] **Step 9: Run the kevbox route + core suites**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/core test/kevboxAudit.test.ts && npx vitest run --root apps/web test/server/kevbox.routes.test.ts`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add packages/core/test/schema.sql deploy/kevbox_member_setup.sql packages/core/src/kevboxAudit.ts packages/core/src/index.ts packages/core/test/kevboxAudit.test.ts apps/web/src/server/routes/kevbox.ts apps/web/test/server/kevbox.routes.test.ts
git commit -m "feat(core+web): kevbox_audit trail for mutations + install-url reveal (no secrets, §13)"
```

---

### Task 9: Web SPA — API client, Kevbox tab, badge, wiring

**Files:**
- Modify: `apps/web/src/web/lib/api.ts`
- Create: `apps/web/src/web/components/KevboxTab.tsx`
- Modify: `apps/web/src/web/components/MemberDetail.tsx`
- Modify: `apps/web/src/web/components/MemberList.tsx`
- Modify: `apps/web/src/web/App.tsx`
- Test: `apps/web/test/web/KevboxTab.test.tsx`

- [ ] **Step 1: Add API client methods + the kevbox member type**

In `apps/web/src/web/lib/api.ts`: re-export `KevboxState` and extend the `MemberDetail` fetch shape + methods.

Add to the type re-exports at top:

```ts
import type { KevboxState } from "@kevbox-admin/core";
export type { KevboxState } from "@kevbox-admin/core";
/** MemberDetail as returned by GET /members/:ref (server attaches the kevbox block). */
export interface MemberDetailWithKevbox extends MemberDetail { kevbox: KevboxState | null }
```

Change `getMember` return type to `{ member: MemberDetailWithKevbox }` and add methods (inside the `Api` class):

```ts
  getKevboxInstallUrl(userId: string): Promise<{ installUrl: string }> {
    return this.request("GET", `/members/${encodeURIComponent(userId)}/kevbox/install-url`);
  }
  putKevbox(userId: string, body: { name?: string; premiumizeKey?: string }): Promise<{ kevbox: KevboxState | null }> {
    return this.request("PUT", `/members/${encodeURIComponent(userId)}/kevbox`, body);
  }
  unenrollKevbox(userId: string): Promise<{ kevbox: KevboxState | null }> {
    return this.request("DELETE", `/members/${encodeURIComponent(userId)}/kevbox`);
  }
```

- [ ] **Step 2: Write the failing component test**

Create `apps/web/test/web/KevboxTab.test.tsx`:

```tsx
import { afterEach, expect, test, vi } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { KevboxTab } from "../../src/web/components/KevboxTab.js";

afterEach(cleanup);

test("not enrolled: shows enroll form, submit calls onSave with the key", () => {
  const onSave = vi.fn();
  render(<KevboxTab kevbox={null} busy={false} onSave={onSave} onUnenroll={vi.fn()} onRevealUrl={vi.fn()} />);
  fireEvent.change(screen.getByLabelText("kevbox-key"), { target: { value: "PMK" } });
  fireEvent.click(screen.getByText("Enroll"));
  expect(onSave).toHaveBeenCalledWith({ premiumizeKey: "PMK" });
});

test("enrolled: shows status, reveal button calls onRevealUrl", () => {
  const onRevealUrl = vi.fn();
  render(
    <KevboxTab
      kevbox={{ name: "alice", enrolled: true, hasKey: true }}
      busy={false} onSave={vi.fn()} onUnenroll={vi.fn()} onRevealUrl={onRevealUrl}
    />,
  );
  expect(screen.getByText(/Enrolled/)).toBeTruthy();
  fireEvent.click(screen.getByText("Reveal / copy URL"));
  expect(onRevealUrl).toHaveBeenCalled();
});
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root apps/web test/web/KevboxTab.test.tsx`
Expected: FAIL — cannot find module `KevboxTab.js`.

- [ ] **Step 4: Implement the tab**

Create `apps/web/src/web/components/KevboxTab.tsx`:

```tsx
import { useState } from "react";
import type { KevboxState } from "../lib/api.js";

export interface KevboxTabProps {
  kevbox: KevboxState | null;
  busy?: boolean;
  /** name optional (rename), premiumizeKey optional (rotate); enroll requires the key. */
  onSave: (body: { name?: string; premiumizeKey?: string }) => void;
  onUnenroll: () => void;
  onRevealUrl: () => void;
}

export function KevboxTab({ kevbox, busy = false, onSave, onUnenroll, onRevealUrl }: KevboxTabProps) {
  const enrolled = kevbox?.enrolled === true;
  const [name, setName] = useState(kevbox?.name ?? "");
  const [key, setKey] = useState("");

  return (
    <div>
      <h4>Kevbox</h4>
      <p className="muted">
        {enrolled ? (
          <>Status: <strong>Enrolled</strong> as <strong>{kevbox!.name}</strong>{kevbox!.hasKey ? "" : " (no key stored)"}</>
        ) : (
          <>Status: <strong>Not enrolled</strong></>
        )}
      </p>

      <p>
        <label className="muted">AIOStreams name (defaults to email local-part)</label>
        <input aria-label="kevbox-name" value={name} onChange={(e) => setName(e.target.value)} placeholder={kevbox?.name ?? "name"} />
      </p>
      <p>
        <label className="muted">Premiumize key {enrolled ? "(set to rotate)" : "(required to enroll)"}</label>
        <input aria-label="kevbox-key" value={key} onChange={(e) => setKey(e.target.value)} placeholder="••••••••" />
      </p>

      <div className="row">
        <button
          className="primary"
          disabled={busy || (!enrolled && key.trim() === "")}
          onClick={() => {
            const body: { name?: string; premiumizeKey?: string } = {};
            if (name.trim()) body.name = name.trim();
            if (key.trim()) body.premiumizeKey = key.trim();
            onSave(body);
            setKey("");
          }}
        >
          {enrolled ? "Save" : "Enroll"}
        </button>
        {enrolled && kevbox!.hasKey && (
          <button disabled={busy} onClick={onRevealUrl}>Reveal / copy URL</button>
        )}
      </div>

      {enrolled && (
        <p className="muted" style={{ marginTop: 8 }}>
          Rotating the key updates the member's remote addon URL — their TV picks it up on its next
          sync (no end-user reinstall).
        </p>
      )}

      {enrolled && (
        <div style={{ marginTop: 16 }}>
          <button className="danger" disabled={busy} onClick={onUnenroll}>Un-enroll</button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root apps/web test/web/KevboxTab.test.tsx`
Expected: PASS.

- [ ] **Step 6: Wire the tab into `MemberDetail.tsx`**

In `apps/web/src/web/components/MemberDetail.tsx`: import `KevboxTab` + `KevboxState`, replace the import of `DebridForm` (removed in Task 11 — for now leave DebridForm; this task only ADDS kevbox). Add `"kevbox"` to the `Tab` union, a tab button, the panel, and these props:

```ts
type Tab = "addons" | "access" | "activity" | "kevbox";
// props additions:
  kevbox: import("../lib/api.js").KevboxState | null;
  onSaveKevbox: (body: { name?: string; premiumizeKey?: string }) => void;
  onUnenrollKevbox: () => void;
  onRevealKevboxUrl: () => void;
```

Add the tab button next to the others:

```tsx
        <button className={`tab${tab === "kevbox" ? " active" : ""}`} onClick={() => setTab("kevbox")}>Kevbox</button>
```

Add the panel branch (before the addons `<>` fallback):

```tsx
      ) : tab === "kevbox" ? (
        <KevboxTab
          kevbox={kevbox}
          busy={busy}
          onSave={onSaveKevbox}
          onUnenroll={onUnenrollKevbox}
          onRevealUrl={onRevealKevboxUrl}
        />
```

- [ ] **Step 7: Wire state + handlers into `App.tsx`**

In `apps/web/src/web/App.tsx`:
- Add state: `const [selectedKevbox, setSelectedKevbox] = useState<import("./lib/api.js").KevboxState | null>(null);`
- In `reloadSelected`, capture the kevbox block from the member fetch: the member now has `.kevbox`. After `setSelected(member)`, add `setSelectedKevbox(member.kevbox);`
- Add handlers:

```ts
  const onSaveKevbox = (body: { name?: string; premiumizeKey?: string }) =>
    withBusy(async () => { if (selected) { await api.putKevbox(selected.userId, body); await afterMutate(); } });
  function onUnenrollKevbox() {
    if (!selected) return;
    const userId = selected.userId;
    setPending({
      title: "Un-enroll from Kevbox",
      message: <>Remove <strong>{selected.email ?? userId}</strong> from the Kevbox allowlist? Their TV loses the kevbox addon on next sync.</>,
      run: () => withBusy(async () => { await api.unenrollKevbox(userId); await afterMutate(); }),
    });
  }
  const onRevealKevboxUrl = () =>
    withBusy(async () => {
      if (!selected) return;
      const { installUrl } = await api.getKevboxInstallUrl(selected.userId);
      await navigator.clipboard.writeText(installUrl).catch(() => undefined);
      window.prompt("Kevbox install URL (copied):", installUrl);
    });
```

- Pass the new props to `<MemberDetail ... kevbox={selectedKevbox} onSaveKevbox={onSaveKevbox} onUnenrollKevbox={onUnenrollKevbox} onRevealKevboxUrl={onRevealKevboxUrl} />`.

- [ ] **Step 8: Swap the MemberList badge**

In `apps/web/src/web/components/MemberList.tsx` line 28, change `{m.hasDebrid ? " · debrid" : ""}` → `{m.enrolled ? " · kevbox" : ""}`.

- [ ] **Step 9: Typecheck the web app**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npm run --workspace @kevbox-admin/web typecheck`
Expected: No errors. (`MemberList.test.tsx` / `deep-link.test.ts` fixtures were already migrated `hasDebrid` → `enrolled` in Task 7 Step 1e/1f, and the badge text changed to `· kevbox` here in Step 8 — re-run `npx vitest run --root apps/web test/web` to confirm both pass.)

- [ ] **Step 10: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add apps/web/src/web/lib/api.ts apps/web/src/web/components/KevboxTab.tsx apps/web/src/web/components/MemberDetail.tsx apps/web/src/web/components/MemberList.tsx apps/web/src/web/App.tsx apps/web/test/web/KevboxTab.test.tsx
git commit -m "feat(web): Kevbox tab (enroll/rename/rotate/unenroll + reveal URL), enrolled badge"
```

---

### Task 10: CLI — kevbox commands + migration

**Files:**
- Modify: `packages/cli/src/actions.ts` (add kevbox actions)
- Modify: `packages/cli/src/index.ts` (register commands)
- Modify: `packages/cli/src/db.ts` (helper to build `KevboxConfig` from env)
- Test: `packages/cli/test/kevbox.actions.test.ts`

- [ ] **Step 1: Add a config helper**

In `packages/cli/src/db.ts`, add:

```ts
import { loadEncKey, type KevboxConfig } from "@kevbox-admin/core";

/** Build the KevboxConfig from env (KEVBOX_ENC_KEY + KEVBOX_MEMBERS_FILE required). */
export function resolveKevboxConfig(): KevboxConfig {
  const encRaw = process.env.KEVBOX_ENC_KEY?.trim();
  const file = process.env.KEVBOX_MEMBERS_FILE?.trim();
  if (!encRaw) throw new Error("KEVBOX_ENC_KEY is required for kevbox commands");
  if (!file) throw new Error("KEVBOX_MEMBERS_FILE is required for kevbox commands");
  const addonSort = Number.parseInt(process.env.KEVBOX_ADDON_SORT?.trim() || "4", 10);
  // Mirror the existing PORT int-validation: a NaN here fails the int-NOT-NULL member_addon insert
  // and 500s every enroll/rotate/rename, so reject it before any command runs.
  if (!Number.isInteger(addonSort)) throw new Error("KEVBOX_ADDON_SORT must be an integer");
  return {
    encKey: loadEncKey(encRaw),
    membersFile: file,
    streamsBaseUrl: (process.env.KEVBOX_STREAMS_BASE_URL?.trim() || "https://streams.kevbox.dev").replace(/\/$/, ""),
    addonSort,
  };
}
```

- [ ] **Step 2: Write the failing test**

Create `packages/cli/test/kevbox.actions.test.ts`:

```ts
import { afterAll, expect, test } from "vitest";
import { mkdtempSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { KevboxConfig } from "@kevbox-admin/core";
import { loadEncKey, getKevbox } from "@kevbox-admin/core";
import { pool, withRollback, createTestMember } from "../../core/test/helpers.js";
import { actionKevboxEnroll, actionKevboxMigrate } from "../src/actions.js";

afterAll(async () => { await pool.end(); });

function cfg(): KevboxConfig {
  return { encKey: loadEncKey("0".repeat(64)), membersFile: join(mkdtempSync(join(tmpdir(), "cli-")), "members.json"), streamsBaseUrl: "https://streams.kevbox.dev", addonSort: 4 };
}
function captureSink() { const out: string[] = []; const err: string[] = []; return { sink: { log: (s: string) => out.push(s), err: (s: string) => err.push(s) }, out, err }; }

test("actionKevboxEnroll enrolls + writes members.json", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "cli.one@test.dev");
    const c = cfg(); const { sink, out } = captureSink();
    await actionKevboxEnroll(db, "cli.one@test.dev", { premiumize: "PMK" }, c, sink);
    expect(out.join("\n")).toContain("/stremio/k/cli.one/PMK/manifest.json");
    expect((await getKevbox(db, uid, c))!.enrolled).toBe(true);
    expect(JSON.parse(readFileSync(c.membersFile, "utf8"))).toContain("cli.one");
  });
});

test("actionKevboxMigrate dry-run prints a report and writes nothing", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "cli.mig@test.dev");
    await db.query(`insert into public.member_addon (user_id, url, sort_order) values ($1, 'https://streams.kevbox.dev/stremio/k/cli.mig/K/manifest.json', 4)`, [uid]);
    const c = cfg(); const { sink, out } = captureSink();
    await actionKevboxMigrate(db, ["cli.mig"], { apply: false }, c, sink);
    expect(out.join("\n")).toMatch(/matched.*1/i);
    expect((await getKevbox(db, uid, c))).toBeNull(); // dry-run persisted nothing
  });
});
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/cli test/kevbox.actions.test.ts`
Expected: FAIL — `actionKevboxEnroll`/`actionKevboxMigrate` not exported.

- [ ] **Step 4: Add the actions**

In `packages/cli/src/actions.ts`, add imports + actions:

```ts
import type { KevboxConfig } from "@kevbox-admin/core";
import { enrollMember, rotateKey, renameMember, unenrollMember, getKevbox, buildInstallUrl, withKevboxWrite, migrate261 } from "@kevbox-admin/core";

/** kevbox-enroll <ref> --premiumize <key> [--name <n>] */
export async function actionKevboxEnroll(
  db: Db, ref: string, opts: { premiumize: string; name?: string }, cfg: KevboxConfig, sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const { installUrl } = await withKevboxWrite(db, cfg.membersFile, (d) =>
    enrollMember(d, member.userId, { aiostreamsName: opts.name, premiumizeKey: opts.premiumize }, cfg),
  );
  sink.log(`Enrolled ${member.email ?? member.userId}. Install URL: ${installUrl}`);
}

/** kevbox-rotate <ref> --premiumize <key> */
export async function actionKevboxRotate(
  db: Db, ref: string, opts: { premiumize: string }, cfg: KevboxConfig, sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const { installUrl } = await withKevboxWrite(db, cfg.membersFile, (d) => rotateKey(d, member.userId, opts.premiumize, cfg));
  sink.log(`Rotated key. New install URL: ${installUrl}`);
}

/** kevbox-rename <ref> <newName> */
export async function actionKevboxRename(
  db: Db, ref: string, newName: string, cfg: KevboxConfig, sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const res = await withKevboxWrite(db, cfg.membersFile, (d) => renameMember(d, member.userId, newName, cfg));
  sink.log(res.keyless ? `Renamed to ${newName} (no key stored — re-issue a key/URL).` : `Renamed. New install URL: ${res.installUrl}`);
}

/** kevbox-unenroll <ref> */
export async function actionKevboxUnenroll(db: Db, ref: string, cfg: KevboxConfig, sink: Sink = consoleSink): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  await withKevboxWrite(db, cfg.membersFile, (d) => unenrollMember(d, member.userId, cfg));
  sink.log(`Un-enrolled ${member.email ?? member.userId}.`);
}

/** kevbox-url <ref> — print the key-bearing install URL (reveal). */
export async function actionKevboxUrl(db: Db, ref: string, cfg: KevboxConfig, sink: Sink = consoleSink): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const url = await buildInstallUrl(db, member.userId, cfg);
  if (!url) { sink.err("No install URL (member has no stored key)."); return; }
  sink.log(url);
}

/** kevbox-migrate --names <comma-list|@file> [--apply] */
export async function actionKevboxMigrate(
  db: Db, names: string[], opts: { apply: boolean }, cfg: KevboxConfig, sink: Sink = consoleSink,
): Promise<void> {
  // migrate261 THROWS under --apply if there are malformed/lost/conflict names (it blocks the write);
  // let that propagate so the CLI exits non-zero with the at-risk list. Dry-run never throws.
  const r = await migrate261(db, names, cfg, opts);
  sink.log(
    `${r.applied ? "APPLIED" : "DRY-RUN"}: total ${r.total}, matched ${r.matched}, backfilled ${r.backfilled}, ` +
      `extras ${r.extras.length}, conflicts ${r.conflicts.length}, malformed ${r.malformed.length}, ` +
      `lost ${r.lost.length}, renamed ${r.renamed.length}, added ${r.added.length}, rendered ${r.rendered.length}.`,
  );
  if (r.extras.length) sink.log(`Extras (unmanaged): ${r.extras.join(", ")}`);
  if (r.renamed.length) sink.log(`Renamed (verbatim drift): ${r.renamed.join(", ")}`);
  if (r.added.length) sink.log(`Added (not in input): ${r.added.join(", ")}`);
  if (r.conflicts.length) sink.err(`Conflicts (skipped): ${r.conflicts.join(", ")}`);
  if (r.malformed.length) sink.err(`Malformed (at risk — blocks --apply): ${r.malformed.join(", ")}`);
  if (r.lost.length) sink.err(`LOST (would drop from allowlist — blocks --apply): ${r.lost.join(", ")}`);
}
```

- [ ] **Step 5: Register the commands**

In `packages/cli/src/index.ts`, import `resolveKevboxConfig` from `./db.js` and the new actions, and add commands (the migration reads names from `--names` as a comma list or `@path` file — the M1 explicit input). NOTE: `createPool`/`withPool` is already imported at the top of `index.ts` — add ONLY `resolveKevboxConfig` to that existing import; do not re-import `createPool`:

```ts
import { readFileSync } from "node:fs";
// `createPool`/`withPool` already exist in index.ts — extend the existing import with resolveKevboxConfig:
import { resolveKevboxConfig } from "./db.js";

program
  .command("kevbox-enroll")
  .argument("<ref>", "member email or userId")
  .requiredOption("--premiumize <key>", "the member's Premiumize API key")
  .option("--name <n>", "explicit AIOStreams name (defaults to email local-part)")
  .description("Enroll a member into the kevbox allowlist + add their addon URL.")
  .action(async (ref: string, opts: { premiumize: string; name?: string }) => {
    await withPool((pool) => actionKevboxEnroll(pool, ref, opts, resolveKevboxConfig(), consoleSink));
  });

program
  .command("kevbox-rotate")
  .argument("<ref>", "member email or userId")
  .requiredOption("--premiumize <key>", "the new Premiumize API key")
  .description("Rotate a member's Premiumize key (updates their remote addon URL).")
  .action(async (ref: string, opts: { premiumize: string }) => {
    await withPool((pool) => actionKevboxRotate(pool, ref, opts, resolveKevboxConfig(), consoleSink));
  });

program
  .command("kevbox-rename")
  .argument("<ref>", "member email or userId")
  .argument("<newName>", "the new AIOStreams name")
  .description("Rename a member's kevbox allowlist name (rebuilds their addon URL).")
  .action(async (ref: string, newName: string) => {
    await withPool((pool) => actionKevboxRename(pool, ref, newName, resolveKevboxConfig(), consoleSink));
  });

program
  .command("kevbox-unenroll")
  .argument("<ref>", "member email or userId")
  .description("Remove a member from the kevbox allowlist.")
  .action(async (ref: string) => {
    await withPool((pool) => actionKevboxUnenroll(pool, ref, resolveKevboxConfig(), consoleSink));
  });

program
  .command("kevbox-url")
  .argument("<ref>", "member email or userId")
  .description("Print a member's key-bearing install URL.")
  .action(async (ref: string) => {
    await withPool((pool) => actionKevboxUrl(pool, ref, resolveKevboxConfig(), consoleSink));
  });

program
  .command("kevbox-migrate")
  .requiredOption("--names <list>", "comma-separated KEVBOX_MEMBERS, or @path to a file with one/comma list")
  .option("--apply", "persist + write members.json (default is dry-run)", false)
  .description("One-off: import the legacy KEVBOX_MEMBERS allowlist (dry-run unless --apply).")
  .action(async (opts: { names: string; apply?: boolean }) => {
    const raw = opts.names.startsWith("@") ? readFileSync(opts.names.slice(1), "utf8") : opts.names;
    const names = raw.split(/[\s,]+/).map((s) => s.trim()).filter((s) => s.length > 0);
    await withPool((pool) => actionKevboxMigrate(pool, names, { apply: opts.apply === true }, resolveKevboxConfig(), consoleSink));
  });
```

- [ ] **Step 6: Run to verify it passes**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npx vitest run --root packages/cli`
Expected: PASS.

- [ ] **Step 7: Build the CLI**

Run: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npm run --workspace @kevbox-admin/cli build`
Expected: tsc succeeds.

- [ ] **Step 8: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add packages/cli/src/db.ts packages/cli/src/actions.ts packages/cli/src/index.ts packages/cli/test/kevbox.actions.test.ts
git commit -m "feat(cli): kevbox enroll/rotate/rename/unenroll/url + migrate commands"
```

---

### Task 11: Delete the old debrid onboarding

**Files:**
- Delete: `packages/core/src/debrid.ts`, `packages/core/test/debrid.test.ts`, `apps/web/src/web/components/DebridForm.tsx`
- Modify: `packages/core/src/defaults.ts`, `packages/core/src/index.ts`, `packages/core/test/defaults.test.ts`, `apps/web/src/server/routes/actions.ts`, `apps/web/test/server/actions.routes.test.ts`, `apps/web/src/web/lib/api.ts`, `apps/web/src/web/components/MemberDetail.tsx`, `apps/web/src/web/App.tsx`, `packages/cli/src/index.ts`, `packages/cli/src/actions.ts`, `packages/cli/test/actions.test.ts`

> **Verify before deleting (M4):** confirm nothing outside this list imports `onboardDebrid`, `buildTorrentioUrl`, `DEBRID_TORRENTIO_SORT`, `DEBRID_AIOSTREAMS_SORT`:
> `cd /home/kevin/projects/NuvioTV/kevbox-admin && grep -rn "onboardDebrid\|buildTorrentioUrl\|DEBRID_TORRENTIO_SORT\|DEBRID_AIOSTREAMS_SORT\|DebridForm\|onOnboardDebrid" --include=*.ts --include=*.tsx packages apps | grep -v node_modules`
> The migration (`migrate261.ts`) parses URLs with its OWN regex and does NOT import any of these — confirm it's absent from the grep hits in `src/`.

- [ ] **Step 1: Remove core debrid**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git rm packages/core/src/debrid.ts packages/core/test/debrid.test.ts
```

In `packages/core/src/index.ts`, delete the line `export * from "./debrid.js";`.

In `packages/core/src/defaults.ts`, delete `DEBRID_TORRENTIO_SORT`, `DEBRID_AIOSTREAMS_SORT`, and `buildTorrentioUrl` (the whole file is debrid-only per its header — if nothing else lives there, `git rm packages/core/src/defaults.ts` and remove `export * from "./defaults.js";` from `index.ts`; otherwise keep the unrelated exports). Confirm with: `grep -rn "from \"./defaults" packages/core/src`.

In `packages/core/test/defaults.test.ts`: if the file only tested `buildTorrentioUrl`, `git rm` it; otherwise delete those cases.

- [ ] **Step 2: Remove the server debrid route**

In `apps/web/src/server/routes/actions.ts`: remove the `onboardDebrid` import, the `DebridBody` interface, and the `POST /members/:userId/debrid` handler (keep the `reset` handler + `resetToDefaults`/`getMember` imports). The kept `reset` handler must also call the Task 4 Step 6 helper: thread `opts.kevbox` into the actions registrar (same as Task 8 Step 5 threads it into `registerMemberRoutes`) and, after `await resetToDefaults(db, userId)`, add `if (opts.kevbox) await reapplyKevboxAddon(db, userId, opts.kevbox);` (import `reapplyKevboxAddon` from `@kevbox-admin/core`). In `apps/web/test/server/actions.routes.test.ts`, delete the debrid cases (keep reset).

- [ ] **Step 3: Remove the web debrid UI**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git rm apps/web/src/web/components/DebridForm.tsx
```

In `apps/web/src/web/components/MemberDetail.tsx`: remove `import { DebridForm }`, the `onOnboardDebrid` prop, and the `<DebridForm ... />` usage + the `<hr/>` above it.
In `apps/web/src/web/App.tsx`: remove `onOnboardDebrid` handler + the prop passed to `<MemberDetail>`.
In `apps/web/src/web/lib/api.ts`: remove the `onboardDebrid` method.

- [ ] **Step 4: Remove the CLI debrid command**

In `packages/cli/src/index.ts`: remove the `onboard-debrid` command + the `actionOnboardDebrid` import.
In `packages/cli/src/actions.ts`: remove `actionOnboardDebrid` + the `onboardDebrid` import.
In `packages/cli/test/actions.test.ts`: remove the onboard-debrid cases.

- [ ] **Step 5: Run the full suite + typecheck**

Run:
```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
npm test
npm run --workspace @kevbox-admin/web typecheck
```
Expected: All green, no references to deleted symbols. The earlier grep now returns only `migrate261.ts`'s own (unrelated) URL parsing — confirm.

- [ ] **Step 6: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add -A
git commit -m "chore: delete dead debrid onboarding (replaced by kevbox enrollment)"
```

---

### Task 12: Deploy — shared dir, systemd, env, README

**Files:**
- Modify: `deploy/kevbox-admin.service` (ReadWritePaths + group)
- Modify: `deploy/env.example` (new env vars)
- Modify: `deploy/README.md` (shared-dir + migration runbook)

- [ ] **Step 1: systemd — allow writing the shared dir**

In `deploy/kevbox-admin.service`, add the shared dir to `ReadWritePaths` (line 23) and add the kevbox supplementary group so the writer + container share group `kevbox`:

```ini
ReadWritePaths=/opt/kevbox-admin /var/lib/kevbox-shared
SupplementaryGroups=kevbox
```

- [ ] **Step 2: env.example — document the new vars**

Append to `deploy/env.example`:

```bash
# --- KevBox member enrollment ---
# 32-byte AES key for Premiumize keys at rest. Generate: openssl rand -hex 32
KEVBOX_ENC_KEY=CHANGE_ME_64_hex_chars
# Shared file the dashboard writes and the kevbox container reads (see deploy/README.md).
KEVBOX_MEMBERS_FILE=/var/lib/kevbox-shared/members.json
# Install-URL base (kevbox AIOStreams). Default is fine.
KEVBOX_STREAMS_BASE_URL=https://streams.kevbox.dev
# Sort slot for the kevbox member_addon row. Default 4.
KEVBOX_ADDON_SORT=4
```

- [ ] **Step 3: README — shared-dir provisioning + migration runbook**

Append a section to `deploy/README.md`:

````markdown
## KevBox member enrollment (members.json)

One-time provisioning on persovps (spec §15 step 2):

```bash
sudo groupadd -f kevbox
sudo install -d -o kevbox-admin -g kevbox -m 2775 /var/lib/kevbox-shared   # setgid: files inherit group kevbox
sudo usermod -aG kevbox kevbox-admin
sudo usermod -aG kevbox kevin            # the kevbox container's host user
# apply schema:
psql "$SUPABASE_DB_URL" -f deploy/kevbox_member_setup.sql
sudo systemctl daemon-reload && sudo systemctl restart kevbox-admin
```

Migrate the legacy 261 (dry-run first):

```bash
# pull the live list (one-shot input, M1) from the AIOStreams .env on the box:
export KEVBOX_MEMBERS="$(grep '^KEVBOX_MEMBERS=' /opt/kevbox/AIOStreams/.env | cut -d= -f2-)"
# DRY-RUN — review the report (expect 0 lost / 0 renamed):
node packages/cli/dist/index.js kevbox-migrate --names "$KEVBOX_MEMBERS"
# APPLY once the report looks right:
node packages/cli/dist/index.js kevbox-migrate --names "$KEVBOX_MEMBERS" --apply
# verify the written set matches the env set:
diff <(tr ',' '\n' <<<"$KEVBOX_MEMBERS" | sort -u) <(jq -r '.[]' /var/lib/kevbox-shared/members.json | sort -u)
```

Rollback (spec §15 step 6): `rm /var/lib/kevbox-shared/members.json` → the container falls back to `KEVBOX_MEMBERS` env.
````

- [ ] **Step 4: Commit**

```bash
cd /home/kevin/projects/NuvioTV/kevbox-admin
git add deploy/kevbox-admin.service deploy/env.example deploy/README.md
git commit -m "docs(deploy): kevbox shared-dir, systemd ReadWritePaths, env, migration runbook"
```

---

## Final verification (after Task 12)

- [ ] Full suite green: `cd /home/kevin/projects/NuvioTV/kevbox-admin && npm test`
- [ ] All workspaces build: `npm run build`
- [ ] Web typecheck clean: `npm run --workspace @kevbox-admin/web typecheck`
- [ ] Grep confirms no debrid leftovers in `src`/`apps` (only `migrate261.ts`'s own URL parse): `grep -rn "debrid\|Debrid\|Torrentio" --include=*.ts --include=*.tsx packages apps | grep -v node_modules`
- [ ] No stray `hasDebrid` references remain (field renamed to `enrolled` in T7): `grep -rn "hasDebrid" --include=*.ts --include=*.tsx packages apps | grep -v node_modules` returns nothing.
- [ ] Audit trail populated (T8b): a `kevbox_audit` row exists for each mutation + reveal, and `select * from public.kevbox_audit` carries no key/URL value.

## Deployment order (spec §15)

Order matters — the shared dir, the schema, and the env vars must all be in place BEFORE the
dashboard starts, or the first kevbox write fails (no writable dir / missing tables / unconfigured
optional block).

1. AIOStreams fork file source deployed (separate plan) — file absent → env fallback.
2. **Provision the shared dir FIRST, before `docker compose up`:** `/var/lib/kevbox-shared`
   owned `kevbox-admin:kevbox`, mode **2775** (setgid so files inherit group `kevbox`), with the
   container's host user and `kevbox-admin` both in group `kevbox` (Task 12 Step 3). Add it to systemd
   `ReadWritePaths` (Task 12 Step 1). The dashboard's atomic write needs this dir to exist + be
   group-writable before it ever runs.
3. **Apply `deploy/kevbox_member_setup.sql` as POSTGRES** (the superuser/owner) in the Supabase SQL
   editor — `kevbox_admin` cannot `CREATE TABLE`/`GRANT`, so it must NOT be applied as kevbox_admin.
   This creates `kevbox_member` / `kevbox_allowlist_extra` / `kevbox_audit`, enables RLS, and grants
   kevbox_admin.
4. **Set `KEVBOX_ENC_KEY` and `KEVBOX_MEMBERS_FILE` (together) in the env BEFORE deploying** (plus the
   optional `KEVBOX_STREAMS_BASE_URL` / `KEVBOX_ADDON_SORT`) in `/etc/kevbox-admin/env`. The kevbox
   block is optional but half-configured fails loud, so set BOTH before bringing the dashboard up.
5. Deploy kevbox-admin (routes/UI live, debrid deleted). Boot-render (Task 8 Step 8b) reconciles the
   file from the DB on startup once ≥1 member is enrolled.
6. `kevbox-migrate` dry-run → review (expect 0 lost / 0 conflict) → `--apply`; verify set equality +
   group-readable file.
7. Confirm the container serves from the file; keep `KEVBOX_MEMBERS` as backup; remove later.

---

## Self-Review (run during planning)

**Spec coverage:** §4 schema → T1 (+ kevbox_audit in T8b); §5 crypto → T2; §6 renderer (C2/C4) → T3; §6 lock/boot-render (H2/H3) → T5 (+ boot-render note below); §8 enroll/rename/rotate/unenroll (H1, key-retain) → T4 (+ reset-reapply, T4 Step 6); §9 UX/API + C5 → T8/T9; §10 delete onboarding (M4) → T11; §11 migration (C1/H4/H6/M1) → T6 + T10 + T12 runbook; §12 config → T8/T10/T12; §13 security (C5, audit) → T8 (key isolation) + **T8b (structured audit trail: kevbox_audit + writeAudit on every mutation + reveal)**; §15 rollout/rollback (M2) → T12. C3 (file precedence) lives in the AIOStreams plan. H5 (partial unique) → T1.

**Addressed (no longer deferred):**
- **Boot-render (H3):** RESOLVED — Task 8 Step 8b wires a one-shot `renderMembersFile(pool, cfg.kevbox.membersFile)` in `apps/web/src/server/index.ts`, fail-soft-logged and GATED on "kevbox configured AND `kevbox_member` has ≥1 enrolled row" so the empty-set floor can't trip pre-migration. Step 8c adds the §14 integration tests ("boot-render reconciles a stale file" + "empty-set gate skips the render").
- **Audit logging (§13):** RESOLVED — Task 8b adds the `kevbox_audit` table (test schema + prod setup SQL), a core `writeAudit` helper, and calls in every kevbox route mutation (enroll/rename/rotate/unenroll) plus the install-url reveal, with tests asserting a row is written and no secret value is recorded.
- **Log redaction (§13, C5):** RESOLVED — Task 8 Step 5b adds Fastify/pino `redact` for the Premiumize key body fields and a `kevbox.redaction.test.ts` proving no log line embeds the key or the install URL.
- **`hasDebrid` → `enrolled` ripple (M2):** RESOLVED — Task 7 updates all 7 live refs (`types.ts`, `members.ts`, `format.ts`, and the 4 test fixtures) so the suite stays green.
- **RLS hardening (M4):** RESOLVED — `deploy/kevbox_member_setup.sql` (Tasks 1 + 8b) enables RLS and revokes anon/authenticated grants on `kevbox_member`, `kevbox_allowlist_extra`, and `kevbox_audit` (mirrors `member_access`).
- **PUT discriminator (M1):** RESOLVED — Task 8 Step 6 reads existence + decides enroll-vs-rename-vs-rotate INSIDE the advisory-locked txn on the locked connection `d` (no TOCTOU on the outer pool, H2).
- **Migration safety (M7):** RESOLVED — `migrate261` blocks `--apply` on malformed/lost/conflict names and is insert-only (never resurrects an un-enrolled member or clobbers a rotated key, H4).

**Known gaps / follow-ups (surfaced, not silently dropped):**
- **C2 %-drop guard (optional, spec §6):** the empty-set floor is implemented; an optional "refuse a >N% drop unless `--force`" guard is noted in Task 5 (after the serialization test) but left out of scope.

**Placeholder scan:** none — every code step carries full code; commands have expected output.

**Type consistency:** `KevboxConfig`/`KevboxState` defined in T4 (`types.ts`) and used identically in T5/T6/T8/T9/T10. `renderMembersFile(db, filePath)`, `withKevboxWrite(db, membersFile, fn)`, `enrollMember(db, userId, {aiostreamsName?, premiumizeKey}, cfg)`, `getKevbox(db, userId, cfg)`, `buildInstallUrl(db, userId, cfg)`, `migrate261(db, names, cfg, {apply})` signatures match across server, cli, and tests. `MemberSummary.enrolled` (T7) is consumed by `MemberList` (T9) and CLI `format` (T7 Step 1c renames the `DEBRID` column → `KEVBOX` and reads `m.enrolled`; the table layout is otherwise unchanged). `encryptSecret(plain, userId, key)` takes the key explicitly (divergence from the spec's 2-arg shape, for testability) and is called consistently in T4/T6.
