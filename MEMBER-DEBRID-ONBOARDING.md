# KevBox TV — Per-member debrid onboarding (runbook)

> **What this is:** the step-by-step for onboarding each family member end-to-end:
> **(1)** their own debrid stream sources (**Torrentio** + **AIOStreams**) with **their own keys**, and
> **(2)** their own **Trakt** account for personal watch-history, scrobbling, ratings, and Continue
> Watching. Each member uses *different* debrid creds **and** a *different* Trakt account — nothing is
> shared. Related: `MEMBER-CONFIG-PLAN.md` (the remote-control feature), `UPSTREAM-SYNC.md` (fork rules).
>
> **⚠️ Per member you must do BOTH:** ① add their debrid rows in Supabase (steps 1–5 below) **and**
> ② sign them into Trakt on their TV (its own section further down). Skipping Trakt = that member has
> **no** personal watch tracking — easy to forget, so it has its own checklist below.

## The model

Addons split into two buckets:

| Bucket | What | Where it lives | Per member? |
|---|---|---|---|
| **Universal** | Cinemeta, OpenSubtitles v3 Pro, OpenSubtitles v3, Netflix catalogs | Baked into the app (`DefaultContent.DEFAULT_ADDON_URLS` / `AddonPreferences.getDefaultAddons()`) **and** auto-seeded by the `default_member_addons()` SQL helper | No — identical for everyone |
| **Per-member debrid** | Torrentio (their Premiumize key) + AIOStreams (their own URL) | **`member_addon` rows only** — added at onboarding | **Yes — each member's own creds** |

Why debrid isn't baked: a single baked URL can't hold five members' different keys, and AIOStreams
encrypts its debrid key *inside* the URL (no swappable field), so the only safe home for per-member
credentials is the per-member `member_addon` table. Baking one would silently share one person's debrid
with everyone.

**Trakt is a third thing — not an addon, not a Supabase row.** It's an on-device OAuth sign-in (the
token lives on the TV in `TraktAuthDataStore`, *not* in `member_addon`). It has its own onboarding
section below; don't try to model it as an addon URL.

## Per-member onboarding — do this once per member

**1. Collect the member's debrid creds**
   - Their **Premiumize API key** (for Torrentio) — from their Premiumize account → My Account → API key.
   - Their **AIOStreams URL** (see step 2).

**2. Generate that member's AIOStreams URL** (this is the part that's *not* a key swap)
   - Open the AIOStreams configurator (e.g. `https://aiostreams.elfhosted.com/` — or your own self-hosted
     instance for more control), configure it with **that member's** debrid account/services, and **Install
     / copy the manifest URL**. It looks like
     `https://aiostreams.elfhosted.com/stremio/<their-uuid>/<their-encrypted-token>/manifest.json`.
   - The UUID + `eyJ…` token *are* the credential — keep the whole URL; you can't edit a key inside it.

**3. Find the member's `user_id`**
   - Supabase dashboard → Auth → Users → copy their UUID. (kevin's is
     `7b9fed27-8935-4b21-8137-120becb51d0a`.)

**4. Add their two debrid rows** (Supabase → SQL editor)
```sql
insert into public.member_addon (user_id, url, enabled, sort_order) values
  ('<member uuid>',
   'https://torrentio.strem.fun/qualityfilter=unknown,cam,4k,scr|limit=5|sizefilter=4GB|debridoptions=nodownloadlinks,nocatalog|premiumize=<THEIR_PREMIUMIZE_KEY>/manifest.json',
   true, 4),
  ('<member uuid>',
   '<THEIR_FULL_AIOSTREAMS_URL>',
   true, 5)
on conflict (user_id, url) do nothing;
```
   - Swap `<THEIR_PREMIUMIZE_KEY>` into the Torrentio URL; paste their full AIOStreams URL.
   - `sort_order` 4–5 places them after the 4 universal addons (0–3).

**5. Apply on the TV**
   - The member-config feature is live (`MemberConfigService`, shipped on `kevbox`), so the two rows
     apply automatically on that member's next sign-in / app-open — no restart, no on-device step. If
     they're already signed in, reopening the app pulls the new rows.
   - Manual fallback (only if needed): add the same two addons on-device via the app's Add-Addon screen.

## Per-member Trakt sign-in — do this once per member (on the TV)

> **Crucial, easy-to-forget step.** Without it, that member has **no** personal watch history, **no**
> scrobbling, **no** Continue Watching synced to their Trakt, and **no** Trakt lists/ratings. Do this for
> every member right after their debrid rows are in.

**Why this is on-device (not Supabase like debrid):** Trakt auth is an OAuth token stored locally on the
TV, not in `member_addon`. The app's Trakt API credentials are already baked into the build
(`TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET`), so the member needs **only a free Trakt account** — no
developer/API-app setup on their end.

**1. Make sure the member has their own Trakt account**
   - Free at <https://trakt.tv/auth/join>. **One account per member — never shared** (a shared account
     merges everyone's watch history, scrobbles, and ratings — the Trakt twin of sharing a debrid key).

**2. On that member's TV: Settings → Trakt → Connect**
   - The screen shows a short **code** plus a **QR code** that points to
     `https://trakt.tv/activate/<code>` (code pre-filled). A countdown shows when the code expires.

**3. Activate on a phone/laptop**
   - Scan the QR (or open <https://trakt.tv/activate>), **sign in to that member's own Trakt account**,
     and approve the code shown on the TV.

**4. Confirm it took**
   - The TV flips to **Connected** and shows that member's Trakt stats strip. Done — scrobbling, watch
     history, and Continue Watching now flow to their Trakt. No restart needed.

## Editing / rotating later
- **Change a member's Premiumize key:** edit that member's Torrentio row `url` (swap the `premiumize=…`
  value). - **New AIOStreams config:** replace that member's AIOStreams row `url` with the new full URL.
- One member's debrid URL works across **all of that member's devices** — no per-device step.
- **Switch a member's Trakt account:** on their TV, Settings → Trakt → **Disconnect** (confirm), then
  **Connect** again and approve with the other account.
- **Trakt is per-device, not synced:** unlike debrid (which follows the member to any device via
  Supabase), Trakt is signed in on the TV itself. A new or replaced TV needs the device-code sign-in
  (Trakt steps 2–3) again.

## Reset / remove
- **Reset a member to universal defaults:** `delete from member_addon where user_id='<uuid>';` then
  re-run `select … from public.default_member_addons()` (see `MEMBER-CONFIG-PLAN.md`). This **drops their
  debrid** — re-run the onboarding insert (step 4) to restore it.
- **Remove one source:** `delete from member_addon where user_id='<uuid>' and url like 'https://torrentio%';`
- **Disconnect Trakt:** on the TV, Settings → Trakt → **Disconnect** — revokes the token on that TV. The
  member's history stays safe on Trakt's servers; reconnect any time via the Trakt steps above.

## Gotchas
- **Never share keys.** Each member's Torrentio key and AIOStreams URL are their own. A shared AIOStreams
  URL = a shared debrid account (and its concurrent-stream limits).
- **Never share a Trakt account either.** One Trakt account per member — sharing merges everyone's watch
  history, scrobbles, and ratings into one timeline. Same rule as debrid keys.
- **The Trakt code expires.** The device code shown on the TV has a countdown; if it lapses before the
  member approves, tap **Connect** again for a fresh one. (Re-opening the screen reuses a still-valid code
  rather than burning Trakt's tightly rate-limited code endpoint.)
- **The URL is a bearer secret.** Anyone with a member's URL can use their debrid. It lives in the APK
  (universal addons) and the `member_addon` table (debrid) — fine for family, don't post publicly.
- **Key case is preserved** on-device (the canonical form only lowercases an internal dedup key, not the
  stored/fetched URL), so keys with uppercase letters work.
- **Torrentio `qualityfilter=unknown,cam,4k,scr` is an *exclude* list** — it filters **out** 4K (plus
  cam/screener/unknown), and `sizefilter=4GB` caps size. Adjust per taste.
- **Netflix catalog token:** the baked URL #4 has carried a pre-expired token before — verify it resolves
  and refresh it in `default_member_addons()` + the Kotlin mirrors if needed.
- **Keep the 3 universal mirrors in sync:** `DefaultContent.DEFAULT_ADDON_URLS` (full),
  `AddonPreferences.getDefaultAddons()` (main), and the SQL `default_member_addons()` helper.
