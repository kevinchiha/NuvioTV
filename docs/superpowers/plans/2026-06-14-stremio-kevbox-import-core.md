# Stremio → KevBox Import Core + CLI — Implementation Plan (Plan 1 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the manual Stremio→KevBox import tool (pure converter + KevBox DB client + CLI) that backfills a member's Stremio watch_progress / watched_items / library into KevBox Supabase via the admin `sync_push_*_for` functions — enough to run the §7.3 EXECUTE preflight, the canary, and the manual backfill of the 17.

**Architecture:** Three layers in the existing fork. (1) `convert-kevbox.ts` — a **pure, network-free, bitfield-free** converter: Stremio library + an injected per-series *watched-episode index* → `{watchProgress, watchedItems, library}` payloads matching the verified RPC contract (spec §5/§6). (2) `kevbox.ts` — all I/O: a `pg` admin connection (owner resolution, allowlist, batched `_for` writes, base-table verify) + Cinemeta fetch building the watched index (the only place `stremio-watched-bitfield` is used, isolated in a pure `watched-decode.ts` helper). (3) `kevbox-import.ts` — CLI orchestrator, dry-run by default. Correctness lives entirely server-side in the `_for` functions; the converter only shapes payloads.

**Tech Stack:** TypeScript (strict, `@tsconfig/node18`, CommonJS), `pg` (node-postgres) for the admin DB connection, `axios` + `stremio-watched-bitfield` (already present), **vitest** for unit tests. Code lives in `/home/kevin/projects/trakt-stremio-import` (a **separate git repo** from NuvioTV — all `git` steps below run there).

**Spec:** `/home/kevin/projects/NuvioTV/docs/superpowers/specs/2026-06-14-stremio-kevbox-direct-import-design.md` (**rev 4**).

> **⚠️ PLAN rev 4 (2026-06-14) — read before implementing.** A second gap audit (34 confirmed findings) corrected this plan. Net changes folded into the tasks below: **(1)** ConvertStats gains per-subsystem **source counts** + **`bitfieldDriftIds`** (Task 1); **(2)** `movieWatched` uses the legacy **OR-rule** `flaggedWatched===1 || (lastWatched && timesWatched>0)` (Task 3); **(3)** `buildWatchedEpisodeIndex` adds **Cinemeta-drift detection** (serialized `lastVideoId` presence) returning `{index, driftIds}` (Task 8); **(4)** `pushBatched` **branches on PG SQLSTATE** 42501/42883 (Task 9); **(5)** the CLI computes **source-vs-dest reconciliation**, adds a **Trakt-connected precheck**, and **splits allowlisting out of `--commit`** with a forced-push precondition (Tasks 10/11); **(6)** the canary runbook is rewritten — passive "open the app" is a no-op, must **force a push** + **`--no-library`** for library-bearing members (Task 13). Option A is corrected here; **Option B (Kotlin client union patch) is a SEPARATE plan** (`2026-06-14-stremio-kevbox-import-clientB-merge-union.md`), required before fleet-wide C1.

**Scope note:** This is **Plan 1** (import core + CLI). The §9.4 merge-safety blocker is handled here by **corrected option A** (operational sequencing + CLI guards, no client code) — Tasks 11/13. **Option B (the Kotlin client union patch) is its own plan** and is mandated before fleet-wide C1. Plan 2 (creds vault + C1 poller for the ~320 tail) is a later follow-up that depends on both this plan and Option B.

---

## File Structure

In `/home/kevin/projects/trakt-stremio-import`:

| File | Responsibility | New? |
|---|---|---|
| `src/utils/kevbox-types.ts` | Payload interfaces (`WatchProgressEntry`, `WatchedItem`, `LibraryItem`), `KevboxPayloads`, `ConvertStats`, `WatchedEpisodeIndex`, `VerifyCounts` | new |
| `src/utils/watched-decode.ts` | Pure `decodeWatchedEpisodes(serialized, episodeList)` — the ONLY `stremio-watched-bitfield` user | new |
| `src/utils/convert-kevbox.ts` | Pure converter (no network, no bitfield) | new |
| `src/utils/kevbox.ts` | All KevBox I/O: `pg` client, owner/allowlist, Cinemeta watched-index, batched `_for` writes, base-table verify | new |
| `src/kevbox-import.ts` | CLI orchestrator (dry-run default, `--commit`, `--verify-only`, `--no-library`) | new |
| `scripts/kevbox_import.sh` | Thin wrapper reading `~/.config/stremio-kevbox-migration/app.env` | new |
| `test/watched-decode.test.ts`, `test/convert-kevbox.test.ts` | vitest unit tests | new |
| `package.json` | add `vitest` (dev), `pg` (dep), `@types/pg` (dev), `test` script | modify |

`src/utils/stremio.ts` (reader: `getLibrary`, `getCinemetaMeta`, `updateAuthKeyWithCredentials`) is reused unchanged.

---

## Task 0: Preflight — prove `_for` EXECUTE on the prod connection (hard go/no-go)

Spec §7.3 / §17 step 0. This is operational, not code — but the whole write path is dead if it fails, so it gates everything.

**Files:** none (operator runs a query).

- [ ] **Step 1: Read the admin connection string**

The `SUPABASE_DB_URL` lives in `/home/kevin/projects/NuvioTV/local.properties`. Read just that line:

Run: `grep -m1 SUPABASE_DB_URL /home/kevin/projects/NuvioTV/local.properties`
Expected: a `postgresql://postgres.<ref>:<pw>@...pooler.supabase.com:5432/postgres` URL.

- [ ] **Step 2: Run the EXECUTE preflight against prod**

Run (substitute the URL from Step 1):
```bash
psql "<SUPABASE_DB_URL>" -v ON_ERROR_STOP=1 -c "
select p.proname, r.rolname as owner,
       has_function_privilege(current_user, p.oid, 'EXECUTE') as can_exec
from pg_proc p join pg_roles r on r.oid = p.proowner
where p.proname like 'sync_push_%_for'
order by p.proname;"
```
Expected: 7 rows; for `sync_push_watch_progress_for`, `sync_push_watched_items_for`, `sync_push_library_for` → `can_exec = t`. If `owner` ≠ `current_user` and `can_exec = f`, **STOP** — the plan cannot proceed; add a `grant execute on function public.sync_push_<name>_for(uuid,int,jsonb) to <poller_role>;` migration first and re-run.

- [ ] **Step 3: Record the result in the spec**

Append the query output (owner + can_exec) under spec §7.3 as the verified preflight, replacing the "asserted; confirm via preflight" note. This converts the §7.3 claim from inference to fact.

---

## Task 1: Test infra (vitest) + shared types

**Files:**
- Modify: `/home/kevin/projects/trakt-stremio-import/package.json`
- Create: `/home/kevin/projects/trakt-stremio-import/src/utils/kevbox-types.ts`
- Create: `/home/kevin/projects/trakt-stremio-import/test/smoke.test.ts`

- [ ] **Step 1: Install vitest**

Run: `cd /home/kevin/projects/trakt-stremio-import && npm install -D vitest`
Expected: `vitest` added to `devDependencies`, no errors.

- [ ] **Step 2: Add the `test` script**

In `package.json`, add to `"scripts"`:
```json
"test": "vitest run"
```

- [ ] **Step 3: Write a smoke test**

Create `test/smoke.test.ts`:
```ts
import { describe, it, expect } from "vitest";

describe("smoke", () => {
  it("runs", () => {
    expect(1 + 1).toBe(2);
  });
});
```

- [ ] **Step 4: Run it to verify vitest works**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx vitest run test/smoke.test.ts`
Expected: PASS, 1 test.

- [ ] **Step 5: Create the shared types**

Create `src/utils/kevbox-types.ts`:
```ts
// Payload shapes verified against the deployed sync_push_*_for function bodies (spec §5).
export interface WatchProgressEntry {
  content_id: string;
  content_type: "movie" | "series";
  video_id: string;
  season?: number;
  episode?: number;
  position: number; // ms
  duration: number; // ms
  last_watched: number; // epoch ms, > 0
  progress_key: string; // content_id (movie) | `${content_id}_s${season}e${episode}` (episode)
}

export interface WatchedItem {
  content_id: string;
  content_type: "movie" | "series";
  title: string;
  season: number | null;
  episode: number | null;
  watched_at: number; // epoch ms
}

export interface LibraryItem {
  content_id: string;
  content_type: "movie" | "series";
  name: string;
  poster: string;
  poster_shape: string; // literal "POSTER"
  background: string;
  description: string;
  release_info: string;
  imdb_rating: number | null;
  genres: string[];
  addon_base_url: string;
  added_at: number; // epoch ms, 0 if unknown
}

export interface ConvertStats {
  totalItems: number;
  skippedRemoved: number;
  watchProgress: number;
  watchedItems: number;
  library: number;
  nonStandardIds: string[]; // non tt/tmdb:/trakt: ids (degraded-tile risk)
  // rev 4 — per-subsystem SOURCE denominators for §13 source-vs-dest reconciliation
  sourceMidPlay: number;        // items with timeOffset>0 && duration>0 (continue-watching candidates)
  sourceWatchedMovies: number;  // movies matching the §6.2 watched OR-rule
  sourceSaved: number;          // !removed && !temp (saved-library candidates)
  // rev 4 — series whose Cinemeta episode list drifted from the serialized bitfield (watched marks NOT decodable)
  bitfieldDriftIds: string[];
}

// rev 4 — options for the pure converter. `bitfieldDriftIds`/`now` injected so the converter stays deterministic.
export interface ConvertOptions {
  includeLibrary?: boolean;
  bitfieldDriftIds?: string[]; // from buildWatchedEpisodeIndex (network side)
  now?: number;                // epoch-ms fallback for watched_at; defaults to Date.now() only at the call site
}

export interface KevboxPayloads {
  watchProgress: WatchProgressEntry[];
  watchedItems: WatchedItem[];
  library: LibraryItem[];
  stats: ConvertStats;
}

// Per-series list of WATCHED episode video-ids ("<id>:<season>:<number>"), built network-side in kevbox.ts.
export interface WatchedEpisodeIndex {
  [contentId: string]: string[];
}

// rev 4 — buildWatchedEpisodeIndex returns the index PLUS the drift list (shows whose serialized lastVideoId
// was absent from the rebuilt episodeList → bitfield decoded to all-zero → watched marks lost, must be surfaced).
export interface WatchedIndexResult {
  index: WatchedEpisodeIndex;
  driftIds: string[];
}

export interface VerifyCounts {
  watchProgress: number;
  watchedItems: number;
  library: number;
}
```

- [ ] **Step 6: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add package.json package-lock.json test/smoke.test.ts src/utils/kevbox-types.ts
git commit -m "chore(kevbox): add vitest + shared import payload types"
```

---

## Task 2: Pure watched-episode decoder

Isolates `stremio-watched-bitfield` into one pure, tested function. Returns the subset of `episodeList` flagged watched. Robust to colon-containing ids (the serialized format is `<lastId>:<length>:<base64>` where `<lastId>` itself contains colons — so never index-parse it).

**Files:**
- Create: `/home/kevin/projects/trakt-stremio-import/src/utils/watched-decode.ts`
- Test: `/home/kevin/projects/trakt-stremio-import/test/watched-decode.test.ts`

- [ ] **Step 1: Write the failing test**

Create `test/watched-decode.test.ts`:
```ts
import { describe, it, expect } from "vitest";
// @ts-ignore
import watchedBitfield from "stremio-watched-bitfield";
import { decodeWatchedEpisodes, isBitfieldDrifted, serializedLastVideoId } from "../src/utils/watched-decode";

// Build a real Stremio `state.watched` string: 1 = watched, 0 = not.
function makeWatched(episodeList: string[], flags: number[]): string {
  return watchedBitfield.constructFromArray(flags, episodeList).serialize();
}

describe("decodeWatchedEpisodes", () => {
  it("returns only the watched episode ids", () => {
    const eps = ["tt1:1:1", "tt1:1:2", "tt1:1:3", "tt1:2:1"];
    const watched = makeWatched(eps, [1, 0, 1, 1]);
    expect(decodeWatchedEpisodes(watched, eps).sort()).toEqual(["tt1:1:1", "tt1:1:3", "tt1:2:1"]);
  });

  it("returns [] for an empty serialized string", () => {
    expect(decodeWatchedEpisodes("", ["tt1:1:1"])).toEqual([]);
  });

  it("returns [] for an empty episode list", () => {
    expect(decodeWatchedEpisodes("tt1:1:1:1:abc", [])).toEqual([]);
  });

  it("returns [] (never throws) on a malformed serialized string", () => {
    expect(decodeWatchedEpisodes("garbage", ["tt1:1:1"])).toEqual([]);
  });

  it("handles colon-containing ids (kitsu) without misparsing", () => {
    const eps = ["kitsu:42:1:1", "kitsu:42:1:2"];
    const watched = makeWatched(eps, [0, 1]);
    expect(decodeWatchedEpisodes(watched, eps)).toEqual(["kitsu:42:1:2"]);
  });

  // rev 4 (§15 g) — specials/season-0 decode alongside regular seasons
  it("decodes specials (season 0) alongside regular seasons", () => {
    const eps = ["tt1:1:1", "tt1:1:2", "tt1:0:1"];
    const watched = makeWatched(eps, [1, 0, 1]);
    expect(decodeWatchedEpisodes(watched, eps).sort()).toEqual(["tt1:0:1", "tt1:1:1"]);
  });
});

// rev 4 (§15 f) — pure drift detection (constructAndResize silently blanks a show when the serialized
// lastVideoId is absent from the rebuilt episode list). Tested with hand-crafted serialized strings so it
// does not depend on the library's serialize internals.
describe("serializedLastVideoId / isBitfieldDrifted", () => {
  it("parses the lastVideoId as everything before the final two tokens", () => {
    expect(serializedLastVideoId("tt1:1:5:120:AAAA")).toBe("tt1:1:5");
    expect(serializedLastVideoId("kitsu:42:2:7:8:abc")).toBe("kitsu:42:2:7");
    expect(serializedLastVideoId("garbage")).toBeNull();
    expect(serializedLastVideoId("")).toBeNull();
  });

  it("is TRUE when the lastVideoId is absent from the episode list (silent-blank case)", () => {
    expect(isBitfieldDrifted("tt1:1:5:5:AAAAAA", ["tt1:1:1", "tt1:1:2", "tt1:1:3"])).toBe(true);
  });

  it("is FALSE when the lastVideoId is present", () => {
    expect(isBitfieldDrifted("tt1:1:3:3:AAAA", ["tt1:1:1", "tt1:1:2", "tt1:1:3"])).toBe(false);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx vitest run test/watched-decode.test.ts`
Expected: FAIL — `Cannot find module '../src/utils/watched-decode'`.

- [ ] **Step 3: Implement the decoder**

Create `src/utils/watched-decode.ts`:
```ts
// @ts-ignore  (stremio-watched-bitfield ships no types)
import watchedBitfield from "stremio-watched-bitfield";

/**
 * Given Stremio's serialized `state.watched` bitfield and the Cinemeta-derived
 * ordered episode video-id list, return the subset that are flagged watched.
 * Pure: no network. Never throws — a malformed bitfield yields [].
 */
export function decodeWatchedEpisodes(
  watchedSerialized: string,
  episodeList: string[],
): string[] {
  if (!watchedSerialized || episodeList.length === 0) return [];
  let wb: { getVideo: (id: string) => unknown };
  try {
    wb = watchedBitfield.constructAndResize(watchedSerialized, episodeList);
  } catch {
    return [];
  }
  return episodeList.filter((ep) => !!wb.getVideo(ep));
}

/**
 * rev 4 — extract the lastVideoId from a serialized bitfield "<lastVideoId>:<length>:<base64>".
 * The lastVideoId itself contains colons (e.g. "tt1:1:5"), so it is everything before the final
 * two tokens. Returns null if the string isn't in that shape. Pure.
 */
export function serializedLastVideoId(watchedSerialized: string): string | null {
  if (!watchedSerialized) return null;
  const parts = watchedSerialized.split(":");
  if (parts.length < 3) return null;
  return parts.slice(0, parts.length - 2).join(":");
}

/**
 * rev 4 — detect Cinemeta episode-set DRIFT. `constructAndResize` silently returns an all-zero
 * bitfield (no throw) when the serialized lastVideoId is absent from `episodeList`, so a drifted
 * show decodes to [] and looks genuinely-0-watched. `true` ⇒ the marks are NOT decodable and must
 * be surfaced rather than emitted as empty. Pure.
 */
export function isBitfieldDrifted(watchedSerialized: string, episodeList: string[]): boolean {
  const lastId = serializedLastVideoId(watchedSerialized);
  if (!lastId || episodeList.length === 0) return false;
  return episodeList.indexOf(lastId) === -1;
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx vitest run test/watched-decode.test.ts`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add src/utils/watched-decode.ts test/watched-decode.test.ts
git commit -m "feat(kevbox): pure watched-bitfield decoder (colon-id safe)"
```

---

## Task 3: Converter — movies (watch_progress + watched_items)

**Files:**
- Create: `/home/kevin/projects/trakt-stremio-import/src/utils/convert-kevbox.ts`
- Test: `/home/kevin/projects/trakt-stremio-import/test/convert-kevbox.test.ts`

- [ ] **Step 1: Write the failing test**

Create `test/convert-kevbox.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { StremioLibraryObject } from "../src/utils/stremio";
import { convertStremioToKevbox } from "../src/utils/convert-kevbox";

// Minimal builder for a Stremio library object with state overrides.
function obj(over: Partial<StremioLibraryObject> & { _id: string; type: "movie" | "series" }, state: Partial<StremioLibraryObject["state"]> = {}): StremioLibraryObject {
  return {
    _id: over._id, type: over.type, name: over.name ?? "Title",
    removed: over.removed ?? false, temp: over.temp ?? false,
    _ctime: over._ctime ?? "2026-01-01T00:00:00.000Z", _mtime: "2026-01-01T00:00:00.000Z",
    poster: over.poster ?? "", background: over.background ?? "", logo: "", year: over.year ?? "2020",
    state: {
      lastWatched: "2026-06-01T10:00:00.000Z", timeWatched: 0, timeOffset: 0, overallTimeWatched: 0,
      timesWatched: 0, flaggedWatched: 0, duration: 0, video_id: "", watched: "", noNotif: false,
      season: 0, episode: 0, ...state,
    } as StremioLibraryObject["state"],
  };
}

describe("convertStremioToKevbox — movies", () => {
  it("emits a watch_progress entry for a mid-play movie", () => {
    const lib = [obj({ _id: "tt100", type: "movie" }, { timeOffset: 600000, duration: 5400000, video_id: "tt100" })];
    const { watchProgress } = convertStremioToKevbox(lib, {});
    expect(watchProgress).toEqual([{
      content_id: "tt100", content_type: "movie", video_id: "tt100",
      position: 600000, duration: 5400000, last_watched: Date.parse("2026-06-01T10:00:00.000Z"),
      progress_key: "tt100",
    }]);
  });

  it("falls back video_id to _id when state.video_id is blank", () => {
    const lib = [obj({ _id: "tt101", type: "movie" }, { timeOffset: 10000, duration: 90000, video_id: "" })];
    expect(convertStremioToKevbox(lib, {}).watchProgress[0].video_id).toBe("tt101");
  });

  it("skips the junk position<=1 && duration<=1 case", () => {
    const lib = [obj({ _id: "tt102", type: "movie" }, { timeOffset: 1, duration: 1 })];
    expect(convertStremioToKevbox(lib, {}).watchProgress).toEqual([]);
  });

  it("emits a watched_items row for a flaggedWatched movie", () => {
    const lib = [obj({ _id: "tt103", type: "movie", name: "Heat" }, { flaggedWatched: 1 })];
    const { watchedItems } = convertStremioToKevbox(lib, {});
    expect(watchedItems).toEqual([{
      content_id: "tt103", content_type: "movie", title: "Heat",
      season: null, episode: null, watched_at: Date.parse("2026-06-01T10:00:00.000Z"),
    }]);
  });

  it("does not emit watched_items for an unwatched movie", () => {
    const lib = [obj({ _id: "tt104", type: "movie" }, { flaggedWatched: 0 })];
    expect(convertStremioToKevbox(lib, {}).watchedItems).toEqual([]);
  });

  // rev 4 — §6.2 OR-rule: finished movie via timesWatched even when flaggedWatched=0
  it("emits watched_items for a finished movie via timesWatched even when flaggedWatched=0", () => {
    const lib = [obj({ _id: "tt105", type: "movie", name: "Drive" }, { flaggedWatched: 0, timesWatched: 2, lastWatched: "2026-05-01T00:00:00.000Z" })];
    expect(convertStremioToKevbox(lib, {}).watchedItems).toEqual([{
      content_id: "tt105", content_type: "movie", title: "Drive",
      season: null, episode: null, watched_at: Date.parse("2026-05-01T00:00:00.000Z"),
    }]);
  });

  it("does NOT count a movie as watched when timesWatched=0 and not flagged", () => {
    const lib = [obj({ _id: "tt106", type: "movie" }, { flaggedWatched: 0, timesWatched: 0 })];
    expect(convertStremioToKevbox(lib, {}).watchedItems).toEqual([]);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx vitest run test/convert-kevbox.test.ts`
Expected: FAIL — `Cannot find module '../src/utils/convert-kevbox'`.

- [ ] **Step 3: Implement the converter shell + movie branch**

Create `src/utils/convert-kevbox.ts`:
```ts
import { StremioLibraryObject } from "./stremio";
import {
  WatchProgressEntry, WatchedItem, LibraryItem,
  KevboxPayloads, WatchedEpisodeIndex, ConvertOptions,
} from "./kevbox-types";

function parseEpochMs(iso?: string): number {
  if (!iso) return NaN;
  return Date.parse(iso);
}

function isStandardId(id: string): boolean {
  return id.startsWith("tt") || id.startsWith("tmdb:") || id.startsWith("trakt:");
}

function movieWatchProgress(o: StremioLibraryObject): WatchProgressEntry | null {
  const s = o.state;
  if (!(s.timeOffset > 0 && s.duration > 0)) return null;
  if (s.timeOffset <= 1 && s.duration <= 1) return null; // junk
  const last = parseEpochMs(s.lastWatched);
  if (!(last > 0)) return null; // drop NaN/0 — protects the §9 lastWatched>0 client guard
  return {
    content_id: o._id, content_type: "movie",
    video_id: s.video_id || o._id,
    position: s.timeOffset, duration: s.duration,
    last_watched: last, progress_key: o._id,
  };
}

// rev 4 — watched if explicitly flagged OR finished-by-signal (lastWatched + timesWatched), matching legacy sync.ts:133-138.
function isMovieWatched(s: StremioLibraryObject["state"]): boolean {
  return s.flaggedWatched === 1 || (!!s.lastWatched && s.timesWatched > 0);
}

function movieWatched(o: StremioLibraryObject, now: number): WatchedItem | null {
  if (!isMovieWatched(o.state)) return null;
  const at = parseEpochMs(o.state.lastWatched);
  return {
    content_id: o._id, content_type: "movie", title: o.name,
    season: null, episode: null,
    watched_at: at > 0 ? at : now, // fallback OK for watched_items (benign union metadata); now injected for determinism
  };
}

export function convertStremioToKevbox(
  library: StremioLibraryObject[],
  watchedIndex: WatchedEpisodeIndex,
  opts: ConvertOptions = {},
): KevboxPayloads {
  const now = opts.now ?? Date.now(); // injected for determinism in tests; runtime fallback otherwise
  const watchProgress: WatchProgressEntry[] = [];
  const watchedItems: WatchedItem[] = [];
  const libraryOut: LibraryItem[] = [];
  const nonStandard = new Set<string>();
  let skippedRemoved = 0;
  // rev 4 — source denominators for §13 reconciliation
  let sourceMidPlay = 0, sourceWatchedMovies = 0, sourceSaved = 0;

  for (const o of library) {
    if (o.removed) { skippedRemoved++; continue; }
    if (!isStandardId(o._id)) nonStandard.add(o._id);

    const s = o.state;
    if (s.timeOffset > 0 && s.duration > 0) sourceMidPlay++;
    if (!o.temp) sourceSaved++;

    if (o.type === "movie") {
      if (isMovieWatched(s)) sourceWatchedMovies++;
      const wp = movieWatchProgress(o);
      if (wp) watchProgress.push(wp);
      const w = movieWatched(o, now);
      if (w) watchedItems.push(w);
    }
    // series + library branches added in later tasks
  }

  return {
    watchProgress, watchedItems, library: libraryOut,
    stats: {
      totalItems: library.length, skippedRemoved,
      watchProgress: watchProgress.length, watchedItems: watchedItems.length,
      library: libraryOut.length, nonStandardIds: [...nonStandard],
      sourceMidPlay, sourceWatchedMovies, sourceSaved,
      bitfieldDriftIds: opts.bitfieldDriftIds ?? [],
    },
  };
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx vitest run test/convert-kevbox.test.ts`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add src/utils/convert-kevbox.ts test/convert-kevbox.test.ts
git commit -m "feat(kevbox): converter movie branch (watch_progress + watched_items)"
```

---

## Task 4: Converter — series watch_progress (skip-missing-S/E, video_id fallback, NaN drop)

**Files:**
- Modify: `/home/kevin/projects/trakt-stremio-import/src/utils/convert-kevbox.ts`
- Test: `/home/kevin/projects/trakt-stremio-import/test/convert-kevbox.test.ts`

- [ ] **Step 1: Write the failing tests**

Append to `test/convert-kevbox.test.ts`:
```ts
describe("convertStremioToKevbox — series watch_progress", () => {
  it("emits an episode watch_progress entry with the _sNeM progress_key", () => {
    const lib = [obj({ _id: "tt200", type: "series" }, {
      timeOffset: 120000, duration: 2400000, season: 1, episode: 5, video_id: "tt200:1:5",
    })];
    const { watchProgress } = convertStremioToKevbox(lib, {});
    expect(watchProgress[0]).toMatchObject({
      content_id: "tt200", content_type: "series", season: 1, episode: 5,
      video_id: "tt200:1:5", progress_key: "tt200_s1e5",
    });
  });

  it("derives video_id from id:S:E when state.video_id is blank", () => {
    const lib = [obj({ _id: "tt201", type: "series" }, { timeOffset: 5000, duration: 60000, season: 2, episode: 3, video_id: "" })];
    expect(convertStremioToKevbox(lib, {}).watchProgress[0].video_id).toBe("tt201:2:3");
  });

  it("SKIPS a series resume row missing season/episode (never emits a bare-_id key)", () => {
    const lib = [obj({ _id: "tt202", type: "series" }, { timeOffset: 5000, duration: 60000, season: 0, episode: 0 })];
    expect(convertStremioToKevbox(lib, {}).watchProgress).toEqual([]);
  });

  it("drops an entry whose lastWatched is unparseable (NaN)", () => {
    const lib = [obj({ _id: "tt203", type: "series" }, { timeOffset: 5000, duration: 60000, season: 1, episode: 1, lastWatched: "" })];
    expect(convertStremioToKevbox(lib, {}).watchProgress).toEqual([]);
  });
});
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx vitest run test/convert-kevbox.test.ts`
Expected: FAIL — the four series tests fail (no series watch_progress yet).

- [ ] **Step 3: Add the series watch_progress function and wire it**

In `src/utils/convert-kevbox.ts`, add after `movieWatched`:
```ts
function seriesWatchProgress(o: StremioLibraryObject): WatchProgressEntry | null {
  const s = o.state;
  if (!(s.timeOffset > 0 && s.duration > 0)) return null;
  if (s.timeOffset <= 1 && s.duration <= 1) return null;
  const last = parseEpochMs(s.lastWatched);
  if (!(last > 0)) return null;
  const season = s.season, episode = s.episode;
  if (!(season > 0 && episode > 0)) return null; // skip — never a bare-_id key for a series (avoids slot collision)
  return {
    content_id: o._id, content_type: "series",
    video_id: s.video_id || `${o._id}:${season}:${episode}`,
    season, episode,
    position: s.timeOffset, duration: s.duration,
    last_watched: last, progress_key: `${o._id}_s${season}e${episode}`,
  };
}
```

In `convertStremioToKevbox`, replace the `// series + library branches added in later tasks` comment with:
```ts
    else if (o.type === "series") {
      const wp = seriesWatchProgress(o);
      if (wp) watchProgress.push(wp);
    }
```

- [ ] **Step 4: Run to verify all pass**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx vitest run test/convert-kevbox.test.ts`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add src/utils/convert-kevbox.ts test/convert-kevbox.test.ts
git commit -m "feat(kevbox): converter series watch_progress (skip missing S/E, video_id fallback)"
```

---

## Task 5: Converter — series watched_items from the injected index

**Files:**
- Modify: `/home/kevin/projects/trakt-stremio-import/src/utils/convert-kevbox.ts`
- Test: `/home/kevin/projects/trakt-stremio-import/test/convert-kevbox.test.ts`

- [ ] **Step 1: Write the failing tests**

Append to `test/convert-kevbox.test.ts`:
```ts
describe("convertStremioToKevbox — series watched_items", () => {
  it("emits one watched_items row per watched episode from the index", () => {
    const lib = [obj({ _id: "tt300", type: "series", name: "Show" }, { watched: "x" })];
    const index = { tt300: ["tt300:1:1", "tt300:1:3"] };
    const { watchedItems } = convertStremioToKevbox(lib, index);
    expect(watchedItems).toEqual([
      { content_id: "tt300", content_type: "series", title: "Show", season: 1, episode: 1, watched_at: Date.parse("2026-06-01T10:00:00.000Z") },
      { content_id: "tt300", content_type: "series", title: "Show", season: 1, episode: 3, watched_at: Date.parse("2026-06-01T10:00:00.000Z") },
    ]);
  });

  it("parses season/episode from the LAST two colon segments (colon-containing ids)", () => {
    const lib = [obj({ _id: "kitsu:42", type: "series", name: "Anime" }, { watched: "x" })];
    const index = { "kitsu:42": ["kitsu:42:2:7"] };
    expect(convertStremioToKevbox(lib, index).watchedItems[0]).toMatchObject({ season: 2, episode: 7 });
  });

  it("emits nothing for a series with no index entry (e.g. Cinemeta miss) — never crashes", () => {
    const lib = [obj({ _id: "tt301", type: "series" }, { watched: "x" })];
    expect(convertStremioToKevbox(lib, {}).watchedItems).toEqual([]);
  });
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx vitest run test/convert-kevbox.test.ts`
Expected: FAIL — three new tests fail (no series watched_items yet).

- [ ] **Step 3: Add the series watched_items function and wire it**

In `src/utils/convert-kevbox.ts`, add after `seriesWatchProgress`:
```ts
function seriesWatched(o: StremioLibraryObject, watchedIndex: WatchedEpisodeIndex, now: number): WatchedItem[] {
  const watchedIds = watchedIndex[o._id];
  if (!watchedIds || watchedIds.length === 0) return [];
  const at0 = parseEpochMs(o.state.lastWatched);
  const at = at0 > 0 ? at0 : now;
  const out: WatchedItem[] = [];
  for (const ep of watchedIds) {
    const parts = ep.split(":");
    const episode = parseInt(parts[parts.length - 1], 10);
    const season = parseInt(parts[parts.length - 2], 10);
    if (Number.isNaN(season) || Number.isNaN(episode)) continue;
    out.push({ content_id: o._id, content_type: "series", title: o.name, season, episode, watched_at: at });
  }
  return out;
}
```

In the `else if (o.type === "series")` branch of `convertStremioToKevbox`, add below the `wp` lines:
```ts
      watchedItems.push(...seriesWatched(o, watchedIndex, now));
```

- [ ] **Step 4: Run to verify all pass**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx vitest run test/convert-kevbox.test.ts`
Expected: PASS (14 tests).

- [ ] **Step 5: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add src/utils/convert-kevbox.ts test/convert-kevbox.test.ts
git commit -m "feat(kevbox): converter series watched_items from injected index"
```

---

## Task 6: Converter — library branch + stats

**Files:**
- Modify: `/home/kevin/projects/trakt-stremio-import/src/utils/convert-kevbox.ts`
- Test: `/home/kevin/projects/trakt-stremio-import/test/convert-kevbox.test.ts`

- [ ] **Step 1: Write the failing tests**

Append to `test/convert-kevbox.test.ts`:
```ts
describe("convertStremioToKevbox — library + stats", () => {
  it("emits library items only when includeLibrary is set, skipping temp", () => {
    const lib = [
      obj({ _id: "tt400", type: "movie", name: "Saved", year: "2019", _ctime: "2025-12-31T00:00:00.000Z" }),
      obj({ _id: "tt401", type: "movie", name: "Temp", temp: true }),
    ];
    const off = convertStremioToKevbox(lib, {});
    expect(off.library).toEqual([]);
    const on = convertStremioToKevbox(lib, {}, { includeLibrary: true });
    expect(on.library).toEqual([{
      content_id: "tt400", content_type: "movie", name: "Saved", poster: "", poster_shape: "POSTER",
      background: "", description: "", release_info: "2019", imdb_rating: null, genres: [],
      addon_base_url: "", added_at: Date.parse("2025-12-31T00:00:00.000Z"),
    }]);
  });

  it("coalesces added_at to 0 when _ctime is missing", () => {
    const lib = [obj({ _id: "tt402", type: "movie", _ctime: "" })];
    expect(convertStremioToKevbox(lib, {}, { includeLibrary: true }).library[0].added_at).toBe(0);
  });

  it("reports non-standard ids and skipped-removed in stats", () => {
    const lib = [
      obj({ _id: "kitsu:9", type: "movie" }, { flaggedWatched: 1 }),
      obj({ _id: "tt500", type: "movie", removed: true }),
    ];
    const { stats } = convertStremioToKevbox(lib, {});
    expect(stats.nonStandardIds).toEqual(["kitsu:9"]);
    expect(stats.skippedRemoved).toBe(1);
    expect(stats.totalItems).toBe(2);
  });

  // rev 4 — §13 source denominators + injected drift ids
  it("reports per-subsystem source counts for reconciliation", () => {
    const lib = [
      obj({ _id: "tt600", type: "movie" }, { flaggedWatched: 1, timeOffset: 100, duration: 1000 }), // saved + watched-movie + mid-play
      obj({ _id: "tt601", type: "movie", temp: true }, { timeOffset: 50, duration: 500 }),           // mid-play, NOT saved (temp)
      obj({ _id: "tt602", type: "movie", removed: true }),                                            // skipped entirely
    ];
    const { stats } = convertStremioToKevbox(lib, {}, { bitfieldDriftIds: ["tt999"] });
    expect(stats.sourceMidPlay).toBe(2);
    expect(stats.sourceWatchedMovies).toBe(1);
    expect(stats.sourceSaved).toBe(1);
    expect(stats.bitfieldDriftIds).toEqual(["tt999"]);
  });
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx vitest run test/convert-kevbox.test.ts`
Expected: FAIL — library is always `[]` so the first two library tests fail.

- [ ] **Step 3: Add the library function and wire it**

In `src/utils/convert-kevbox.ts`, add after `seriesWatched`:
```ts
function libraryItem(o: StremioLibraryObject): LibraryItem | null {
  if (o.temp) return null; // removed already filtered upstream
  let added = parseEpochMs(o._ctime);
  if (!(added > 0)) added = 0;
  return {
    content_id: o._id, content_type: o.type, name: o.name || "", // rev 4 — guard like poster/background
    poster: o.poster || "", poster_shape: "POSTER", background: o.background || "",
    description: "", release_info: o.year || "", imdb_rating: null, genres: [],
    addon_base_url: "", added_at: added,
  };
}
```

In `convertStremioToKevbox`, immediately after the `if (o.type === "movie") {...} else if (o.type === "series") {...}` block (still inside the `for` loop), add:
```ts
    if (opts.includeLibrary) {
      const lib = libraryItem(o);
      if (lib) libraryOut.push(lib);
    }
```

- [ ] **Step 4: Run the full converter suite**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx vitest run`
Expected: PASS (all converter + decoder + smoke tests, 16+ tests).

- [ ] **Step 5: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add src/utils/convert-kevbox.ts test/convert-kevbox.test.ts
git commit -m "feat(kevbox): converter library branch + stats (temp filter, NaN added_at)"
```

---

## Task 7: KevBox DB client — connection, owner resolution, allowlist

**Files:**
- Modify: `/home/kevin/projects/trakt-stremio-import/package.json`
- Create: `/home/kevin/projects/trakt-stremio-import/src/utils/kevbox.ts`

This task is I/O against the live prod DB; verification is a manual connectivity check (no unit test — there is no test DB for the prod login path; spec §15 makes these integration-only).

- [ ] **Step 1: Install pg**

Run: `cd /home/kevin/projects/trakt-stremio-import && npm install pg && npm install -D @types/pg`
Expected: `pg` in dependencies, `@types/pg` in devDependencies.

- [ ] **Step 2: Create kevbox.ts with the connection + owner/allowlist helpers**

Create `src/utils/kevbox.ts`:
```ts
import { Client } from "pg";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function createDbClient(connectionString: string): Client {
  // Supabase pooler presents a cert the default chain rejects (operator-known gotcha).
  return new Client({ connectionString, ssl: { rejectUnauthorized: false } });
}

/** Resolve a member reference (auth UID or KevBox email) to the owner uuid. */
export async function resolveOwner(db: Client, memberRef: string): Promise<string> {
  if (UUID_RE.test(memberRef)) return memberRef;
  const res = await db.query<{ id: string }>("select id from auth.users where email = $1", [memberRef]);
  if (res.rows.length === 0) throw new Error(`No KevBox member found for "${memberRef}"`);
  return res.rows[0].id;
}

export async function isAllowlisted(db: Client, owner: string): Promise<boolean> {
  const res = await db.query("select 1 from public.sync_canary_members where user_id = $1", [owner]);
  return res.rows.length > 0;
}

export async function allowlist(db: Client, owner: string): Promise<void> {
  await db.query(
    "insert into public.sync_canary_members(user_id) values ($1) on conflict do nothing",
    [owner],
  );
}
```

- [ ] **Step 3: Verify connectivity + owner resolution against prod**

Run (substitute `SUPABASE_DB_URL` from Task 0 and a known member email):
```bash
cd /home/kevin/projects/trakt-stremio-import
SUPABASE_DB_URL="<url>" npx ts-node -e '
import { createDbClient, resolveOwner, isAllowlisted } from "./src/utils/kevbox";
(async () => {
  const db = createDbClient(process.env.SUPABASE_DB_URL!);
  await db.connect();
  const owner = await resolveOwner(db, "kevin.chiha@gmail.com");
  console.log("owner:", owner, "allowlisted:", await isAllowlisted(db, owner));
  await db.end();
})();'
```
Expected: prints the operator's uuid and `allowlisted: true` (Kevin is the cloud-restore canary).

- [ ] **Step 4: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add package.json package-lock.json src/utils/kevbox.ts
git commit -m "feat(kevbox): pg admin client — owner resolution + allowlist helpers"
```

---

## Task 8: KevBox client — Cinemeta watched-episode index builder

**Files:**
- Modify: `/home/kevin/projects/trakt-stremio-import/src/utils/kevbox.ts`

- [ ] **Step 1: Add the index builder**

In `src/utils/kevbox.ts`, add imports at the top:
```ts
import { StremioAPIClient, StremioLibraryObject } from "./stremio";
import { WatchedEpisodeIndex, WatchedIndexResult } from "./kevbox-types";
import { decodeWatchedEpisodes, isBitfieldDrifted } from "./watched-decode";
```
Then append:
```ts
/**
 * For every watched series, fetch its Cinemeta episode list and decode the
 * watched subset. Network lives here so the converter stays pure. A Cinemeta
 * miss (e.g. non-tt id) logs a warning and contributes no episodes.
 * rev 4 — also detects Cinemeta episode-set DRIFT (serialized lastVideoId absent
 * from the rebuilt list → constructAndResize returns all-zero, silently dropping
 * the show's watched marks) and surfaces those ids in `driftIds` instead of
 * emitting a misleading 0-watched result.
 */
export async function buildWatchedEpisodeIndex(
  library: StremioLibraryObject[],
): Promise<WatchedIndexResult> {
  const index: WatchedEpisodeIndex = {};
  const driftIds: string[] = [];
  const series = library.filter((o) => o.type === "series" && !o.removed && !!o.state.watched);
  for (const o of series) {
    try {
      const meta = await StremioAPIClient.getCinemetaMeta(o._id);
      const videos = meta?.meta?.videos ?? []; // rev 4 — guard malformed body, not just missing meta
      if (videos.length === 0) {
        console.warn(`Cinemeta: no episodes for ${o._id} (watched episodes skipped)`);
        continue;
      }
      // Mirror convert.ts: real seasons first, then specials (season 0).
      const episodeList = videos
        .filter((v) => v.season > 0)
        .concat(videos.filter((v) => v.season === 0))
        .map((v) => `${o._id}:${v.season}:${v.number}`);
      // rev 4 — drift guard: constructAndResize returns an ALL-ZERO bitfield (no throw) when the serialized
      // lastVideoId is absent from this episodeList → the show's watched marks silently vanish. Detect + surface.
      if (isBitfieldDrifted(o.state.watched, episodeList)) {
        console.warn(`Cinemeta drift for ${o._id}: serialized lastVideoId not in current episode list — watched marks NOT decodable, flagging`);
        driftIds.push(o._id);
        continue; // do not emit a misleading 0-watched result for a drifted show
      }
      const watched = decodeWatchedEpisodes(o.state.watched, episodeList);
      if (watched.length > 0) index[o._id] = watched;
    } catch (e) {
      console.warn(`Cinemeta lookup failed for ${o._id}: ${(e as Error).message}`);
    }
  }
  return { index, driftIds };
}
```

- [ ] **Step 2: Type-check**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 3: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add src/utils/kevbox.ts
git commit -m "feat(kevbox): Cinemeta watched-episode index builder"
```

---

## Task 9: KevBox client — batched `_for` writes + base-table verify

**Files:**
- Modify: `/home/kevin/projects/trakt-stremio-import/src/utils/kevbox.ts`

- [ ] **Step 1: Add batched writers and the verifier**

In `src/utils/kevbox.ts`, extend the imports:
```ts
import { WatchedEpisodeIndex, WatchedIndexResult, WatchProgressEntry, WatchedItem, LibraryItem, VerifyCounts } from "./kevbox-types";
```
(replace the existing `import { WatchedEpisodeIndex, WatchedIndexResult } from "./kevbox-types";` line from Task 8 with the line above).
Then append:
```ts
const BATCH = 500;

async function pushBatched(db: Client, fn: string, owner: string, items: unknown[]): Promise<number> {
  let written = 0;
  for (let i = 0; i < items.length; i += BATCH) {
    const slice = items.slice(i, i + BATCH);
    try {
      // ⚠️ arg order is (owner, profile_id=1, payload) — profile_id is SECOND for the _for fns.
      await db.query(`select public.${fn}($1::uuid, 1, $2::jsonb)`, [owner, JSON.stringify(slice)]);
      written += slice.length;
    } catch (e) {
      // rev 4 §12 — branch on PG SQLSTATE so the operator gets an actionable message, not a raw string.
      const code = (e as { code?: string }).code;
      if (code === "42501") {
        throw new Error(`${fn}: permission denied (SQLSTATE 42501) — the connecting role lacks EXECUTE on the _for function. Run the §7.3 preflight / add a GRANT EXECUTE migration.`);
      }
      if (code === "42883") {
        throw new Error(`${fn}: function does not exist (SQLSTATE 42883) — cloud-restore schema not deployed, or the _for signature differs. Verify deployment (§7.3).`);
      }
      throw new Error(`${fn} batch [${i}, ${i + slice.length}) failed${code ? ` [${code}]` : ""}: ${(e as Error).message}`);
    }
  }
  return written;
}

export const pushWatchProgress = (db: Client, owner: string, e: WatchProgressEntry[]) =>
  pushBatched(db, "sync_push_watch_progress_for", owner, e);
export const pushWatchedItems = (db: Client, owner: string, i: WatchedItem[]) =>
  pushBatched(db, "sync_push_watched_items_for", owner, i);
export const pushLibrary = (db: Client, owner: string, i: LibraryItem[]) =>
  pushBatched(db, "sync_push_library_for", owner, i);

/** Ground-truth verify: read the base tables directly (bypasses the owner-gated pull RPCs). */
export async function verifyCounts(db: Client, owner: string): Promise<VerifyCounts> {
  const count = async (table: string): Promise<number> => {
    const r = await db.query<{ n: number }>(
      `select count(*)::int as n from public.${table} where user_id = $1 and profile_id = 1`,
      [owner],
    );
    return r.rows[0].n;
  };
  return {
    watchProgress: await count("watch_progress"),
    watchedItems: await count("watched_items"),
    library: await count("library"),
  };
}
```

- [ ] **Step 2: Type-check**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 3: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add src/utils/kevbox.ts
git commit -m "feat(kevbox): batched _for writes + base-table verify"
```

---

## Task 10: CLI — arg parsing, env loader, dry-run flow

**Files:**
- Create: `/home/kevin/projects/trakt-stremio-import/src/kevbox-import.ts`

- [ ] **Step 1: Create the CLI with parsing, env, and the dry-run path**

Create `src/kevbox-import.ts`:
```ts
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { StremioAPIClient } from "./utils/stremio";
import { convertStremioToKevbox } from "./utils/convert-kevbox";
import { ConvertStats, VerifyCounts } from "./utils/kevbox-types";
import * as kb from "./utils/kevbox";

interface Args {
  memberRef: string;
  authKey?: string;
  email?: string;
  password?: string;
  commit: boolean;
  noLibrary: boolean;
  verifyOnly: boolean;
  // rev 4 — allowlisting is now an explicit, separate action; --force bypasses the no-prior-state safety refusal;
  // --allow-trakt-connected bypasses the Trakt precheck.
  allowlist: boolean;
  force: boolean;
  allowTraktConnected: boolean;
}

function parseArgs(argv: string[]): Args {
  const get = (flag: string): string | undefined => {
    const i = argv.indexOf(flag);
    return i >= 0 ? argv[i + 1] : undefined;
  };
  const memberRef = get("--member");
  if (!memberRef) throw new Error("Usage: kevbox-import --member <uid|email> (--authkey K | --email E --password P) [--allowlist] [--commit] [--force] [--no-library] [--allow-trakt-connected] [--verify-only]");
  return {
    memberRef,
    authKey: get("--authkey"),
    email: get("--email"),
    password: get("--password"),
    commit: argv.includes("--commit"),
    noLibrary: argv.includes("--no-library"),
    verifyOnly: argv.includes("--verify-only"),
    allowlist: argv.includes("--allowlist"),
    force: argv.includes("--force"),
    allowTraktConnected: argv.includes("--allow-trakt-connected"),
  };
}

function loadEnv(): { SUPABASE_DB_URL: string } {
  const p = path.join(os.homedir(), ".config", "stremio-kevbox-migration", "app.env");
  const url = process.env.SUPABASE_DB_URL ?? readEnvVar(p, "SUPABASE_DB_URL");
  if (!url) throw new Error(`SUPABASE_DB_URL not set (env or ${p})`);
  return { SUPABASE_DB_URL: url };
}

function readEnvVar(file: string, key: string): string | undefined {
  if (!fs.existsSync(file)) return undefined;
  for (const line of fs.readFileSync(file, "utf8").split("\n")) {
    const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*)\s*$/);
    if (m && m[1] === key) return m[2].replace(/^["']|["']$/g, "");
  }
  return undefined;
}

// rev 4 §13 — source-vs-(converted)-vs-dest reconciliation. dest=null for dry-run (no server read yet).
// watched_items has no single source denominator (movies via flags + series via bitfield), so it reports the
// movie portion and notes the series portion is bitfield-derived. Server read = ground truth.
function reconcile(stats: ConvertStats, dest: { before: VerifyCounts; after: VerifyCounts } | null): void {
  const tail = (b?: number, a?: number) => (dest ? `  dest ${b}→${a} (Δ+${(a ?? 0) - (b ?? 0)})` : "");
  console.log(`reconciliation (source → converted${dest ? " → dest" : ""}; server read = ground truth):`);
  console.log(`  watch_progress: source(mid-play)=${stats.sourceMidPlay} converted=${stats.watchProgress}${tail(dest?.before.watchProgress, dest?.after.watchProgress)}`);
  console.log(`  watched_items : source(movies)=${stats.sourceWatchedMovies}+series(bitfield) converted=${stats.watchedItems}${tail(dest?.before.watchedItems, dest?.after.watchedItems)}`);
  console.log(`  library       : source(saved)=${stats.sourceSaved} converted=${stats.library}${tail(dest?.before.library, dest?.after.library)}`);
  if (dest && (dest.after.watchProgress < dest.before.watchProgress || dest.after.watchedItems < dest.before.watchedItems || dest.after.library < dest.before.library)) {
    console.warn("⚠️  a dest count DROPPED after import — investigate (expected after >= before).");
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const env = loadEnv();
  const db = kb.createDbClient(env.SUPABASE_DB_URL);
  await db.connect();
  try {
    const owner = await kb.resolveOwner(db, args.memberRef);
    console.log(`owner: ${owner}`);

    if (args.verifyOnly) {
      const allow = await kb.isAllowlisted(db, owner);
      const counts = await kb.verifyCounts(db, owner);
      console.log(`allowlisted=${allow}`, "verify:", counts);
      return;
    }

    let authKey = args.authKey;
    if (!authKey && args.email && args.password) {
      authKey = await StremioAPIClient.updateAuthKeyWithCredentials({ email: args.email, password: args.password });
    }
    if (!authKey) throw new Error("Provide --authkey or --email/--password");

    const { result: library } = await StremioAPIClient.getLibrary(authKey);
    if (library.length === 0) throw new Error("Stremio library returned 0 items — refusing to proceed (stale token or empty account)");
    const { index, driftIds } = await kb.buildWatchedEpisodeIndex(library); // rev 4 — index + Cinemeta-drift ids
    const payloads = convertStremioToKevbox(library, index, { includeLibrary: !args.noLibrary, bitfieldDriftIds: driftIds });

    console.log(`source library items: ${library.length}`);
    console.log("converted:", payloads.stats);
    if (payloads.stats.nonStandardIds.length) console.log("non-standard ids:", payloads.stats.nonStandardIds);
    if (payloads.stats.bitfieldDriftIds.length) console.warn(`⚠️  Cinemeta drift — watched marks NOT imported for: ${payloads.stats.bitfieldDriftIds.join(", ")}`);

    if (!args.commit) {
      // rev 4 §13 — show source-vs-converted so under-import is visible even in dry-run (dest side added on --commit).
      reconcile(payloads.stats, null);
      console.log(`[dry-run] wp=${payloads.watchProgress.length} wi=${payloads.watchedItems.length} lib=${payloads.library.length} — nothing written. Re-run with --commit.`);
      return;
    }
    // commit path is added in Task 11
    throw new Error("--commit path not yet implemented");
  } finally {
    await db.end();
  }
}

main().catch((e) => { console.error("ERROR:", e.message); process.exit(1); });
```

- [ ] **Step 2: Verify the dry-run against a real member (integration)**

Run (substitute Kevin's Stremio authkey from `~/projects/trakt-stremio-import/runtime-config.json` or `--email/--password`, and his uid):
```bash
cd /home/kevin/projects/trakt-stremio-import
SUPABASE_DB_URL="<url>" npx ts-node src/kevbox-import.ts --member kevin.chiha@gmail.com --authkey "<stremio_authkey>"
```
Expected: prints `owner: <uuid>`, source item count, the `stats` object, and a `[dry-run] ... nothing written` line. No DB writes.

- [ ] **Step 3: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add src/kevbox-import.ts
git commit -m "feat(kevbox): import CLI — args, env, dry-run (default)"
```

---

## Task 11: CLI — `--commit` (allowlist → push → verify) + archive

**Files:**
- Modify: `/home/kevin/projects/trakt-stremio-import/src/kevbox-import.ts`

- [ ] **Step 1: Replace the commit stub with the real commit path**

In `src/kevbox-import.ts`, replace these two lines:
```ts
    // commit path is added in Task 11
    throw new Error("--commit path not yet implemented");
```
with:
```ts
    // rev 4 §10/§14 — Trakt precheck: a Trakt-connected member won't restore (gating) and seeding+allowlisting
    // wastes the canary. Best-effort hint from the local accounts/*.json; override with --allow-trakt-connected.
    if (traktConnectedHint(args.memberRef) && !args.allowTraktConnected) {
      throw new Error("member appears Trakt-connected (accounts/*.json has trakt_accesstoken) — restore is gated OFF while Trakt is connected. Disconnect Trakt on-device first, or pass --allow-trakt-connected.");
    }

    // rev 4 §9.4 — allowlisting is a DELIBERATE, separate action (it enables device restore AND lets the device's
    // forced push land — the device push is owner-gated). --commit does NOT auto-allowlist; pass --allowlist.
    if (args.allowlist) {
      await kb.allowlist(db, owner);
      console.log("allowlisted (sync_canary_members)");
    }
    if (!(await kb.isAllowlisted(db, owner))) {
      throw new Error("member not allowlisted — run with --allowlist. Corrected Option A order (§9.4): --allowlist → device 'sync now' (force push) → --verify-only shows counts>0 → then --commit.");
    }

    // rev 4 §9.2/§9.3 — merge-safety guard: refuse to seed a member with NO prior cloud state unless --force.
    // For a MERGE-SENSITIVE member, 0 counts mean the device has not pushed → the first pull would REPLACE local
    // (silent loss). For a CLEAN member, 0 is expected and safe — assert that with --force.
    const before = await kb.verifyCounts(db, owner);
    const noPriorState = before.watchProgress === 0 && before.watchedItems === 0 && before.library === 0;
    if (noPriorState && !args.force) {
      throw new Error(
        "no prior cloud state for this member (counts all 0). If MERGE-SENSITIVE, the device must FORCE a push first " +
        "(Account 'sync now') so its local watched_items survive the first pull — then re-run. If the member is CLEAN " +
        "(no local history) or you have verified the device already pushed, re-run with --force. " +
        "(§9.4 corrected Option A; library-bearing members also need --no-library until Option B ships.)",
      );
    }

    const wp = await kb.pushWatchProgress(db, owner, payloads.watchProgress);
    const wi = await kb.pushWatchedItems(db, owner, payloads.watchedItems);
    const lib = args.noLibrary ? 0 : await kb.pushLibrary(db, owner, payloads.library);
    console.log(`pushed: wp=${wp} wi=${wi} lib=${lib}`);

    const after = await kb.verifyCounts(db, owner);
    reconcile(payloads.stats, { before, after }); // rev 4 §13 — source→converted→dest, asserts after>=before

    archive(args.memberRef, owner, payloads.stats, after);
    console.log(`archived to accounts/${safeName(args.memberRef)}.json`);
```

- [ ] **Step 2: Add the archive helpers**

In `src/kevbox-import.ts`, add before `async function main()`:
```ts
function safeName(ref: string): string {
  return ref.replace(/[^a-zA-Z0-9._@-]/g, "_");
}

// rev 4 §10/§14 — best-effort Trakt-connected hint: a legacy Trakt-migration archive (accounts/<ref>.json with a
// trakt_accesstoken) means the member is/was Trakt-driven → Supabase restore is gated off. Most reliable when
// --member is the email used by the Trakt migration; returns false (no block) if it can't tell.
function traktConnectedHint(ref: string): boolean {
  try {
    const f = path.join(__dirname, "..", "accounts", `${safeName(ref)}.json`);
    if (!fs.existsSync(f)) return false;
    return !!JSON.parse(fs.readFileSync(f, "utf8")).trakt_accesstoken;
  } catch {
    return false;
  }
}

function archive(ref: string, owner: string, stats: unknown, verify: unknown) {
  const dir = path.join(__dirname, "..", "accounts");
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(
    path.join(dir, `${safeName(ref)}.json`),
    JSON.stringify({ kevbox_owner: owner, ran_at: new Date().toISOString(), stats, verify }, null, 2),
  );
}
```

- [ ] **Step 3: Type-check**

Run: `cd /home/kevin/projects/trakt-stremio-import && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 4: Verify `--verify-only` against an already-imported member (integration)**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
SUPABASE_DB_URL="<url>" npx ts-node src/kevbox-import.ts --member kevin.chiha@gmail.com --verify-only
```
Expected: prints `allowlisted=true` and the three base-table counts. (Full `--commit` is exercised on the canary in Task 13, not here — `--commit` against a real member is a deliberate, gated action.)

- [ ] **Step 5: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add src/kevbox-import.ts
git commit -m "feat(kevbox): import CLI --commit (allowlist→push→verify) + archive + --verify-only"
```

---

## Task 12: Wrapper script + app.env convention + repo pointer

**Files:**
- Create: `/home/kevin/projects/trakt-stremio-import/scripts/kevbox_import.sh`
- Create: `/home/kevin/projects/trakt-stremio-import/KEVBOX-IMPORT.md`

- [ ] **Step 1: Create the wrapper script**

Create `scripts/kevbox_import.sh`:
```bash
#!/usr/bin/env bash
# Thin wrapper around the KevBox import CLI. Reads SUPABASE_DB_URL from app.env.
# Usage: kevbox_import.sh --member <uid|email> (--authkey K | --email E --password P) [--commit] [--no-library] [--verify-only]
set -euo pipefail
ENV_FILE="${HOME}/.config/stremio-kevbox-migration/app.env"
[ -f "$ENV_FILE" ] && set -a && source "$ENV_FILE" && set +a
TOOL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$TOOL_DIR"
exec npx ts-node src/kevbox-import.ts "$@"
```

- [ ] **Step 2: Make it executable**

Run: `chmod +x /home/kevin/projects/trakt-stremio-import/scripts/kevbox_import.sh`
Expected: no output.

- [ ] **Step 3: Document the app.env convention + repo pointer**

Create `KEVBOX-IMPORT.md`:
```markdown
# KevBox Import (Stremio → KevBox Supabase)

Replaces the Trakt path. Design spec:
`~/projects/NuvioTV/docs/superpowers/specs/2026-06-14-stremio-kevbox-direct-import-design.md` (rev 3).

## Setup
Create `~/.config/stremio-kevbox-migration/app.env`:
```
SUPABASE_DB_URL=postgresql://postgres.<ref>:<pw>@<host>.pooler.supabase.com:5432/postgres
```
(copy the value from `~/projects/NuvioTV/local.properties`).

## Run
- Dry-run (default, writes nothing — prints source→converted reconciliation + Cinemeta-drift warnings):
  `scripts/kevbox_import.sh --member <uid|email> --authkey <stremio_authkey>`
- Allowlist a member (separate, deliberate action — enables device restore): add `--allowlist`.
- Commit the push: add `--commit` (requires the member ALREADY allowlisted).
- **Merge-sensitive member:** allowlist → device "Sync now" → `--verify-only` shows counts>0 → `--commit --no-library`
  (library is unprotectable until Option B ships — see KEVBOX-CANARY-RUNBOOK.md).
- **Clean member** (no local history): `--commit --force` (asserts the 0-count state is a clean member).
- Skip library: `--no-library`. Verify only: `--verify-only`. Bypass Trakt precheck: `--allow-trakt-connected`.
- Email+password instead of an authKey: `--email <e> --password <p>`.

Server pull / base-table counts are ground truth — never claim success from the push log.
The merge-safety sequence is MANDATORY for members with local history — see KEVBOX-CANARY-RUNBOOK.md.
```

- [ ] **Step 4: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add scripts/kevbox_import.sh KEVBOX-IMPORT.md
git commit -m "docs(kevbox): import wrapper + app.env convention + spec pointer"
```

---

## Task 13: Canary runbook — merge-safe sequencing (§9.4 corrected option A) + on-device verify

Spec §9.4 / §10 / §13. **rev 4:** the watched_items + library client merges are REPLACE-not-union for never-synced members, and a *passive* app-open does NOT push (the startup watched_items push self-blocks behind the same `lastSuccessfulPushMs > 0` it would set). So a merge-sensitive member must **force** a device push ("Sync now") — then verify it landed — BEFORE the import seeds cloud. Library is unprotectable by sequencing (no `lastSuccessfulPushMs` concept) → `--no-library` until Option B ships. This task produces the operator runbook — **no code**.

**Files:**
- Create: `/home/kevin/projects/trakt-stremio-import/KEVBOX-CANARY-RUNBOOK.md`

- [ ] **Step 1: Write the runbook**

Create `KEVBOX-CANARY-RUNBOOK.md`:
```markdown
# KevBox Import — Canary Runbook (merge-sensitive member) — rev 4

Goal: prove the import end-to-end on ONE real merge-sensitive, **non-Trakt, non-Kevin** member
with zero local-history loss for watch_progress + watched_items. (Library survival is NOT
guaranteed until Option B ships — library-bearing members run `--no-library`.)

## Why the order matters (rev 4 — corrected)
For a never-synced member the client restore for watched_items + library is REPLACE-not-union
(spec §9.2/§9.3). The device preserves local watched_items only when `lastSuccessfulPushMs > 0`,
and that flag is set ONLY by an actual push. A passive "open the app" does **NOT** push on a
never-synced device (the startup push self-blocks behind the same `lastSuccessfulPushMs > 0` it
would set). So we must FORCE a push from the device, verify it landed, THEN import. Library has no
such flag at all and cannot be protected by sequencing → use `--no-library`.

## Preconditions
- §7.3 EXECUTE preflight passed.
- The member is **NOT** Kevin (already-synced; the preserve path doesn't apply and a commit pollutes
  his delta cursor) and **NOT** Trakt-connected (Trakt gates Supabase restore off).

## Steps (merge-sensitive member)
1. Pick the most-watched merge-sensitive member from the §10 cohort snapshot (exclude Kevin + Trakt members).
2. **Allowlist** them — required BEFORE the device push, because the device's push is owner-gated and no-ops
   while gated off:
   `scripts/kevbox_import.sh --member <uid|email> --allowlist --verify-only`  → prints `allowlisted=true` + current counts.
3. **Force a device push:** on their TV, KevBox → Account → **"Sync now"** (NOT a passive app-open). Wait ~1 min.
   This pushes their existing watch_progress + watched_items to cloud and sets `lastSuccessfulPushMs > 0`.
4. **Confirm it landed:** `scripts/kevbox_import.sh --member <uid|email> --verify-only` → watched_items /
   watch_progress counts now reflect their CURRENT KevBox state (non-zero for a real watcher). If still 0,
   the push did not land — redo step 3 before proceeding.
5. **Dry-run:** `scripts/kevbox_import.sh --member <uid|email> --authkey <their_stremio_authkey> --no-library`.
   Eyeball the reconciliation (source → converted), Cinemeta-drift warnings, non-standard ids.
6. **Commit:** `scripts/kevbox_import.sh --member <uid|email> --authkey <...> --no-library --commit`.
   Reconciliation now shows source → converted → dest with `before→after`; confirm `after >= before` for every
   subsystem (no "dest count DROPPED" warning).
7. **ON-DEVICE verify** (ground truth, §13): on their TV confirm —
   (a) continue-watching shows Stremio resumes AND their prior KevBox positions, none regressed;
   (b) watched marks: prior KevBox "watched" items still watched + Stremio ones added.
   If ANY prior local item vanished → STOP, this is the §9.2 loss path; kill-switch + do not backfill others.
8. **Library** for this member: skipped (`--no-library`) until Option B ships. After Option B is released +
   sideloaded, library can be imported and library survival verified on-device.
9. **Kill-switch** if needed: `delete from public.sync_canary_members where user_id = '<uid>'` (one) or
   `truncate public.sync_canary_members` (all).

## Clean members (no local history)
For the ~10 clean active members (watch_seconds = 0) and the ~320 future C1 cohort there is no local state to
protect — `--force` asserts the 0-count state is a clean member (not an un-pushed merge-sensitive one), and
library may be imported:
`scripts/kevbox_import.sh --member <uid|email> --allowlist --authkey <...> --commit --force`
```

- [ ] **Step 2: Commit**

Run:
```bash
cd /home/kevin/projects/trakt-stremio-import
git add KEVBOX-CANARY-RUNBOOK.md
git commit -m "docs(kevbox): canary runbook — merge-safe sequencing + on-device 3-subsystem verify"
```

---

## Self-Review (completed during planning)

**Spec coverage (Plan 1 scope, rev 4):** §4 components → Tasks 2-12; §5 contract → Tasks 3-9; §6 field mapping incl. **movie OR-rule** (Task 3) + **Cinemeta-drift detection** (Tasks 2/8) → Tasks 3/5/8; §7.3 EXECUTE preflight → Task 0; **§9.4 corrected Option A** (forced-push precondition + `--no-library` + allowlist-split + `--force` guard) → Tasks 11/13; §11 dry-run default + archive → Tasks 10-11; §12 gate-aware + **PG SQLSTATE branching** (42501/42883) + 0-item guard → Tasks 9/10/11; **§13 computed source-vs-dest reconciliation** + pre/post `after>=before` → Tasks 10/11; §10/§14 **Trakt precheck** → Task 11; §15 unit fixtures (incl. rev-4 cases) → Tasks 2-6. **Separate plan (MANDATED before fleet-wide C1):** §9.4 **Option B** Kotlin client union patch → `2026-06-14-stremio-kevbox-import-clientB-merge-union.md`. **Deferred to Plan 2:** §7.1 creds vault, C1 poller, monitoring/sweep (§16), `/stremio-kevbox-migration` skill, activity-aware 0-item branch.

**Placeholder scan:** no TBD/TODO/"handle edge cases" — every code step shows full code; every run step shows the exact command + expected output.

**Type consistency (rev 4):** `WatchedIndexResult` (Task 1) is produced by `buildWatchedEpisodeIndex` (Task 8, `{index, driftIds}`) and destructured in the CLI (Task 10), which passes `index` + `bitfieldDriftIds` into `convertStremioToKevbox` (Tasks 3-6); `decodeWatchedEpisodes`/`isBitfieldDrifted`/`serializedLastVideoId` (Task 2) match their callers in Task 8; the `_for` functions are called `(owner, 1, payload)` positionally (Task 9) matching §5; `ConvertStats` (source counts + `bitfieldDriftIds`), `ConvertOptions`, `VerifyCounts`, `KevboxPayloads` names are consistent across Tasks 1/8/9/10/11; `reconcile` (Task 10) consumes `ConvertStats` + `VerifyCounts`.
