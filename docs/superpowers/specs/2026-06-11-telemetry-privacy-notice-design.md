# Telemetry Privacy Notice — Design Spec

**Date:** 2026-06-11
**Status:** Approved (brainstorming) — pending implementation
**Parent:** `docs/superpowers/specs/2026-06-09-member-activity-telemetry-design.md` §11; closes plan Task 4.3 Step 4 (gap items L12/C8)

---

## 1. Purpose

The Member Activity Telemetry feature collects durations-only diagnostics (watch-time, app version, playback errors — never content). Spec §11 requires a one-line in-app notice that the box reports activity & diagnostics for service operation. Lawful basis = **legitimate interest** (operational/security telemetry, durations only, no content) — so an explicit consent gate is **not** required; a passive, discoverable notice is sufficient. This spec pins where that notice lands and its wording.

**Ordering constraint (L12/C8):** the notice must ship no later than the build that turns `FEATURE_TELEMETRY=true` for real TVs. `FEATURE_TELEMETRY` is already `true` in the `full` flavor, and no telemetry build has shipped to TVs yet (only the emulator debug build), so the sequence is: land this notice → then `./release.sh`.

## 2. Decision

A passive **"Privacy & diagnostics"** paragraph in **Settings → About** (`AboutSettingsContent`), placed just under the version line.

- **Per-flavor gate:** rendered only when `BuildConfig.FEATURE_TELEMETRY` is true. The `playstore` flavor (telemetry off) never shows it, keeping the notice accurate per build.
- **Localization:** the copy is a new string in the default `res/values/strings.xml` (English). Non-English locales fall back to English — acceptable for the family fork (consistent with existing About-body fallbacks); translation can be added later.
- **No consent gate, no first-run interstitial, no account-panel copy** (rejected during brainstorming in favor of the lowest-friction, conventional placement).

## 3. Wording (operator-confirmed)

> **Privacy & diagnostics**
> This box reports anonymous activity and diagnostics — how long you watch, your app version, and playback errors — so the service keeps running and issues get caught. It never records *what* you watch: no titles, searches, or content. Durations only.

## 4. In-scope cleanup

`AboutSettingsContent` currently has a **"Privacy Policy"** `SettingsActionRow` that opens *upstream Nuvio's* policy (`https://tapframe.github.io/NuvioStreaming/#privacy-policy`) — wrong target for the family build. **Hide it** for the KevBox build using the same comment-out pattern already applied to the hidden "Supporters & Contributors" row (hide, don't delete; keep the upstream code intact for merges).

## 5. Files

- Modify `app/src/main/java/com/nuvio/tv/ui/screens/settings/AboutScreen.kt` — add the gated notice `Text` block under the version line; comment out the upstream Privacy Policy row.
- Modify `app/src/main/res/values/strings.xml` — add `about_telemetry_notice` (and a short `about_telemetry_notice_title` if a heading string is cleaner than inline).

## 6. Non-goals

- No GDPR/consent mechanism (legitimate interest, durations only — engineering note, not legal advice; operator owns final legal wording).
- No per-locale translation in this pass (English fallback).
- No change to the telemetry data path — this is presentation only.

## 7. Verification

- `playstore` flavor: notice absent (compile `assemblePlaystoreDebug` or inspect — `FEATURE_TELEMETRY=false`).
- `full` flavor: notice visible in Settings → About under the version; upstream Privacy Policy row gone. Confirm on the emulator (Settings → About).
- `./gradlew :app:assembleFullDebug` clean.
