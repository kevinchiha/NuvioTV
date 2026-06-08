# KevBox TV — Per-member debrid onboarding (runbook)

> **What this is:** the step-by-step for giving each family member their own debrid stream sources
> (**Torrentio** + **AIOStreams**) with **their own keys**. Each member uses a *different* debrid
> account/key — keys are never shared. Related: `MEMBER-CONFIG-PLAN.md` (the remote-control feature),
> `UPSTREAM-SYNC.md` (fork rules).

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
   - Once the member-config feature is built, it applies on the member's next sign-in / app-open — no
     restart. Until then, add the same two addons on-device via the app's Add-Addon screen.

## Editing / rotating later
- **Change a member's Premiumize key:** edit that member's Torrentio row `url` (swap the `premiumize=…`
  value). - **New AIOStreams config:** replace that member's AIOStreams row `url` with the new full URL.
- One member's URL works across **all of that member's devices** — no per-device step.

## Reset / remove
- **Reset a member to universal defaults:** `delete from member_addon where user_id='<uuid>';` then
  re-run `select … from public.default_member_addons()` (see `MEMBER-CONFIG-PLAN.md`). This **drops their
  debrid** — re-run the onboarding insert (step 4) to restore it.
- **Remove one source:** `delete from member_addon where user_id='<uuid>' and url like 'https://torrentio%';`

## Gotchas
- **Never share keys.** Each member's Torrentio key and AIOStreams URL are their own. A shared AIOStreams
  URL = a shared debrid account (and its concurrent-stream limits).
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
