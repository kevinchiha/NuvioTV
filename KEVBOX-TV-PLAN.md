# KevBox TV — Rebrand, Self-Owned Backend, Baked-in Content & VPS Auto-Update

## Context

The NuvioTV app (a fork of `NuvioMedia/NuvioTV`) is deployed to non-technical family
members. Two problems drive this work:

1. **Login headaches.** Family members get logged out of their **Nuvio (Supabase)**
   accounts and can only sign back in via a **QR "TV login"** flow they can't operate —
   so they call for help. The original Supabase backend powering that flow is **no longer
   accessible** to us, so the QR/sync features are effectively dead anyway.
2. **The app is upstream-branded** (`Nuvio`) and its in-app updater points at the
   upstream GitHub repo — neither is acceptable for a private family build.

**Goal / outcome:** a self-contained **"KevBox TV"** app the family can use with
near-zero login friction, pre-configured out of the box, that auto-updates from our own
VPS. Each member keeps their own account; re-login is "type a password" (or one tap),
never a QR scan. Watch history rides on **Trakt** (per account). Addons + scraper plugins
are **baked into the APK** so a fresh install is fully configured. Updates are hosted on
**persovps** exactly like the existing `kevbox-support` app.

We are committing to the **`full` flavor only** (the `playstore` flavor is abandoned).
A standing design constraint: keep the fork **easy to update from upstream** — every change
below is structured to minimize merge conflicts (see "Upstream porting / fork maintenance").

## Locked decisions

| Decision | Choice |
|---|---|
| Backend scope | **Auth-only** new Supabase project (no sync RPCs/edge fn/web page rebuild). Trakt covers history; addons/plugins baked in. |
| Login UX | **Email + password on TV**, with **remember-last-user** (prefill email; optional encrypted one-tap). QR flow removed. |
| Auto-update source | **Self-hosted `version.json` on persovps** (kevbox-support model), versionCode-compared, SHA-256 verified. |
| Update UX | Reuse NuvioTV's existing dialog (manual check + progress bar already exist) **+ add download speed + ETA** (ported from kevbox-support). |
| Rebrand assets | Use `kevbox-support/public/Logo/KEVbox.png` (+ paraph logo) as source art. |
| applicationId | **Change to `tv.kevbox`** (keep Kotlin `namespace = com.nuvio.tv` to avoid renaming 498 files). Fresh install identity. |
| Name sweep | **Full sweep** of user-visible `Nuvio`→`KevBox TV`, done via **`full`-flavor resource overrides** (`app/src/full/res`), **not** in-place edits to `main` — keeps upstream merges clean. |
| Flavor | `full` only. |
| Fork upkeep | Stay **upstream-mergeable**: keep `namespace = com.nuvio.tv`, prefer flavor overrides + new files, and **disable** (don't delete) unused upstream code. See "Upstream porting / fork maintenance". |

## What the user must provide / do (handoffs)

- **New Supabase project** (you own it): `SUPABASE_URL` + key in `local.properties` ✅ added.
  ✅ **Publishable key verified server-side** (GoTrue `/auth/v1/settings` → 200; password-grant
  reaches auth with `invalid_credentials`). Email/password provider **enabled** ✅. ⚠️ But
  **email confirmation is currently ON** (`mailer_autoconfirm=false`) → either turn it **off**
  (Auth → Providers → Email → "Confirm email") **or** create each member via **Auth → Users →
  Add user** with auto-confirm, so they can sign in immediately (the app only ever signs **in**,
  never sign-up). ⚠️ Free-tier projects **auto-pause after ~7 days idle** → whole family locked
  out; use a keep-alive ping or paid tier; consider a longer refresh-token lifetime to cut
  logouts at the source. (Residual: confirm the first *in-app* sign-in works under supabase-kt
  `3.1.4` — server is fine; if it ever 401s client-side, swap to the legacy anon JWT.)
- **API keys — REQUIRED, currently blank** (build defaults them empty → set in `local.properties`):
  **`TRAKT_CLIENT_ID` + `TRAKT_CLIENT_SECRET`** (register an app at trakt.tv/oauth/applications —
  **without these the entire watch-history pillar is dead**: `TraktAuthService.hasRequiredCredentials()`
  returns false) and **`TMDB_API_KEY`** (posters/metadata/Discover). Decide whether any optional
  keys (trailers, IMDb ratings, debrid client ids) matter for your setup.
- ~~**Addon URLs** to bake in~~ — **provided** (5 addons, listed in Workstream 4).
  Still needed: **Plugin repo URLs / `cutt.ly` codes** to bake in (full-flavor scrapers).
- **Release keystore**: confirm whether you have a keystore you control, or we generate a
  fresh `kevboxtv.jks`. The updater requires every APK (first install + all updates) to be
  signed with the **same** key — losing it means you can't ship updates. Keep it backed up.
- ~~**VPS DNS**: point `tv.kevbox.dev` at persovps~~ — ✅ **done**. (WS5 still runs
  `certbot --nginx -d tv.kevbox.dev` once the vhost exists.)

---

## Execution notes (for whoever implements this)

- **Use the graphify graph for code exploration — not Explore/subagents or broad greps.** This
  repo has a prebuilt graph at `graphify-out/graph.json`; run `graphify query "<question>"` (or
  the `/graphify` skill) to locate code. It is far cheaper in tokens than spawning Explore
  agents or reading whole files. Drop to **targeted `Read`s** only for the exact lines you'll
  edit, and to the graph's known blind spots (native/JNI, JS scrapers, debrid HTTP — see the
  `nuviotv-graph-navigation` memory). If the code has drifted, refresh first with
  `graphify <path> --update`.

## Workstream 1 — Rebrand Nuvio → KevBox TV

**Approach:** change app **identity + visible text + art**, but keep the internal Kotlin
package (`com.nuvio.tv`) to avoid a risky 498-file rename. **All brand overrides live in the
`full`-flavor resource folder (`app/src/full/res/...`), which Android merges *over* `main` at
build time — so we never edit upstream's `main` resources and future merges stay clean** (see
"Upstream porting / fork maintenance").

- **App identity** — `app/build.gradle.kts` (one file, small localized edits):
  - `applicationId = "tv.kevbox"` (was `com.nuvio.tv`); keep `namespace = "com.nuvio.tv"`.
  - Update the debug-variant override block (`androidComponents { onVariants … }`, lines
    ~284-288) from `com.nuviodebug.com` to e.g. `tv.kevbox.debug`.
  - FileProvider authority `${applicationId}.fileprovider` updates automatically.
- **Visible name (flavor override, not in-place)** — create `app/src/full/res/values/strings.xml`
  (and per-locale `values-*/strings.xml`) that **override** the brand strings by `name`:
  `app_name` → `KevBox TV`, plus every user-visible `Nuvio`/`NuvioTV` → `KevBox TV` string
  (~700 across locales). Only the overridden entries need to be present; this leaves
  `app/src/main/res` **untouched**. For any hardcoded brand text in Kotlin, route it through a
  string resource so it can be overridden the same way. Do **not** touch `com.nuvio.tv` package
  paths.
  - Trade-off: an overridden string won't auto-pick-up upstream *wording* changes — fine for
    brand text, which rarely gets non-brand rewrites.
  - ⚠️ **Audit, don't blind-replace:** of the ~700 `nuvio` hits, many are `app.nuvio.tv` URLs,
    Discord/GitHub/donation links, and "Nuvio account" feature copy. Rebrand the **name**, but
    **repoint or remove** dead URLs/community links, and leave technical values alone.
- **Art (flavor override)** — drop replacements into `app/src/full/res/` so they override `main`:
  - Launcher icon `mipmap-*/ic_launcher` (all densities) + `drawable/ic_launcher.png`.
  - Android TV leanback banner `mipmap-xhdpi/banner.png` (320×180) + `drawable-nodpi/tv_banner.png`
    + `drawable/tv_banner.png`.
  - In-app logo `drawable/nuvio_text.png` → KevBox wordmark.
  - ⚠️ Also check/override: `mipmap-anydpi-v26/ic_launcher.xml` (adaptive-icon foreground/
    background layers), `ic_launcher_round`, the **splash screen** logo/theme (app uses
    `core-splashscreen`), and any notification small-icon / channel name that says "Nuvio".
  - Source art: `kevbox-support/public/Logo/KEVbox.png`; generate densities with ImageMagick.
- **Manifest** — `app/src/main/AndroidManifest.xml` already references `@string/app_name`,
  `@mipmap/banner`, `@mipmap/ic_launcher`; the flavor overrides above flow through with **no
  manifest edit**.
- **Out of scope:** `NuvioColors` theme (`app/src/main/java/com/nuvio/tv/ui/theme/Color.kt:64`)
  — colors unchanged unless you ask. Internal class names (`NuvioApplication`/`NuvioDialog`)
  stay (invisible).

## Workstream 2 — New self-owned Supabase backend (auth-only) + repoint

NuvioTV depends on **36 Supabase RPCs + 1 edge function + a hosted web page** — all gone
with the old backend. We rebuild **none** of it; email/password auth is built-in to any
new project. Verified safe: sign-in depends on no RPC, and sync only runs when fully
signed in and is wrapped in try/catch (`StartupSyncService.kt:72-146`, non-fatal).

- Point the app at the new project via `local.properties` (`SUPABASE_URL`,
  `SUPABASE_ANON_KEY`) — already wired through `app/build.gradle.kts:205-206`. See handoffs for
  the **publishable-key vs legacy-anon-key** caution and the **free-tier auto-pause** risk.
- Client is ready as-is: `core/di/SupabaseModule.kt` installs `Auth` with
  `alwaysAutoRefresh`/`autoLoadFromStorage`/`autoSaveToStorage`.
- Degraded (acceptable, non-crashing): cross-device sync, sync codes, QR login, cloud
  profiles, server-verified profile PINs, avatar catalog. **Remove/hide** the dead QR and
  sync UI (see WS3) so the family never hits a broken button.

## Workstream 3 — Email/password TV login + remember-last-user

**Two sign-in surfaces exist and BOTH must be handled** (the plan originally named only the
first):
- `AuthSignInScreen.kt` — the in-settings sign-in (QR-only today).
- `AuthQrSignInScreen.kt` — the actual QR flow, **and the full-screen first-run onboarding gate**
  rendered directly by `MainActivity.kt:451-456` on every fresh install. **This is the family's
  *first* screen** — the most important one to fix, and the one the original plan missed.

The backend method already works: `AccountViewModel.signIn(email, password)` →
`AuthManager.signInWithEmail()` (no RPC needed).

- **Build one reusable email+password form composable** and use it in **both** the first-run
  onboarding slot (replace `AuthQrSignInScreen` at `MainActivity.kt:456`) and the settings
  sign-in (`AuthSignInScreen`). TV-focusable `TextField`s + platform IME (TV text entry is
  finicky — add a password-visibility toggle and test D-pad focus). On success
  (`AuthState.FullAccount`) continue/close; inline error from `AccountUiState.error`. Keep a
  "Skip for now" so the app stays usable unauthenticated.
- **Disable, don't delete, the *whole* sync/QR surface** (not just one button): the QR entry on
  `AuthSignInScreen` **and** — on the **Account screen** (`AccountScreen` /
  `AccountSettingsContent`) — the sync-code generate/claim, linked-devices, and sync-overview
  sections, all backed by missing RPCs. Hide the entry points; leave files in place for clean merges.
- **Account creation = Supabase dashboard**, not an in-app signup form (there is none on TV).
  You add each member under Auth → Users; the app only ever signs **in**.
- **Remember last user:** add `LastSignInDataStore` storing the last email; prefill it and show
  "Sign in as &lt;email&gt;". For near **one-tap** re-login on a trusted family TV, store the
  credential encrypted via the **Android Keystore** (note: `EncryptedSharedPreferences` is
  deprecated — use a Keystore-backed AEAD + DataStore, or accept plaintext on-device). Given TV
  text-entry pain, treat **one-tap as the *primary* re-login path**, not an afterthought.

## Workstream 4 — Bake addons + plugins into the APK (full flavor)

The app already seeds default **addons** on first launch; we extend that and add a
parallel seed for **plugins**.

- **Addons** — `data/local/AddonPreferences.kt:224` `getDefaultAddons()`. Replace the
  current Cinemeta + OpenSubtitles set with the **family's addons, in this exact order**
  (order matters — it drives catalog row order + stream/source priority). `getDefaultAddons()`
  returns a `Set` (unordered), so seed as an **ordered list** and apply it via
  `AddonRepositoryImpl.setAddonOrder(...)` (`AddonRepositoryImpl.kt:241`) so the first-launch
  install order matches. Auto-installed on first launch:

  1. `https://v3-cinemeta.strem.io` — Cinemeta (metadata + catalogs)
  2. `https://opensubtitlesv3-pro.dexter21767.com/eyJsYW5ncyI6WyJlbmdsaXNoIiwiZnJlbmNoIl0sInNvdXJjZSI6ImFsbCIsImFpVHJhbnNsYXRlZCI6dHJ1ZSwiYXV0b0FkanVzdG1lbnQiOnRydWV9/manifest.json` — OpenSubtitles v3 Pro (EN/FR, AI-translated, auto-adjust)
  3. `https://opensubtitles-v3.strem.io` — OpenSubtitles v3
  4. `https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/bmZ4LGRucCxhbXAsYXRwLGhibSxwbXAscGNwLGhsdSxjcnUsY3RzLG1nbCxjbHYsaGF5LGdvcCxqaHMsc3N0LHZpbCxubHosemVlLGNwZCxzdHosZHBlLG1iaSxzb255bGl2LHNnbyx2aWssYmJvLGl0dixtcDksYWN0LGNyYyxzaGQsYWw0LGJiYyxpcWksc2hhOjo6MTc4MDgxOTU2NTc1MDowOjA6TEI%3D/manifest.json` — streaming-service catalogs (Netflix, etc.)
  5. `https://stremio.kevbox.dev/stremio/aff1c9f5-75b6-49ab-bc7d-bdbe49a72e78/manifest.json` — your own addon (hosted on persovps)
- **Plugins** — `app/src/full/java/com/nuvio/tv/core/plugin/PluginManager.kt`. ⚠️ Plugins are
  **not truly baked into the APK** — only the *repo URL* is. Seeding registers the repo and
  **downloads the scraper JS at runtime** (`downloadJsScrapers`), so a fresh install needs
  **network on first run** and ongoing repo-server availability (if a repo dies, new installs
  lose those streams). True offline resilience would mean bundling the scraper JS in `assets/`
  (bigger change — decide if worth it). Add a one-time first-launch seed: new flag in
  `data/local/AppOnboardingDataStore.kt` (mirrors `hasSeenAuthQrOnFirstLaunch`); **branch by repo
  type** — `addRepository()` for a JS manifest URL, `addNuvioRepository()` (L386) for a Nuvio
  repo, `addExternalRepository()` for DEX/CloudStream, resolving `cutt.ly` short-codes first.
  Hook from `NuvioApplication`/`MainActivity` startup.
- ⚠️ **Addon token expiry:** addon #4 (Netflix catalog) embeds a config blob ending
  `…:::1780819565750:0:0:LB` — that 13-digit value parses as an **expiry timestamp (~2026)**. A
  baked-in URL that expires silently breaks for *all* installs. Confirm each configured addon
  issues a **non-expiring** URL before baking it in.
- Keep the URL lists as Kotlin constants in a **new** file (e.g.
  `app/src/full/java/com/nuvio/tv/core/content/DefaultContent.kt`) compiled into the APK; the
  edits to upstream files (`getDefaultAddons()`, the startup hook) stay to a line or two each,
  so future merges barely touch them. Every install is pre-configured, no cloud restore needed.

## Workstream 5 — Self-hosted auto-update from persovps (kevbox-style)

NuvioTV already ships a **full-flavor updater** (`app/src/full/java/com/nuvio/tv/updater/`:
`UpdateRepository`, `UpdateViewModel`, `UpdatePreferences`, `model/AppUpdate`, `ui/UpdatePromptDialog`)
gated by `FEATURE_IN_APP_UPDATES_ENABLED`. It auto-checks on launch, has a **manual check**
(`AboutScreen.kt:135` → `checkForUpdates(force=true, …)`) and a **progress bar**
(`UpdatePromptDialog.kt:359-411`). It currently fetches **GitHub Releases**
(`GITHUB_OWNER="tapframe"`, `GITHUB_REPO="NuvioTV"`, `GitHubReleaseApi`).

**Repoint the data layer to a self-hosted `version.json`** (mirror kevbox-support):

- **App side:**
  - Add `BuildConfig` field `UPDATE_BASE_URL` (default `https://tv.kevbox.dev`) in
    `app/build.gradle.kts` (drop/ignore `GITHUB_OWNER`/`GITHUB_REPO`).
  - Replace `UpdateRepository.getLatestUpdate()` internals to fetch
    `UPDATE_BASE_URL + "/version.json"` and parse a manifest. ⚠️ The existing `AppUpdate` model +
    dialog render **release notes (markdown)** and a version tag, so the manifest must include
    them: **`{ versionCode, versionName, url, sha256, notes }`** — map `versionName`→`tag`/`title`
    and `notes`→`notes` (a bare kevbox `{…,url,sha256}` would blank the dialog's notes section).
    Mark the model `@Serializable` + add an R8 **keep rule**/`@Keep` and verify a **release**
    (minified) build parses it — not just debug.
  - Switch the "newer?" test from `VersionUtils.isRemoteNewer(tag, versionName)` to
    **`manifest.versionCode > BuildConfig.VERSION_CODE`** (kevbox model); also re-key
    `UpdatePreferences.ignoredTag` ("ignore this version") off versionCode.
  - Add **SHA-256 verification** before install (port kevbox `Checksum.verify` into the
    `ApkDownloader`/install path; `Updater.verifyAndInstall` is the reference).
  - **Add download speed + ETA** (the only new UX): extend `UpdateUiState` with
    `bytesPerSec` + downloaded/total, compute in `UpdateViewModel.downloadUpdate()`
    (`bps = downloaded*1000/elapsed`, kevbox `MainViewModel.kt:108-110`), and render in
    `UpdatePromptDialog` near the percent row (`formatSpeed = "%.1f MB/s"`,
    `formatEta` — kevbox `UpdateDialog.kt:151,220-221`).
  - Install infra already present: `REQUEST_INSTALL_PACKAGES` + `${applicationId}.fileprovider`
    in the manifest; `ApkInstaller` handles the unknown-sources prompt.
- **VPS side (persovps, mirror `support.kevbox.dev`):**
  - New web root `/var/www/kevbox-tv/` holding `version.json` + `kevbox-tv-<ver>.apk`.
  - New nginx vhost `tv.kevbox.dev` (copy the support vhost's `location = /version.json`
    `no-cache` + `location ~ \.apk$` blocks), TLS via `certbot --nginx -d tv.kevbox.dev`.
  - Reuse the deploy guardrail: **never re-upload the full nginx conf** (certbot injects
    TLS); patch in place.
- **Release pipeline:** clone `kevbox-support/server/scripts/release.sh` →
  `release.sh` for KevBox TV: bump `versionCode`+`versionName` in `app/build.gradle.kts`,
  build the **signed** `assembleFullRelease`, ⚠️ publish the **universal** APK from the
  **flavored** path — `app/build/outputs/apk/full/release/app-full-universal-release.apk` (the
  build does ABI splits at `build.gradle.kts:235-242`, so there are several per-ABI APKs + the
  universal one; kevbox's `app/.../release/app-release.apk` path is wrong here). `scp` to
  persovps, `sha256sum` on host, write `/var/www/kevbox-tv/version.json` (incl. `notes`), commit.
  ⚠️ This APK is **large** (~80–150 MB — bundled FFmpeg/ExoPlayer/mpv/native libs) vs kevbox's
  9.8 MB; mind VPS storage (keep only the last few versions) and family download bandwidth.
  (No backend needed — pure static.)

## Workstream 6 — Signing & release identity

The auto-updater **requires a stable signing key** you control (every APK signed with the
same key; first install + updates).

- `app/build.gradle.kts:157-164` already has a `release` signing config reading
  `NUVIO_RELEASE_STORE_FILE`/`_KEY_ALIAS`/`_KEY_PASSWORD` (defaults `../nuviotv.jks`,
  alias `nuviotv`, hardcoded fallback password `815787` — upstream's; override it).
  **Action:** generate a **KevBox TV keystore** you own (e.g. `kevboxtv.jks`), set its path +
  all four creds in `local.properties` (or a `~/kevbox-keys/release.env` like kevbox-support),
  keep secure backups. Without these set, release builds try the missing `../nuviotv.jks` and **fail**.
- ⚠️ **Same-key invariant:** every release the family installs must be signed with the *same*
  key — first install and all updates. A signature change (a build that fell back to debug
  signing via `CI_USE_DEBUG_SIGNING`, or a different machine's debug keystore) makes the update
  fail with "App not installed / signatures don't match." **Never debug-sign a family release.**
- versionCode: continue from current `1021` (monotonic) or reset for the fresh app id —
  pick one and keep `version.json.versionCode` in lockstep with the gradle `versionCode`.

## Workstream 7 — Initial distribution & first install

Because `applicationId` changes to `tv.kevbox`, KevBox TV is a **new app** — the old Nuvio
updater can't deliver it, so the first install on each TV is a **manual sideload**.

- **Stable download URL:** add `tv.kevbox.dev/download` → latest APK (kevbox-support does this).
  Static-only setup: an nginx `location = /download` returning the current APK (or an alias you
  re-point each release). Each TV then installs via the Android-TV **"Downloader" app** (enter a
  short code/URL) — no USB/adb needed.
- **Per-device one-time setup:** enable "Install unknown apps" for the Downloader **and** for
  KevBox TV (so the in-app updater can install). Then sign in (email/password) + connect Trakt.
- **Old app:** uninstall the existing `com.nuvio.tv` Nuvio app, or it coexists side-by-side.

---

## Suggested build order

1. **WS6 signing** (keystore) + **WS1 identity** (`applicationId`, app_name) — get a
   buildable, installable "KevBox TV" shell first.
2. **WS2** repoint Supabase (your project) + **WS3** email/password login — unblocks the
   family login problem.
3. **WS4** bake in addons/plugins (needs your URL lists).
4. **WS5** VPS hosting + updater repoint + speed/ETA.
5. **WS1 art + flavor-override name sweep** (polish) — can run in parallel once URLs/keys land.
6. **WS7 distribution** — `/download` URL + per-TV sideload, once the first signed APK exists.

## Verification

- **Build:** `./gradlew assembleFullRelease` (signed) and `assembleFullDebug` succeed;
  app installs as `tv.kevbox`, launcher shows **KevBox TV** name + new icon/banner.
- **Login (WS2/3):** with the new Supabase keys, create a test account; sign in on the TV
  with email/password (on-screen keyboard); kill+reopen the app → still signed in;
  sign out → last email prefilled → re-login is password-only. No QR button anywhere.
  Confirm app startup/browsing/playback unaffected (sync errors are silent in logs).
- **Baked content (WS4):** wipe app data / fresh install → Cinemeta + the family addons
  present in Addon Manager without any setup; plugin repos seeded in the full flavor;
  streams resolve.
- **Publishable key:** confirm the *very first* sign-in actually authenticates with the
  `sb_publishable_…` key; if it 401s, swap to the legacy anon JWT.
- **Trakt (needs `TRAKT_CLIENT_ID/SECRET` set):** connect Trakt on a test account → watch history
  syncs/restores independently. If creds are blank the connect button errors — that's the
  "API keys required" gap.
- **Auto-update (WS5):** publish `version.json` (versionCode = current+1) + a signed APK to
  `/var/www/kevbox-tv/` via `release.sh`; on the TV use About → **Check for updates** →
  dialog shows update, **progress bar + “x.x MB/s · ETA”**, SHA-256 verifies, installer
  launches and the new build installs over the old (same key). Confirm a tampered/wrong
  `sha256` is **rejected** (no install). Confirm an up-to-date device reports "up to date"
  on a manual check and stays silent on the launch check. Verify the published APK is the
  **universal** one and installs on a real Android TV (arm64), and that a **release** (minified)
  build parses `version.json` (R8 didn't strip the model). Test the first **sideload** via
  `tv.kevbox.dev/download` through the Downloader app.

## Upstream porting / fork maintenance

This is a fork of `NuvioMedia/NuvioTV`; everything above is structured so future upstream
updates (bug fixes, player/scraper improvements) merge with minimal friction.

**Why it stays mergeable:**
- **Namespace unchanged** (`com.nuvio.tv`) — every `.kt` file lines up with upstream, so code
  merges file-for-file. (We only change the installed `applicationId`.)
- **Brand via flavor overrides** — KevBox strings + icons live in `app/src/full/res/...` and
  override `main` at build time; we never edit upstream's `main` resources, so its string/asset
  edits merge cleanly.
- **Additive, not invasive** — baked-in addon/plugin lists and new updater pieces live in new
  files; edits to upstream files are 1–2 lines each.
- **Disable, don't delete** — unused upstream features (QR/sync screens) are hidden, not removed,
  so upstream's ongoing edits to them don't re-conflict every merge.

**Conflict surface (what to expect on a merge):**

| Area | Risk | Note |
|---|---|---|
| Code (`com.nuvio.tv.*`) | low | same package; merges line-for-line |
| `app/src/main/res` strings/assets | ~none | we override in `src/full/res`, never edit `main` |
| `app/build.gradle.kts` | low–med | one file: applicationId, update URL, signing — small edits |
| `updater/` package | medium | conflicts only if upstream reworks the updater |
| QR/sync screens | low | hidden not deleted → upstream edits merge, stay dormant |
| Supabase keys / `release.sh` / VPS | none | gitignored config + new files, not in upstream |

**Routine (every few upstream releases):**
0. **Branch strategy:** keep all KevBox changes on your long-lived branch (you're on `dev`);
   `upstream/main` is only ever *merged in*, never the reverse. Don't push KevBox commits upstream.
1. `git remote add upstream https://github.com/NuvioMedia/NuvioTV` (once).
2. `git fetch upstream && git merge upstream/main` — **small, frequent** merges beat one big one.
3. Resolve the handful of conflicts (mostly `build.gradle.kts`); rebuild `assembleFullRelease`,
   bump version, ship via `release.sh`.

**Realistic expectation:** a ~5-minute merge most of the time, occasional conflict resolution in
`build.gradle.kts` / `updater/` — not a rewrite. (Doing the rebrand as in-place edits to `main`
instead would cause string conflicts on nearly every merge; the flavor-override approach is what
avoids that.)

## Notes / risks

- Changing `applicationId` → KevBox TV installs as a **new** app (fine for fresh family
  installs; existing `com.nuvio.tv` installs won't auto-upgrade to it).
- "Install unknown apps" must be granted once per device for the updater to install APKs
  (Android TV settings) — the app already prompts and deep-links to the setting.
- Keep `version.json.versionCode` and gradle `versionCode` in lockstep or the updater
  loops/no-ops.
