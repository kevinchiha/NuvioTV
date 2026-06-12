# AIOStreams `members.json` File Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the kevbox AIOStreams fork resolve its member allowlist from a hot-reloaded `members.json` file (written by kevbox-admin), falling back to the existing `KEVBOX_MEMBERS` env when the file is missing/empty/malformed — zero-disruption.

**Architecture:** A single additive change to `kevboxMembers()` in `packages/server/src/utils/kevboxTemplate.ts`: read `KEVBOX_MEMBERS_FILE` (if set + usable) via an mtime+size cache, validate each name, and only fall back to env when the file yields no usable list. Plus a compose change to bind-mount the shared **directory** (not the single file) read-only.

**Tech Stack:** TypeScript, Node `node:fs`, vitest. Repo: `/home/kevin/projects/AIOStreams` (separate from kevbox-admin).

**Prerequisite ordering:** This plan must be deployed BEFORE the kevbox-admin cutover (the `members.json` writer). With the file absent, behavior is identical to today.

**Spec:** `kevbox-admin/docs/superpowers/specs/2026-06-11-kevbox-member-enrollment-design.md` §2, §7, §15 (C3).

---

### Task 1: File source for `kevboxMembers()` with mtime+size cache and env fallback

**Files:**
- Modify: `packages/server/src/utils/kevboxTemplate.ts:1-24` (imports + `kevboxMembers`)
- Test: `packages/server/src/utils/kevboxTemplate.test.ts` (append a new `describe`)

- [ ] **Step 1: Write the failing tests**

Append to `packages/server/src/utils/kevboxTemplate.test.ts`:

```ts
// Reuse the test file's existing top-of-file imports: `mkdtempSync`, `writeFileSync`,
// `utimesSync`, `statSync` from 'node:fs'; `tmpdir` from 'node:os'; `path`. Add any of
// these (e.g. `statSync`, used by the SIZE-ONLY reload test) to that import if missing.
import { statSync } from 'node:fs'; // add to the existing 'node:fs' import if not already present

describe('kevboxMembers file source', () => {
  const writeMembers = (content: string): string => {
    const dir = mkdtempSync(path.join(tmpdir(), 'kevbox-members-'));
    const file = path.join(dir, 'members.json');
    writeFileSync(file, content);
    return file;
  };

  it('uses a non-empty members.json file when KEVBOX_MEMBERS_FILE is set', () => {
    const file = writeMembers('["alice","bob"]');
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file, KEVBOX_MEMBERS: 'zzz' })).toEqual([
      'alice',
      'bob',
    ]);
  });

  it('falls back to KEVBOX_MEMBERS env when the file is missing', () => {
    expect(
      kevboxMembers({ KEVBOX_MEMBERS_FILE: '/no/such/members.json', KEVBOX_MEMBERS: 'a,b' }),
    ).toEqual(['a', 'b']);
  });

  it('falls back to env when the file is an empty array', () => {
    const file = writeMembers('[]');
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file, KEVBOX_MEMBERS: 'a' })).toEqual(['a']);
  });

  it('falls back to env when the file is 0-byte / whitespace', () => {
    const file = writeMembers('   \n');
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file, KEVBOX_MEMBERS: 'a' })).toEqual(['a']);
  });

  it('falls back to env on malformed JSON (no throw)', () => {
    const file = writeMembers('{not json');
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file, KEVBOX_MEMBERS: 'a' })).toEqual(['a']);
  });

  it('drops invalid names but keeps valid ones', () => {
    const file = writeMembers('["good","BAD UPPER","ok.name","has space"]');
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file })).toEqual(['good', 'ok.name']);
  });

  it('reloads when the file changes (mtime or size cache-bust)', () => {
    const file = writeMembers('["one"]');
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file })).toEqual(['one']);
    writeFileSync(file, '["one","two"]');
    const future = Date.now() / 1000 + 10;
    utimesSync(file, future, future);
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file })).toEqual(['one', 'two']);
  });

  it('ignores KEVBOX_MEMBERS_FILE when unset (pure env behavior)', () => {
    expect(kevboxMembers({ KEVBOX_MEMBERS: 'x , y , ' })).toEqual(['x', 'y']);
  });

  it('ignores a sibling temp file (reads only members.json)', () => {
    // kevbox-admin writes a sibling `.tmp-<pid>-members.json` then atomic-renames it
    // onto members.json. A leftover/in-progress temp sibling must never be picked up —
    // KEVBOX_MEMBERS_FILE points at members.json exactly, so only that name is read.
    const dir = mkdtempSync(path.join(tmpdir(), 'kevbox-members-'));
    const file = path.join(dir, 'members.json');
    writeFileSync(file, '["alice"]');
    writeFileSync(path.join(dir, '.tmp-12345-members.json'), '["mallory"]');
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file, KEVBOX_MEMBERS: 'zzz' })).toEqual([
      'alice',
    ]);
  });

  it('boot fail-loud: empty RESOLVED list after file→env fallback yields []', () => {
    // Spec §7 fail-loud: when neither the file nor env yields any name, the resolved
    // list is empty (server.ts then refuses to boot). Empty file ([]) falls back to env,
    // and env is also empty → kevboxMembers() returns [] (not a partial/stale list).
    const file = writeMembers('[]');
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file, KEVBOX_MEMBERS: '' })).toEqual([]);
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file, KEVBOX_MEMBERS: '   ,  , ' })).toEqual(
      [],
    );
  });

  it('reloads on a SIZE-ONLY change (same mtime, different length)', () => {
    // Exercises the `cached.size === stat.size` cache term specifically: force the SAME
    // mtime back onto the file after writing different-length content. If the size check
    // were dropped, the stale cached names would be returned and this test would fail.
    const file = writeMembers('["one"]');
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file })).toEqual(['one']);
    const { mtimeMs } = statSync(file); // capture the cached mtime
    writeFileSync(file, '["one","two"]'); // longer content → different size
    const sameMtimeSec = mtimeMs / 1000;
    utimesSync(file, sameMtimeSec, sameMtimeSec); // force mtime back to the cached value
    expect(kevboxMembers({ KEVBOX_MEMBERS_FILE: file })).toEqual(['one', 'two']);
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /home/kevin/projects/AIOStreams && npx vitest run packages/server/src/utils/kevboxTemplate.test.ts`
Expected: FAIL — the file-source `describe` fails (env-only `kevboxMembers` ignores `KEVBOX_MEMBERS_FILE`, so the first test returns `['zzz']` not `['alice','bob']`).

- [ ] **Step 3: Implement the file source**

In `packages/server/src/utils/kevboxTemplate.ts`, the imports on line 1 already include `existsSync, readFileSync, statSync`. Replace the existing `kevboxMembers` function (lines 14-24) with:

```ts
/** Member name rule (mirrors the AIOStreams allowlist + kevbox-admin). */
const KEVBOX_NAME_REGEX = /^[a-z0-9._+-]{1,64}$/;

interface MembersFileEntry {
  mtimeMs: number;
  size: number;
  names: string[];
}
/** mtime+size cache, keyed by file path (mirrors loadKevboxTemplate's cache). */
const membersFileCache = new Map<string, MembersFileEntry>();

/**
 * Resolve the allowlist from KEVBOX_MEMBERS_FILE, or null to signal "no usable file"
 * (caller then falls back to KEVBOX_MEMBERS env). A present file is "usable" only if it
 * parses to a NON-EMPTY array of names; missing / 0-byte / whitespace / malformed / `[]`
 * all return null so the file source can never by itself disable kevbox (spec §7, C3).
 * Parse errors are caught + logged, never thrown into the request/boot path.
 */
function kevboxMembersFromFile(env: NodeJS.ProcessEnv): string[] | null {
  const filePath = env.KEVBOX_MEMBERS_FILE;
  if (!filePath || !existsSync(filePath)) return null;
  let stat: ReturnType<typeof statSync>;
  try {
    stat = statSync(filePath);
  } catch {
    return null;
  }
  const cached = membersFileCache.get(filePath);
  if (cached && cached.mtimeMs === stat.mtimeMs && cached.size === stat.size) {
    return cached.names.length > 0 ? cached.names : null;
  }
  let names: string[];
  try {
    const raw = readFileSync(filePath, 'utf-8').trim();
    if (raw === '') return null;
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return null;
    names = parsed
      .filter((n): n is string => typeof n === 'string')
      .map((n) => n.trim())
      .filter((n) => {
        if (KEVBOX_NAME_REGEX.test(n)) return true;
        // eslint-disable-next-line no-console
        console.warn(`kevbox: dropping invalid members.json entry "${n}"`);
        return false;
      });
  } catch (error: unknown) {
    // eslint-disable-next-line no-console
    console.warn(
      `kevbox: members.json at ${filePath} unreadable/not-JSON, falling back to env: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
    return null;
  }
  membersFileCache.set(filePath, { mtimeMs: stat.mtimeMs, size: stat.size, names });
  return names.length > 0 ? names : null;
}

/**
 * The member allowlist. Resolution precedence (spec §7): a usable members.json file
 * (KEVBOX_MEMBERS_FILE → non-empty array) wins; otherwise fall back to KEVBOX_MEMBERS
 * (comma-separated, trimmed, blanks dropped). Empty resolved array = kevbox disabled.
 * Kept core-free so it is unit-testable without booting the @aiostreams/core env.
 */
export function kevboxMembers(env: NodeJS.ProcessEnv = process.env): string[] {
  const fromFile = kevboxMembersFromFile(env);
  if (fromFile) return fromFile;
  return (env.KEVBOX_MEMBERS ?? '')
    .split(',')
    .map((member) => member.trim())
    .filter((member) => member.length > 0);
}
```

**Hot-path cost note:** `kevboxMembers()` runs inside the per-request kevbox manifest/stream
path (`packages/server/src/routes/stremio/kevbox.ts`) for ~261 members, so the added `statSync`
executes on the hot path. The mtime+size cache keeps this to a **single `statSync` per request**
(the much heavier `readFileSync` + `JSON.parse` runs only on a cache-bust, i.e. when the file is
written). `existsSync` short-circuits to skip even the `stat` when `KEVBOX_MEMBERS_FILE` is unset.
One `statSync` per request against a memory-resident path is acceptable, but it is a new per-request
syscall and is documented here as a deliberate, bounded cost.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /home/kevin/projects/AIOStreams && npx vitest run packages/server/src/utils/kevboxTemplate.test.ts`
Expected: PASS — all existing tests plus the new `kevboxMembers file source` block.

- [ ] **Step 5: Typecheck the package**

Run: `cd /home/kevin/projects/AIOStreams && npx tsc -p packages/server/tsconfig.json --noEmit`
Expected: No errors. (If the repo has no per-package tsconfig, run the repo's build/typecheck script instead: `npm run --workspace @aiostreams/server build` or the root `npm run build`.)

- [ ] **Step 6: Commit**

```bash
cd /home/kevin/projects/AIOStreams
git add packages/server/src/utils/kevboxTemplate.ts packages/server/src/utils/kevboxTemplate.test.ts
git commit -m "feat(kevbox): members.json file source for kevboxMembers with env fallback

Resolve the allowlist from KEVBOX_MEMBERS_FILE (mtime+size cache); fall back to
KEVBOX_MEMBERS env when the file is missing/empty/malformed so rollout is
zero-disruption and an empty file is a safe rollback lever, not a kill-switch."
```

---

### Task 2: Bind-mount the shared directory into the kevbox container

**Files:**
- Modify: `compose.kevbox.yaml` (the `kevbox` service `environment:` + `volumes:`)

- [ ] **Step 1: Add the directory mount + env var**

In `compose.kevbox.yaml`, in the `kevbox:` service, add the env var under `environment:` (alongside `NODE_OPTIONS`):

```yaml
      - KEVBOX_MEMBERS_FILE=/app/kevbox-shared/members.json
```

and add the **directory** bind-mount under `volumes:` (alongside the existing `./data` and `./kevbox.config.json` mounts):

```yaml
      # Shared dir written by kevbox-admin (members.json). MUST be the DIRECTORY, not
      # the single file: kevbox-admin replaces members.json via atomic rename, which
      # swaps the inode — a single-file bind-mount would pin the old inode and the mtime
      # cache would never see updates. Read-only here (the container only reads).
      - /var/lib/kevbox-shared:/app/kevbox-shared:ro
```

- [ ] **Step 2: Validate the compose file parses**

Run: `cd /home/kevin/projects/AIOStreams && docker compose -f compose.kevbox.yaml config >/dev/null && echo OK`
Expected: `OK` (no YAML/compose errors). The host path `/var/lib/kevbox-shared` must be provisioned with the right owner/group/mode BEFORE the first `up` (see the Deployment section below). NOTE: if the dir is absent, Docker auto-creates the bind-mount source as `root:root 0755` — this is fine for THIS container (it only reads), but the kevbox-admin **writer** (running as `kevbox-admin`, group `kevbox`) then cannot create `members.json` inside a root-owned dir. So do not rely on Docker auto-creation; provision the dir first. With no `members.json` present yet, the reader correctly falls back to `KEVBOX_MEMBERS` env.

- [ ] **Step 3: Commit**

```bash
cd /home/kevin/projects/AIOStreams
git add compose.kevbox.yaml
git commit -m "feat(kevbox): bind-mount shared dir for members.json (KEVBOX_MEMBERS_FILE)

Mount /var/lib/kevbox-shared as a read-only DIRECTORY (not the single file) so
atomic-rename updates from kevbox-admin propagate to the mtime+size cache."
```

---

## Deployment (operational — after both commits)

Not code; do on persovps when ready to roll out (spec §15 steps 1-2):

- [ ] **Provision the shared dir FIRST (before any `up`).** Create `/var/lib/kevbox-shared`
  owned by the writer (`kevbox-admin`), group `kevbox`, mode `2775` (setgid so files inherit
  group `kevbox`; group-writable so the writer can create + atomically rename `members.json`):

  ```bash
  sudo install -d -o kevbox-admin -g kevbox -m 2775 /var/lib/kevbox-shared
  ```

  This MUST happen before the first `docker compose up`: if the dir is missing, Docker
  auto-creates the bind-mount source as `root:root 0755`. That is harmless for THIS
  container (it mounts the dir `:ro` and only reads), but it blocks the kevbox-admin
  **writer** from ever creating `members.json` in a root-owned dir — so the file source
  would silently never populate. (This is the same `/var/lib/kevbox-shared` provisioning
  done by the kevbox-admin deploy plan Task 12; whichever plan runs first does it, the
  other verifies owner/group/mode. Do not let Docker auto-create it.)
- [ ] Pull + rebuild the kevbox container: `docker compose -f compose.kevbox.yaml up -d --build kevbox`
- [ ] Confirm the env-fallback path still works with no file present: kevbox URLs resolve exactly as before (members come from `KEVBOX_MEMBERS`).
- [ ] Verify the writer and reader share group `kevbox` on `/var/lib/kevbox-shared`: `stat -c '%U %G %a' /var/lib/kevbox-shared` should report `kevbox-admin kevbox 2775`.

## Self-Review (completed during planning)

- **Spec coverage:** §7 file source + precedence → Task 1; §7/§14 edge cases (.tmp sibling ignored, boot fail-loud on empty resolved list, SIZE-ONLY cache-bust) → Task 1 Step 1 tests; compose directory mount + `KEVBOX_MEMBERS_FILE` → Task 2; §15 steps 1-2 + dir provisioning (owner `kevbox-admin`, group `kevbox`, mode `2775`, before first `up`) → Deployment section. Boot fail-loud (§7) is unchanged — `kevboxMembers()` still returns `[]` when neither source yields names, so `server.ts:165` behaves identically.
- **Placeholder scan:** none — all steps carry real code/commands.
- **Type consistency:** `kevboxMembers(env?)` signature unchanged (still `: string[]`), so callers in `server.ts` and `routes/stremio/kevbox.ts` need no changes.
