# Plan: Remote per-member Stremio-addon control (KevBox TV)

> Status: **planned, not started.** Implement in a fresh session. Branch: `kevbox`.
> Scope: **Stremio addons only** — plugins (JS scrapers) are intentionally NOT managed.
> Storage: **one row per addon** in a `member_addon` table (relational, not a JSON blob).
>
> **This revision (v2) closes the 20 gaps found by the code-grounded review.** Full gap report:
> `MEMBER-CONFIG-PLAN-GAPS.md`. The two **critical** ones (a pre-existing addon reconciler that races
> us, and the profile write-guard that silently no-ops our writes) are addressed first, in
> **§0 Critical pre-work** — do that before anything else or the feature will not work reliably.

## Context

The family wants the operator (you) to **remotely adjust which Stremio addons a specific family
member has**, from the Supabase dashboard, without touching their TV. Each member signs in with their
own account in **your** Supabase project (`scmqdptagksltnwiveyh.supabase.co` — the auth-only KevBox
backend, **not** the dead NuvioTV one). The app bundles the Supabase **Postgrest** client and it's
installed on the client (`core/di/SupabaseModule.kt`), and the device-side reconcile machinery already
exists (`AddonRepositoryImpl.reconcileWithRemoteAddonUrls`). So this feature is a new Supabase table +
a small full-flavor service that reads it on app open and applies it — **but** it must coexist with two
existing facts the first draft missed:

1. **A legacy addon reconciler already runs on the exact same trigger.** `StartupSyncService` pulls the
   old NuvioTV `addons` table and calls `reconcileWithRemoteAddonUrls(..., removeMissingLocal = true)`
   on every `AuthState.FullAccount` (`core/sync/StartupSyncService.kt`, `addonJob` inside
   `pullBroadRemoteData`; also `requestAddonSyncNow`). It shares the **non-atomic**
   `AddonRepositoryImpl.isSyncingFromRemote` flag with us. Two reconcilers on one trigger = a race that
   silently overwrites our config. **The "dead RPC, harmless" claim in the first draft was wrong about
   the pull path.** → fixed in §0.A.
2. **Addon writes are profile-gated.** `AddonPreferences.setAddonOrder` / `setAddonEnabledStates`
   (and `addAddon`/`removeAddon`) early-return when the active local profile is a non-primary profile
   that `usesPrimaryAddons`. `reconcileWithRemoteAddonUrls` writes through `setAddonOrder`, so on a
   kid/guest profile our entire apply silently no-ops — no error. → fixed in §0.B.

**Locked decisions (from the user):**
- **Mirror exactly** — the member's primary addon set matches their rows exactly
  (`removeMissingLocal = true`); their own manual additions are removed on sync.
- **Applies on app open** (and on sign-in). No Supabase Realtime / websockets in v1.
- **Scope = Stremio addons only.** Per member you can **add, delete, reorder, enable/disable, and
  "edit"** an addon. *Editing* = changing its **manifest/base URL** (Stremio encodes config into the URL).
  JS scraper plugins are out of scope.
- **Storage = one row per addon** in `member_addon` (`user_id, url, enabled, sort_order`).
- **No rows = safe fallback:** if a member has zero rows the app does nothing and keeps the baked-in
  defaults (the app only ever **reads** this table). *Operationally every member is auto-seeded (opt-out,
  see v2 decisions), so a zero-row member is a safety fallback — e.g. before back-fill — not the normal
  state.*

**Decisions added in v2 (forced by the code):**
- **Member config governs the PRIMARY addon set** (store id 1 — the set inheriting sub-profiles read).
  Inheriting kid/guest profiles automatically reflect it. Non-inheriting sub-profiles that own a
  separate addon set are **not** governed by member config (documented limitation; matches the per-
  *account* nature of `member_addon`, which has no profile dimension).
- **The legacy `addons`-table addon sync is disabled in the `full` flavor** (KevBox), so `member_addon`
  is the single source of truth. Gated by a new `BuildConfig.FEATURE_MEMBER_ADDON_CONFIG` flag, which
  doubles as a **build-time kill switch** (set it `false`, rebuild → fall back to baked defaults / legacy
  behaviour). `playstore` + upstream keep the legacy behaviour unchanged.
- **Opt-out: every member is auto-seeded with the defaults.** A `SECURITY DEFINER` trigger seeds the 5
  4 universal default rows on sign-up and a one-time back-fill covers existing members, so `member_addon` is
  authoritative for the **whole family from day one** (you centrally control everyone's addons; the
  defaults are just each member's starting point). The app's "no rows → keep defaults" behaviour is kept
  only as a safety fallback.

---

## §0 Critical pre-work (do first)

### 0.A — Stop the legacy addon reconciler from racing us (`full` only)

Add a flavor flag and gate the legacy addon pull behind it.

**`app/build.gradle.kts`** — mirror the existing `FEATURE_PLUGINS_ENABLED` pattern:
```kotlin
create("full") {
    // …existing FEATURE_* fields…
    buildConfigField("boolean", "FEATURE_MEMBER_ADDON_CONFIG", "true")
}
create("playstore") {
    // …existing FEATURE_* fields…
    buildConfigField("boolean", "FEATURE_MEMBER_ADDON_CONFIG", "false")
}
```

**`core/sync/StartupSyncService.kt`** — skip the legacy addon pull when our feature owns addons. This is
a guarded, additive `if` (hide-don't-delete); `playstore`/upstream are unaffected because the flag is
`false` there.
- In `pullBroadRemoteData(...)`, wrap the `addonJob` body so it only runs the legacy reconcile when
  `!BuildConfig.FEATURE_MEMBER_ADDON_CONFIG`. (Keep the `async {}`/`await()` structure; just early-return
  inside, or skip launching it.)
- Do the same in `requestAddonSyncNow()` (it runs the same reconcile on a manual trigger).
- Leave `pushToRemote`/`triggerRemoteSync` alone — they call an RPC (`sync_push_addons`) that doesn't
  exist in the KevBox project, so they fail harmlessly; and during our apply `isSyncingFromRemote = true`
  suppresses `triggerRemoteSync` anyway. (Optional tidy: short-circuit `AddonSyncService.pushToRemote`
  under the same flag to avoid wasted network.)

Result: in `full`, **only** `MemberConfigService` reconciles addons, so `isSyncingFromRemote` has a
single writer and there is no cross-reconciler race or enabled-state clobber.

### 0.B — Make the apply target the PRIMARY addon store, bypassing the profile write-guard

The reconcile path *reads* the primary set correctly (inheriting profiles route to store id 1 via
`AddonPreferences.effectiveProfileId()`), but *writes* are blocked by the guard. Add primary-targeted,
guard-free writers and a primary read, then have the apply use them.

**`data/local/AddonPreferences.kt`** (new methods; `PRIMARY_ADDON_PROFILE_ID = 1` — the id
`effectiveProfileId()` returns for inheriting profiles; verify against `ProfileManager` that primary is
1):
```kotlin
// Remote member-config writers — always target the primary addon store (id 1), which every
// inheriting sub-profile reads. Intentionally NOT profile-gated: member config is per-account and
// authoritative over the primary set regardless of which local profile is active at app-open.
suspend fun getPrimaryInstalledAddonUrls(): List<String> =
    getCurrentList(store(PRIMARY_ADDON_PROFILE_ID).data.first())

suspend fun setPrimaryAddonOrder(urls: List<String>) {
    store(PRIMARY_ADDON_PROFILE_ID).edit { preferences ->
        val orderedUrls = urls.map(::canonicalizeUrl)
        preferences[orderedUrlsKey] = gson.toJson(orderedUrls)
        val currentStates = getCurrentEnabledStates(preferences)
        preferences[addonEnabledStatesKey] = gson.toJson(
            orderedUrls.associateWith { url -> currentStates[url] ?: true }
        )
    }
}

suspend fun setPrimaryAddonEnabledStates(states: Map<String, Boolean>) {
    store(PRIMARY_ADDON_PROFILE_ID).edit { preferences ->
        preferences[addonEnabledStatesKey] =
            gson.toJson(states.mapKeys { (url, _) -> canonicalizeUrl(url) })
    }
}
```

**`data/repository/AddonRepositoryImpl.kt`** — add a primary-targeted reconcile + apply (mirrors
`reconcileWithRemoteAddonUrls`'s logic but reads/writes the **primary** store and keeps the empty-list
safety guard). This is the method `MemberConfigService` calls:
```kotlin
suspend fun applyRemoteAddonConfig(
    orderedUrls: List<String>,          // ALL member rows in sort order (incl. disabled)
    enabledByUrl: Map<String, Boolean>  // url -> enabled, full set
) {
    isSyncingFromRemote = true
    try {
        val normalizedRemote = orderedUrls
            .map { canonicalizeUrl(it) }.filter { it.isNotBlank() }
            .distinctBy { normalizeUrl(it) }
        val local = preferences.getPrimaryInstalledAddonUrls()
        // Empty-list safety guard: never mirror-wipe to nothing.
        if (normalizedRemote.isEmpty()) {
            Log.w(TAG, "applyRemoteAddonConfig: empty remote list, preserving primary addons")
            return
        }
        val localByNorm = linkedMapOf<String, String>()
        local.forEach { localByNorm.putIfAbsent(normalizeUrl(it), canonicalizeUrl(it)) }
        val finalList = normalizedRemote.map { localByNorm[normalizeUrl(it)] ?: it }   // mirror exactly
        // ORDER MATTERS: setPrimaryAddonOrder rewrites the enabled map (defaulting to true), so it MUST
        // run before setPrimaryAddonEnabledStates or the member's on/off flags get clobbered.
        preferences.setPrimaryAddonOrder(finalList)
        preferences.setPrimaryAddonEnabledStates(enabledByUrl)
    } finally {
        isSyncingFromRemote = false
    }
}
```
Add `suspend fun applyRemoteAddonConfig(orderedUrls: List<String>, enabledByUrl: Map<String, Boolean>)`
to the `AddonRepository` interface (`domain/repository/AddonRepository.kt`). The existing reactive chain
(`getInstalledAddons()` reads via `effectiveProfileIdFlow` → store 1 for inheriting profiles →
`AddonManagerViewModel.observeInstalledAddons()`) recomposes the UI live; no restart.

> **Account-switch reset (Gap K):** sign-out does not clear the local primary store, so on a shared TV
> member B could inherit member A's list. `MemberConfigService` (below) tracks the last-applied
> `userId`; when the new `userId` differs **and** the member has **zero** rows, it must reset the primary
> store to the baked defaults (`getDefaultAddons()` order) instead of leaving A's list. Implement as a
> `resetPrimaryAddonsToDefaults()` helper (calls `setPrimaryAddonOrder(getDefaultAddons().toList())`).

---

## Server — your Supabase project (run in SQL editor, one time)

```sql
create table public.member_addon (
  id          bigint generated always as identity primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
  url         text not null,              -- Stremio manifest/base URL (config is encoded in the URL)
  enabled     boolean not null default true,
  sort_order  int not null default 0,     -- ascending = catalog / stream-source priority order
  updated_at  timestamptz not null default now(),
  unique (user_id, url)                    -- same addon can't be added twice for a member
);
create index member_addon_user_order_idx on public.member_addon (user_id, sort_order, id);
alter table public.member_addon enable row level security;

-- A member may READ only their own rows (uses their JWT via the app's anon key).
create policy "member reads own addons"
  on public.member_addon for select
  using (auth.uid() = user_id);
-- No anon/authenticated INSERT/UPDATE/DELETE policy → only the dashboard / service_role can edit.
-- (Verify with the Supabase advisor that RLS is enabled and no broad GRANT exists on this table.)
```

**URL canonical-form contract (Gap G).** The device identifies/dedupes addons by a *canonical* URL —
trailing `/manifest.json` stripped and lowercased (`AddonPreferences.canonicalizeUrl`). The DB
`unique(user_id, url)` is over **raw** text, so `…/manifest.json` and the bare URL are two DB rows the
device collapses into one. **Store URLs in canonical form** (no `/manifest.json` suffix, lowercased)
*except* where config/path requires the suffix (the baked OpenSubtitles-Pro / Netflix / Torrentio /
AIOStreams URLs keep their `/manifest.json` — paste them verbatim; the client canonicalizes consistently
on both sides, so verbatim copies of the defaults are safe). Don't hand-author two spellings of the same
addon.

**Email filtering without a stale column (Gap L).** Do **not** denormalize `email` into `member_addon`
(it goes stale on email change and the seed only fires on insert). Instead filter via a view that joins
live auth data:
```sql
create view public.member_addon_v as
  select m.*, u.email as auth_email
  from public.member_addon m join auth.users u on u.id = m.user_id;
-- Dashboard → filter member_addon_v by auth_email to find a member's rows; edit the base table.
```

**Auto-seed every member with the defaults (opt-out — Gaps C, F).** `member_addon` is authoritative for
the whole family, so seed the **universal** defaults automatically. Define the 4 universal defaults
**once** in a SQL helper (single source of truth — the trigger, the back-fill, and the reset tool all
read it, instead of copies of the long URL blobs). This helper mirrors the **universal** Kotlin defaults
(`DefaultContent.DEFAULT_ADDON_URLS` / `AddonPreferences.getDefaultAddons()`) — keep it in sync. ⚠ Verify
URL #4 (Netflix catalog) isn't a pre-expired token. **The per-member debrid sources (Torrentio +
AIOStreams) are deliberately NOT seeded here** — each member uses their own keys/URL, so they're added
per member at onboarding (see "Onboard a member's debrid" below, and `MEMBER-DEBRID-ONBOARDING.md`).

```sql
-- Single source of truth for the baked-in default addon set (verbatim from the Kotlin defaults).
create function public.default_member_addons() returns table (url text, sort_order int)
  language sql immutable as $$
  values
    ('https://v3-cinemeta.strem.io', 0),
    ('https://opensubtitlesv3-pro.dexter21767.com/eyJsYW5ncyI6WyJlbmdsaXNoIiwiZnJlbmNoIl0sInNvdXJjZSI6ImFsbCIsImFpVHJhbnNsYXRlZCI6dHJ1ZSwiYXV0b0FkanVzdG1lbnQiOnRydWV9/manifest.json', 1),
    ('https://opensubtitles-v3.strem.io', 2),
    ('https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/bmZ4LGRucCxhbXAsYXRwLGhibSxwY3AsaGx1LHBtcCxuZmssY3RzLG1nbCxjcnUsaGF5LGNsdixnb3AsamhzLHNzdCx2aWwsbmx6LHplZSxjcGQsc3R6LGRwZSxtYmksc29ueWxpdixzZ28sdmlrLHNoZCxiYm8sYWN0LG1wOSxpdHYsaXFpLGNyYyxhbDQsc2hhLGJiYzo6OjE3ODA5MjA3NDkwOTc6MDowOkxC/manifest.json', 3)
    -- Per-member debrid (Torrentio + AIOStreams) is NOT seeded here — added per member at onboarding
    -- (see "Onboard a member's debrid" below), because each member uses their own keys/URL.
$$;

-- Seed defaults on every new sign-up. SECURITY DEFINER MUST pin search_path (Gap F — without it a
-- definer function is the classic Supabase privilege-escalation footgun: function_search_path_mutable).
create function public.seed_member_addons() returns trigger
  language plpgsql security definer set search_path = '' as $$
begin
  insert into public.member_addon (user_id, url, enabled, sort_order)
  select new.id, url, true, sort_order from public.default_member_addons()
  on conflict (user_id, url) do nothing;
  return new;
end $$;
create trigger on_member_addon_seed after insert on auth.users   -- unique name, won't clash w/ existing
  for each row execute function public.seed_member_addons();

-- One-time back-fill: the trigger only fires on NEW sign-ups, so seed every EXISTING member once.
insert into public.member_addon (user_id, url, enabled, sort_order)
select u.id, d.url, true, d.sort_order
from auth.users u cross join public.default_member_addons() d
on conflict (user_id, url) do nothing;
```

**Reset / re-initialize ONE member to the exact defaults** (e.g. after mangling their rows — Gaps H, J):
```sql
delete from public.member_addon where user_id = '<member uuid>';
insert into public.member_addon (user_id, url, enabled, sort_order)
select '<member uuid>', url, true, sort_order from public.default_member_addons();
```

**Editing a member:** Dashboard → filter `member_addon_v` by `auth_email` → edit rows in `member_addon`:
add/delete rows, flip `enabled`, change `sort_order` to reorder, or edit a `url` to reconfigure. (kevin's
`user_id` is `7b9fed27-8935-4b21-8137-120becb51d0a`.)
**Onboard a member's debrid (per member — their OWN keys; Torrentio + AIOStreams are NOT seeded):** after a
member exists (auto-seeded with the 4 universal addons), add their two debrid rows once. Full runbook:
`MEMBER-DEBRID-ONBOARDING.md`.
```sql
insert into public.member_addon (user_id, url, enabled, sort_order) values
  ('<member uuid>',
   'https://torrentio.strem.fun/qualityfilter=unknown,cam,4k,scr|limit=5|sizefilter=4GB|debridoptions=nodownloadlinks,nocatalog|premiumize=<THEIR_PREMIUMIZE_KEY>/manifest.json',
   true, 4),
  ('<member uuid>',
   '<THEIR_FULL_AIOSTREAMS_URL>',   -- their own configured AIOStreams instance (debrid key is encrypted in the URL)
   true, 5)
on conflict (user_id, url) do nothing;
```
On-device URL case is preserved, so keys with uppercase are safe. A `reset … to defaults` drops these
(defaults = the 4 universal addons) — re-run this insert to restore a member's debrid.
**Bulk examples:** add an addon to everyone → `insert into member_addon (user_id, url, sort_order)
select id, '<url>', 99 from auth.users on conflict do nothing;` · swap an expired URL everywhere →
`update member_addon set url='<new>' where url='<old>';` (if it's a *default*, also edit
`default_member_addons()` so future seeds use the new URL) · **reset ONE member** → the reset block above ·
**reset EVERYONE** → `truncate member_addon;` then re-run the back-fill. **Test destructive bulk SQL on
one throwaway account before fan-out** (Gap H) — there is no client-side undo and changes mirror to every
TV on next open.

---

## App — changes (KevBox `full` flavor; keeps `playstore` + upstream untouched)

§0 covers the two structural changes (legacy-sync gate + primary-targeted apply). The rest:

1. **Serializable model** (new, full): `MemberAddonRow(url: String, enabled: Boolean = true,
   sortOrder: Int = 0)` with `@SerialName("sort_order")` on `sortOrder`, marked `@Serializable @Keep`.
   **R8 (Gap D):** the broad `-keep class com.nuvio.tv.domain.model.** { *; }` only covers `domain.model`;
   a full-flavor-packaged model needs its own explicit serializer keep (the generic `serializer(...)`
   rule alone was insufficient for `updater.model`). Put the model in e.g.
   `com.nuvio.tv.core.memberconfig.model` and add, next to the updater block in `proguard-rules.pro`:
   ```
   -keep class com.nuvio.tv.core.memberconfig.model.** { *; }
   -keepclassmembers class com.nuvio.tv.core.memberconfig.model.** {
       *** Companion;
       kotlinx.serialization.KSerializer serializer(...);
   }
   -keep,allowobfuscation,allowshrinking class com.nuvio.tv.core.memberconfig.model.**$$serializer { *; }
   ```

2. **`AddonRepository.applyRemoteAddonConfig(...)`** — interface addition + primary-targeted impl, per
   §0.B (reuses the empty-list guard; reconcile-order before enabled-states; single `isSyncingFromRemote`
   writer now that the legacy pull is gated off).

3. **`MemberConfigService`** (new, full, `@Singleton`) — injects `Postgrest`, `AuthManager`,
   `AddonRepository`, and a small `MemberConfigPreferences` (DataStore) to remember the last-applied
   `userId`. Observe `authManager.authState`; on `AuthState.FullAccount`:
   ```kotlin
   // Tolerant decode + JWT-refresh retry (Gaps E, "expired token on an always-on TV").
   val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
   val rows: List<MemberAddonRow> = withJwtRefreshRetry {        // reuse AuthManager.refreshSessionIfJwtExpired
       postgrest.from("member_addon").select {
           filter { eq("user_id", state.userId) }                 // RLS re-checks server-side
           order("sort_order", Order.ASCENDING)
           order("id", Order.ASCENDING)                           // deterministic tiebreaker (Gap J)
       }.decodeList<MemberAddonRow>()                              // decode tolerant; one bad row shouldn't nuke all
   }
   val changedUser = state.userId != memberConfigPreferences.lastAppliedUserId()
   if (rows.isNotEmpty()) {
       addonRepository.applyRemoteAddonConfig(rows.map { it.url }, rows.associate { it.url to it.enabled })
   } else if (changedUser) {
       addonRepository.resetPrimaryAddonsToDefaults()             // shared-TV account switch (Gap K)
   } // else: no rows, same user → keep current (baked defaults)
   memberConfigPreferences.setLastAppliedUserId(state.userId)
   ```
   - **Distinct-outcome logging (Gaps E, "no observability"):** log `applied N rows` / `reset to defaults`
     / `empty-keep` / and the failure cause (network vs JWT vs decode) so a headless TV is diagnosable.
   - **Failure semantics:** wrap in try/catch (non-fatal). On failure the device **keeps its prior
     addon state** (not necessarily defaults) — state this explicitly; do not silently assume defaults.
   - **All-disabled footgun (operator note):** rows that are all `enabled = false` install everything
     then disable everything → empty catalogs. Intended (installed-but-disabled), but warn the operator.

4. **Startup wiring (closes "nothing injects it").** Make `MemberConfigService` start at full-flavor
   startup by injecting it into the **full** `PluginManager` constructor and calling
   `memberConfigService.start()` from `PluginManager.init{}` (the full `PluginManager` is already
   constructed at startup transitively via `NuvioApplication → StartupSyncService → PluginManager`, and
   already self-kicks `seedDefaultPluginsIfFirstLaunch` from `init{}`). `start()` launches the
   `authState` collector on the service's own scope. The `playstore` `PluginManager` stub doesn't
   reference it → `playstore` unaffected. **Do not** add `MemberConfigService` to the shared
   `NuvioApplication` injection set.

**Reused primitives (verified against code):**
- `AddonRepositoryImpl.reconcileWithRemoteAddonUrls` / empty-list guard — `data/repository/AddonRepositoryImpl.kt`
- `AddonPreferences.effectiveProfileId()` (inheriting → store 1), `canonicalizeUrl`, `getDefaultAddons` — `data/local/AddonPreferences.kt`
- `AddonSyncService.withJwtRefreshRetry` + `AuthManager.refreshSessionIfJwtExpired` — `core/sync/AddonSyncService.kt`
- `SupabaseModule` (Postgrest installed) — `core/di/SupabaseModule.kt`
- `AuthManager.authState` / `AuthState.FullAccount(userId, email)` — `core/auth/AuthManager.kt`, `domain/model/AuthState.kt` (only `SignedOut`/`Loading`/`FullAccount` exist)
- supabase-kt **3.1.4**; query shape `postgrest.from("…").select { filter { eq(...) }; order(...) }.decodeList<T>()` (same shape `AddonSyncService` uses)

---

## Verification (end-to-end)
1. **Server:** run the SQL; `select * from member_addon` / `member_addon_v`; RLS on; `unique(user_id,url)` enforced; advisor shows no `function_search_path_mutable` (if the trigger is used).
2. **Legacy gate (Gap A):** with `FEATURE_MEMBER_ADDON_CONFIG=true`, confirm `StartupSyncService` does **not** run the `addons`-table reconcile (log absent), so `member_addon` is the only addon writer.
3. **Profile (Gap B):** on a **non-primary kid profile that uses primary addons**, change a member's rows → reopen → Addon Manager reflects them (the apply targeted the primary store, not no-op'd).
4. **Read scoping:** app anon key + kevin's JWT, `from("member_addon").select()` returns only kevin's rows. (Pre-check via `curl` to `/rest/v1/member_addon` with kevin's access token + `apikey` header.)
5. **Mirror / order:** give kevin a deliberately different ordered set → reflected exactly, no restart; delete a row → addon gone next open. Tie `sort_order` values → order is still deterministic (id tiebreaker).
6. **On/off:** set one row `enabled=false` → shows installed-but-disabled.
7. **Edit/reconfigure:** change a row's `url` (canonical form) → reconfigured addon replaces the old next open.
8. **Auto-seed + fallback:** a fresh sign-up gets the 4 universal default rows (trigger fired); per-member
   debrid (Torrentio + AIOStreams) is added via the onboarding insert; the one-time
   back-fill populated all existing members; and as a safety fallback a zero-row member (e.g. trigger
   disabled) still keeps the baked defaults on-device.
9. **Account switch (Gap K):** on a shared device, sign out kevin → sign in a member with zero rows → list resets to baked defaults (not kevin's).
10. **Failure (Gap E):** airplane-mode cold start → no crash, prior addons kept, failure logged; force an expired JWT → retry refreshes and applies.
11. **Bulk / kill-switch (Gap H):** bulk SQL affects all members' next open; rebuilding with `FEATURE_MEMBER_ADDON_CONFIG=false` reverts to legacy/default behaviour.
12. **Release/R8 (Gap D):** `assembleFullRelease` (throwaway version via `release.sh`) → confirm `MemberAddonRow` still deserializes (keep rules).
13. **playstore safe:** `./gradlew :app:compilePlaystoreDebugKotlin` passes (no full-only refs leaked into main; flag is `false`).
14. **Canary:** apply to ONE test account, verify on a spare device, then fan out.

## Upstream-mergeability
Still mostly additive, but v2 has a **slightly larger conflict surface** than the first draft — call it out
during the next upstream merge:
- `AddonRepository` interface + `AddonRepositoryImpl` additions (new method).
- `AddonPreferences` new primary-targeted methods (additive).
- **`StartupSyncService` guarded `if (!BuildConfig.FEATURE_MEMBER_ADDON_CONFIG)`** around the legacy
  `addonJob` / `requestAddonSyncNow` — a shared `main` file, so most likely to conflict; keep it a tiny
  hide-don't-delete guard.
- `app/build.gradle.kts` one `buildConfigField` per flavor.
- New full-flavor files (`MemberAddonRow`, `MemberConfigService`, `MemberConfigPreferences`) + a 1-line
  `start()` call wired from the full `PluginManager` + `proguard-rules.pro` keep block.
Follows the same fork rules as `UPSTREAM-SYNC.md` (full-flavor, additive, hide-don't-delete).
