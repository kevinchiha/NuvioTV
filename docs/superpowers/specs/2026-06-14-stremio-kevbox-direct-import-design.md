# Stremio → KevBox Direct Import (Trakt-free) — Design Spec

**Date:** 2026-06-14
**Status:** Design approved → ready for implementation **planning** (writing-plans turns this into plans)
**Depends on:** [`2026-06-14-cloud-restore-design.md`](./2026-06-14-cloud-restore-design.md) — its §4/§5 RPC contract and §8 gating are load-bearing here.
**Code lives in:** `~/projects/trakt-stremio-import` (a patched fork of `aliyss/trakt-stremio-import`). A pointer note will be added there linking back to this spec.

## 1. Goal

Backfill a KevBox member's existing **Stremio** watch history, continue-watching, and saved library **directly into the KevBox Supabase project** (`scmqdptagksltnwiveyh`) via the same `sync_push_*` RPCs the Android client uses — **with no Trakt in the loop**. After import, the member's KevBox device restores it through the cloud-restore feature (see the dependency spec).

This **replaces** the Stremio→Trakt→KevBox path. Trakt becomes deprecated (only one member currently uses it; see §11).

**Non-goals:** the server schema itself (delivered by the cloud-restore plans), any Android client changes (none — the device already does restore), collections/profiles/addons/plugins (no Stremio source), and multi-profile handling (fleet is one-device, `profile_id = 1`).

## 2. Background — why this works without Trakt

The Trakt detour only ever existed because KevBox had no server-side schema to restore from; the device could get cross-device history *only* via Trakt scrobbling, which is forward-only and never backfills. Once cloud-restore deploys the `sync_*` RPCs, the device restores watch progress / watched items / library straight from KevBox Supabase.

Crucially, the cloud-restore **§8 gating** makes a non-Trakt member exactly the case where the Supabase path is live:

| Subsystem | Runs for a non-Trakt member? |
|---|---|
| watch_progress, watched_items | **Yes** (`shouldUseSupabaseWatchProgressSync()` is true only when Trakt is *not* the progress source) |
| library | **Yes** (skipped only when `librarySourceMode == TRAKT`) |

So injecting Stremio history into a member's Supabase rows is enough for it to flow down on next login. No client edits, no Trakt account.

**UX win:** both Stremio and KevBox member auth are email+password (no OAuth device-flow), so a run is **one-shot and non-interactive** — no two-turn "present link → wait for human → exchange code" dance that the Trakt skill required.

## 3. Architecture & components

Built as a new entrypoint in the existing fork, reusing its proven Stremio-reading half. The Trakt code is left in place but becomes legacy (see §11).

| Component | File | Responsibility | New? |
|---|---|---|---|
| Stremio reader | `src/utils/stremio.ts` | login (email+pass → authKey), `getLibrary` (`datastoreGet libraryItem`), `getCinemetaMeta` | reuse as-is |
| Converter | `src/utils/convert-kevbox.ts` | Stremio library → `{watchProgress[], watchedItems[], library[]}`, incl. watched-bitfield decode | **new** |
| KevBox client | `src/utils/kevbox.ts` | GoTrue password-grant login → member JWT; batched PostgREST `sync_push_*`; verify via `sync_pull_*` | **new** |
| CLI entrypoint | `src/kevbox-import.ts` | orchestrate fetch → convert → auth → (dry-run \| push) → verify | **new** |

Each unit has one purpose and a clear interface: the converter is pure (Stremio JSON in, payload arrays out) and unit-testable without network; the KevBox client owns all Supabase I/O; the entrypoint wires them. No new npm dependencies (`axios`, `stremio-watched-bitfield` already present).

## 4. Data flow

```
Stremio email+pass ─▶ authKey ─▶ datastoreGet(libraryItem) ─▶ convert ─┐
                                                                        ├─▶ sync_push_watched_items   (batched, member JWT)
member email+pass ─▶ GoTrue password grant ─▶ member JWT ──────────────┼─▶ sync_push_watch_progress  (batched, member JWT)
                                                                        └─▶ sync_push_library         (batched, member JWT)
                                                  then ─▶ sync_pull_* (verify counts = server ground truth)
```

Under `--dry-run` (the default, §8) the convert step runs and prints the three payload arrays + counts; nothing is pushed.

## 5. Verified client contract

All shapes below were verified against the live client call sites (cross-checked the same way as cloud-restore §5). PostgREST binds by argument **name** — these must be exact.

**Push RPCs (the importer calls these):**

| RPC | Args | Payload item shape |
|---|---|---|
| `sync_push_watch_progress` | `p_entries jsonb`, `p_profile_id int` | `{content_id, content_type, video_id, season?, episode?, position, duration, last_watched, progress_key}` — `season`/`episode` **omitted when null** (movies) |
| `sync_push_watched_items` | `p_items jsonb`, `p_profile_id int` | `{content_id, content_type, title, season, episode, watched_at}` — `season`/`episode` are **explicit JSON null** for movies (R6) |
| `sync_push_library` | `p_items jsonb`, `p_profile_id int` | `{content_id, content_type, name, poster, poster_shape, background, description, release_info, imdb_rating?, genres[], addon_base_url, added_at}` |

**Pull RPCs (verification only):** `sync_pull_watch_progress(p_profile_id, p_since_last_watched DEFAULT null, p_limit DEFAULT null)`, `sync_pull_watched_items(p_profile_id, p_page, p_page_size)`, `sync_pull_library(p_profile_id, p_limit, p_offset)`.

**Verified invariants:**
- `progress_key` = `content_id` for movies; `` `${content_id}_s${season}e${episode}` `` for episodes (`_sNeM`, **not** colon-delimited). *(WatchProgressRepositoryImpl.kt:1098-1104)*
- `position` / `duration` are **milliseconds** *(WatchProgress.kt domain model)* — Stremio's `state.timeOffset` / `state.duration` are also ms, so **no unit conversion**.
- `content_type` is `"movie"` / `"series"` — identical to Stremio's `type` (client normalizes `"tv"`→`"series"`).
- `last_watched` / `watched_at` are **epoch-ms `int8`**; Stremio's `state.lastWatched` is an ISO string → needs `Date.parse(...)`.
- `profile_id` = **1** fleet-wide (confirmed; one-device topology).

## 6. Field mapping

Source types: `StremioLibraryObject` / `StremioLibraryObjectState` (`src/utils/stremio.ts`). Iterate the library, skip `removed` items.

**Content-id namespace:** Stremio items are usually IMDB ids (`tt…`), which the client resolves directly. Non-standard ids (e.g. `kitsu:…`, addon-specific) are **passed through as-is** (`content_id` is plain text) but may not resolve on-device; the converter logs a count of non-`tt`/`tmdb:`/`trakt:` ids rather than dropping them, so the operator can spot a library with many unresolvable entries.

### 6.1 watch_progress — one entry per title (Stremio `state` holds the latest resume point)
Emit when `state.timeOffset > 0 && state.duration > 0` and **not** the client's junk case (`position ≤ 1 && duration ≤ 1`):
- common: `content_id=_id`, `content_type=type`, `position=state.timeOffset`, `duration=state.duration`, `last_watched=Date.parse(state.lastWatched)`
- movie: `video_id = state.video_id || _id`, `progress_key = _id`, season/episode **omitted**
- series: `video_id = state.video_id`, `season = state.season`, `episode = state.episode`, `progress_key = ${_id}_s${season}e${episode}` (only when season & episode present)

### 6.2 watched_items — every watched movie + every watched episode
- movie: emit one row **iff** `state.flaggedWatched === 1` → `{content_id:_id, content_type:"movie", title:name, season:null, episode:null, watched_at}`
- series: decode `state.watched` via `stremio-watched-bitfield` against the Cinemeta episode list — reuse the exact logic in `convert.ts:116-137` (`watchedBitfield.constructAndResize(state.watched, episodeList)`, episode ids `${_id}:${season}:${number}`). For each ep where `wb.getVideo(ep)` is truthy → `{content_id:_id, content_type:"series", title:name, season, episode, watched_at}`.
- `watched_at` = parsed `state.lastWatched`, fallback to run-time `Date.now()` (so a genuine future Stremio play always wins the server's R2 `watched_at` guard). All episodes of a show share the show's `lastWatched` — an approximation carried over from the Trakt tool.

### 6.3 library — saved/bookmarked titles *(toggleable, default on)*
Filter `!removed && !temp` (Stremio marks watch-only auto-adds `temp:true`; explicit bookmarks `temp:false`):
`{content_id:_id, content_type:type, name, poster, background, release_info:year, added_at:Date.parse(_ctime), poster_shape:"POSTER", genres:[], addon_base_url:"", description:"", imdb_rating:null}`.
Fidelity caveat: genres/rating/addon_base_url have no Stremio source and ship empty/null (all nullable/defaulted per cloud-restore §5.3). Behind a `--no-library` toggle so it can be dropped if it misbehaves on-device.

## 7. Auth & write path

- **Member JWT:** `POST {SUPABASE_URL}/auth/v1/token?grant_type=password` with header `apikey: <anon>` and body `{email, password}` → `access_token`.
- **Push:** `POST {SUPABASE_URL}/rest/v1/rpc/<fn>` with headers `apikey: <anon>` + `Authorization: Bearer <jwt>` + `Prefer: return=minimal`, body `{p_entries|p_items, p_profile_id: 1}`. Batched ~500 items/call.
- The RPCs scope every write to `get_sync_owner()` = `auth.uid()`, so **all R1–R10 correctness (conflict guards, event-log appends, owner scoping) is enforced server-side**. The importer carries zero correctness logic — it only shapes payloads.
- `SUPABASE_URL` + anon key come from env/args, copied from NuvioTV's `local.properties` (`SUPABASE_URL` / `SUPABASE_ANON_KEY`). **service_role is never used; no secret is hardcoded.** Member + Stremio credentials touch only `api.strem.io` and the KevBox GoTrue/PostgREST endpoints over HTTPS.

## 8. Operator workflow, dry-run & idempotency

A new **`/stremio-kevbox-migration`** skill replaces `/stremio-trakt-migration`. Per member, one non-interactive run:

1. Have the member's **KevBox** email+password (you provision these) and **Stremio** email+password (or authKey).
2. `scripts/kevbox_import.sh <stremio_email> <stremio_pass> <kevbox_email> <kevbox_pass>` — reads `SUPABASE_URL`/anon key from `~/.config/stremio-kevbox-migration/app.env`. **Defaults to `--dry-run`**: prints the three payload arrays + counts, pushes nothing.
3. Eyeball the counts, then re-run with `--commit` to actually push.
4. Report verify counts (server pull = ground truth).
5. Tell the member: on KevBox, just **re-login** — restore pulls it down (no "Settings → Trakt" step anymore).

Archive each member's creds to `accounts/<kevbox-email>.json` under the tool dir so re-runs/top-ups need no re-entry (mirrors the Trakt skill's Step 8).

**Idempotent & safe to re-run:** the server's R2 `last_watched`/`watched_at` guards + R3 unique keys mean re-imports never duplicate or regress progress. Top-ups (member watched more in Stremio later, or a Cinemeta blip left a show short) = run `--commit` again.

## 9. Error handling

- **RPC not deployed (hard precondition):** on `404` / PostgREST `PGRST202`, fail-fast with "cloud-restore schema not deployed to this project" and exit non-zero. The importer is useless until the cloud-restore plans ship.
- **Auth failure:** clear message distinguishing Stremio login vs KevBox GoTrue login failure.
- **Cinemeta lookup blip:** a failed episode-list fetch for a series logs a warning and skips that show's *episodes* (its continue-watching + movie rows still go); re-run later to fill the gap. Same soft-fail as the Trakt tool.
- **Batch failure:** abort with which batch (offset/size) and the PostgREST error body; already-pushed batches are safe (idempotent).
- No foreground `sleep` (sandbox-blocked); batching pauses, if any, are in-process.

## 10. Verification

After a `--commit` run, authenticate as the member and call `sync_pull_watch_progress`, `sync_pull_watched_items` (page 1), and `sync_pull_library`; report row counts. This is server ground truth — never claim success from the push log alone (carried over from the Trakt skill's hard-won lesson). A `--verify-only` mode runs just this against an already-imported member.

## 11. Trakt deprecation & the one existing Trakt user

- **New members:** pure Stremio → KevBox; Trakt never touched.
- **The one existing Trakt user:** run the importer for them (Stremio → Supabase), then **disconnect Trakt on their device**. Required because of §8 gating — while Trakt is the progress source, the Supabase watch_progress/watched_items/library sync is gated *off*; disconnecting flips `shouldUseSupabaseWatchProgressSync()` true so the imported data restores. Documented as a one-time manual step in the skill.
- The Trakt tool (`convert.ts`, `trakt.ts`, `sync.ts`, `server.ts`, `rerun.ts`) and the `/stremio-trakt-migration` skill are marked **deprecated** (not deleted) until the KevBox path is proven on the fleet.

## 12. Testing

- **Unit (converter, no network):** fixture Stremio library JSON — watched movie, series with partial watched bitfield, continue-watching mid-episode, `temp` vs saved — assert exact payload shapes, `progress_key` format, ms units, and the movie `season/episode` null handling (omitted for watch_progress, explicit null for watched_items).
- **Integration (dry-run):** `--dry-run` against a real member's library; verify counts/shapes before any write.
- **End-to-end:** against the **Supabase branch DB** (the same disposable clone the cloud-restore SQL tests use), `--commit` a test member, then assert `sync_pull_*` returns the expected rows; finally an on-device AVD restore for one real member as the canary.

## 13. Deliverables

- `src/utils/convert-kevbox.ts`, `src/utils/kevbox.ts`, `src/kevbox-import.ts` (in the fork).
- `scripts/kevbox_import.sh` wrapper + `~/.config/stremio-kevbox-migration/app.env` convention.
- `/stremio-kevbox-migration` skill (SKILL.md + scripts + references), replacing the Trakt skill; deprecation note on the old skill.
- Converter unit tests + fixtures.
- A pointer note in the `trakt-stremio-import` repo linking to this spec.
- `npm run` script entry for the new entrypoint.

## 14. Locked decisions

- Write path: **member JWT via GoTrue password grant** → the existing `sync_push_*` RPCs (server enforces all correctness; zero new server surface).
- Scope: watch_progress + watched_items + saved library (library toggleable, default on).
- Packaging: **replacement** — new KevBox path in the fork + new skill; Trakt deprecated.
- Spec lives in NuvioTV `docs/superpowers/specs/` (coupled to cloud-restore); code in `trakt-stremio-import`.
- `--dry-run` is the **default**; `--commit` performs the push.
- `profile_id = 1` fleet-wide; `service_role` never used; no hardcoded secrets.
- **Hard precondition:** cloud-restore Plans 1–3 deployed (the `sync_push_*`/`sync_pull_*` RPCs + `get_sync_owner()` exist on the live project) before this tool is useful.
