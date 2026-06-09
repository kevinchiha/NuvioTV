# Gap analysis — MEMBER-CONFIG-PLAN.md

> Produced by an adversarial, code-grounded review (8 reviewers → per-finding refutation against the
> actual codebase + the plan's locked decisions). 54 candidate gaps raised, **20 confirmed**, 34 dismissed.
> Findings are consolidated/deduplicated below; the raw refuted list is in the appendix.
> **Signal note:** the two biggest gaps (A and B) were each independently re-discovered by 2–4 separate
> reviewers with matching file:line evidence — treat them as high-confidence, not speculative.

---

## A. CRITICAL — A second addon reconciler already runs on the same trigger (`StartupSyncService`)

The plan's premise that the legacy NuvioTV sync is a *"dead RPC, harmless"* (plan lines 95, 125) is
**factually wrong at runtime.** `StartupSyncService.pullBroadRemoteData` already calls
`addonRepository.reconcileWithRemoteAddonUrls(remoteUrls, removeMissingLocal = true)` on **every**
`AuthState.FullAccount` — the exact trigger `MemberConfigService` keys off — pulling from the live
`addons` table via `AddonSyncService`.

Consequences:
- **Two reconcilers fight every app-open.** One mirrors `member_addon`, the other mirrors the legacy
  `addons` table. Both write `preferences.setAddonOrder` on the same DataStore keys; last writer wins,
  non-deterministically. The headline "mirror exactly" guarantee silently loses to the legacy path.
- **Shared non-atomic flag.** Both toggle the same plain `var isSyncingFromRemote`
  (`AddonRepositoryImpl.kt:53`). Whichever finishes first flips it back to `false`, un-suppressing
  push-back for the other still-running reconcile.
- **Enabled-state clobber.** `AddonSyncService.getRemoteAddonUrls()` also calls
  `setAddonEnabledStates(...)` (full-replace, not merge) whenever the legacy table is non-empty — it can
  revert the on/off map `MemberConfigService` just wrote. A member you set to *disabled* comes back enabled.

**Evidence:** `StartupSyncService.kt:414-428` (live addon reconcile, `removeMissingLocal=true`), `:70-99`
(collect on `FullAccount`); `AddonSyncService.kt:98-119`; `AddonRepositoryImpl.kt:53`. Plan 95, 125.

**Fix:** decide which table is authoritative. For the `full` flavor, gate/short-circuit
`StartupSyncService`'s addon job (the legacy `addons` table is presumably empty for these users — *verify
kevin's isn't*), or coordinate both through one mutex (the codebase already has a `reconcileMutex` pattern
for plugins). Do **not** share `isSyncingFromRemote` between two reconcile sources. The plan must
acknowledge this path — right now it's invisible to it.

---

## B. CRITICAL — The apply silently no-ops on non-primary profiles

Every mutating method the plan reuses — `setAddonOrder`, `setAddonEnabledStates`, `addAddon`,
`removeAddon` — begins with:

```kotlin
val active = profileManager.activeProfile
if (active != null && !active.isPrimary && active.usesPrimaryAddons) return
```

`reconcileWithRemoteAddonUrls` writes via `setAddonOrder` (`AddonRepositoryImpl.kt:305`);
`applyRemoteAddonConfig` writes via `setAddonEnabledStates`. **Both are gated.** `MemberConfigService`
keys solely off `AuthState.FullAccount` and never inspects the active *local* profile.

So if the TV cold-starts on a kid/guest sub-profile that inherits the primary's addons (a normal,
supported config — and the exact multi-profile family setup this feature targets), the entire remote
apply is **silently dropped**: no exception, no log, `try/catch` sees success. The operator flips a row,
the member reopens, nothing changes, and there is zero diagnostic. This is the single most likely cause
of "I changed it but the TV didn't update."

**Evidence:** `AddonPreferences.kt:99-100,113-114,133-134,146-147,156-157` (write guards); `:30-33`
(`effectiveProfileId` read-routes sub-profiles to store id 1, but the *write* guards still bail);
`AddonRepositoryImpl.kt:305`. The plan never mentions `ProfileManager`.

**Fix:** route the write to the **primary/effective** addon store (the same `effectiveProfileId()` the
read Flow already uses), or explicitly require member devices to use the primary profile — and at minimum
**log when a write is gated** so a headless TV can be diagnosed.

---

## C. HIGH — The seed trigger breaks every member it touches

`seed_member_addons()` (plan 54-60) does **not** match the real baked-in defaults
(`DefaultContent.DEFAULT_ADDON_URLS`, `DefaultContent.kt:29-35`; mirror at `AddonPreferences.kt:252-263`):

- Three rows are unreplaced literal placeholders — `'<OpenSubtitles v3 Pro url>'`,
  `'<Netflix-catalog url>'`, `'<Usenet Ultimate url>'` (orders 1, 3, 4).
- The line-60 comment falsely asserts these *are* `DefaultContent.DEFAULT_ADDON_URLS, in order`.

Because the mirror runs with `removeMissingLocal=true` and a 5-row list doesn't trip the empty-list guard
(`AddonRepositoryImpl.kt:267`), the **first open** of any seeded member replaces the correct defaults with
a list containing literal `<...>` strings as addon base URLs — wiping the working Pro-OpenSubtitles /
Netflix / Usenet addons. And because the trigger gives every new member rows, **no member is ever
"untouched"** — it directly defeats the locked "no rows = keep defaults" guarantee.

**Fix:** either drop the auto-seed entirely (it contradicts the locked rule and adds a *third* copy of the
addon list to keep in sync), or paste the exact 5 canonical URLs verbatim from `DefaultContent.kt:29-35`,
in order. If kept, comment it as a third mirror of the defaults. Also confirm the baked **Netflix-catalog
token isn't already expired** before seeding it (it was flagged pre-expired in prior work).

---

## Medium

**D. R8 / release-build keep rule is under-specified.** The plan says "add to the existing keep block,"
but the broad `domain.model.**` keep won't cover a model placed in the `full` flavor, and the generic
serializer rule alone was deemed insufficient for the updater model — which needed an explicit
`-keep,allowobfuscation,allowshrinking class ...model.**$$serializer { *; }` plus a Companion keep
(`proguard-rules.pro:43,87-89,97-102`). The plan doesn't state the new model's package. Risk: a minified
`assembleFullRelease` blanks/fails to deserialize `MemberAddonRow` — works in debug, breaks in the signed
APK the family installs. **Fix:** pin the package and add the concrete `$$serializer` keep.

**E. Error handling is too coarse — failures are invisible.**
- `decodeList<MemberAddonRow>` is all-or-nothing: **one** malformed row throws and the whole `try/catch`
  swallows it → that member's entire config silently fails to apply. Use per-row tolerant decoding
  (`ignoreUnknownKeys`, `coerceInputValues`).
- A dead/404 URL installs as a non-functional placeholder with empty catalogs and no error
  (`AddonRepositoryImpl.kt:220-223,309-331`).
- **No JWT-refresh retry.** The legacy services wrap reads in `withJwtRefreshRetry`
  (`AddonSyncService.kt:98`) precisely because tokens expire; the plan's single `select` does not. On an
  always-on TV the first "open" after token expiry throws, gets swallowed, and the change never lands.
  **Fix:** reuse `withJwtRefreshRetry`; log distinct outcomes (network vs jwt vs empty).

**F. `SECURITY DEFINER` trigger has no `set search_path`** (plan 52) — the canonical Postgres/Supabase
privilege-escalation footgun (flagged by Supabase's `function_search_path_mutable` linter). It runs on
`insert on auth.users`, i.e. on an attacker's own sign-up under a partly attacker-controlled path. **Fix:**
add `set search_path = ''` and fully-qualify every reference (`public.member_addon`, `pg_catalog.now()`).

**G. URL canonicalization mismatch.** The device dedupes/identifies addons on a canonical form (strips
trailing `/manifest.json`, lowercases — `AddonPreferences.kt:53-64`, `AddonRepositoryImpl.kt:70,262`), but
the DB `unique(user_id,url)` is over **raw** text. So `…/manifest.json` and the bare URL are two distinct
rows the DB accepts but the device silently merges — and which survives (and at what position) depends on
order. Two real defaults carry `/manifest.json`. **Fix:** define the canonical form as the contract
(store without `/manifest.json`, lowercased) and optionally enforce it with a normalizing trigger/CHECK.

**H. No kill-switch or rollback.** With `removeMissingLocal=true` and bulk SQL (plan 71-73), one operator
typo (`update … set url=… where …`) propagates to every TV on next open with no client-side undo and no
documented "reset member to defaults" SQL. A bad bulk update could leave every family TV with nothing
playable. **Fix:** add a remote/build kill flag that no-ops `MemberConfigService`, and document a
reset-to-defaults snippet (canonical list at `AddonPreferences.kt:252-263`). Test destructive SQL on one
account first.

**I. Device/profile model unstated + no rollout/test plan.** `member_addon` is keyed by `user_id` only,
but the existing system scopes by `user_id` **and** `profile_id` (`AddonSyncService.kt:100-103`). Same
account on two TVs mirrors the same rows; the plan never states this is intended. Beyond manual on-TV
steps there's no canary (apply to one test member first) and no instrumented read test. **Fix:** state the
device/profile model; add a one-member canary rollout before fan-out.

---

## Low / Nit

**J. `sort_order` ties are non-deterministic.** Default `0` + a read `order("sort_order", ASC)` with no
secondary key → tied rows return in arbitrary order, and the bulk examples mix `0`/`99`. Order drives
catalog *and* stream-source priority. **Fix:** add `order("id", ASC)` (or `url`) as a tiebreaker.

**K. Account-switch cross-member leak on a shared TV.** Sign-out doesn't clear the local addon DataStore
(`AuthManager.kt:214-227`; addon state is keyed by profile, not user). So after member A signs out, member
B — if they have **zero** rows — keeps member A's mirrored list as "defaults," violating "no rows = baked
defaults." **Fix:** on a `FullAccount` userId change where the new member has no rows, reset to
`DefaultContent.DEFAULT_ADDON_URLS`; track last-applied userId.

**L. Denormalized `email` goes stale.** The seed copies `new.email` at insert and nothing updates it
afterward (the app only reads; the trigger is `after insert` only). If a member changes their email, the
operator's primary workflow — *filter by email* — silently targets the wrong/old address, or splits across
rows. **Fix:** drop the column and join to `auth.users` in a dashboard view, or add an
`after update of email on auth.users` propagation trigger.

---

## Appendix — dismissed candidates (34)

These were raised but refuted on inspection (already handled by the plan, contradicted by the code, or an
accepted tradeoff under a locked decision). A few worth knowing were dismissed only because they duplicate
a confirmed gap above (e.g. the `StartupSyncService` race was also raised — and refuted as a duplicate —
under auth-lifecycle and completeness-meta, but is **confirmed** as Gap A). Two minor verify-this items
among the dismissed set: confirm the exact `AuthState` API shape (one reviewer claimed
`FullAccount(userId,email)` / `Limited`/`Anonymous` don't all exist as written), and note that an
all-`enabled=false` row set yields a fully-installed-but-empty app with no fallback to defaults.
