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
    // The empty-set floor is SOFT (matches the un-enroll path, plan §11/M11): if every input
    // resolves to an already-un-enrolled member (and there are no extras), the would-be members.json
    // is empty and renderMembersFile refuses — that is correct, leave the prior file intact. The
    // insert-only DB mutations above stay committed. Any OTHER render error still propagates.
    try {
      await renderMembersFile(db, cfg.membersFile);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (!/empty/i.test(msg)) throw e;
    }
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
