import type { Db, AddonRow } from "./types.js";
import { mapAddonRow } from "./types.js";

const RETURNING = "id, user_id, url, enabled, sort_order, updated_at";

/** A caller-input error. `statusCode` lets the web layer return 400 (not a generic 500). */
function validationError(message: string): Error {
  const e = new Error(message) as Error & { statusCode?: number };
  e.statusCode = 400;
  return e;
}

/**
 * Trim + validate an addon URL: must be a syntactically valid http(s) URL. A bad/typo'd URL is
 * otherwise written straight through and mirrored to the member's TVs with no feedback (spec §4.6
 * only required "non-empty"). This is the single chokepoint both the CLI and web go through.
 */
function normalizeAddonUrl(raw: string): string {
  const url = (raw ?? "").trim();
  if (!url) throw validationError("url is required");
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw validationError(`invalid url: ${raw}`);
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw validationError(`url must be http(s): ${raw}`);
  }
  return url;
}

export async function addAddon(
  db: Db,
  userId: string,
  opts: { url: string; enabled?: boolean; sortOrder?: number },
): Promise<AddonRow> {
  const url = normalizeAddonUrl(opts.url);
  const enabled = opts.enabled ?? true;
  const { rows } = await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, $3,
             coalesce($4, (select coalesce(max(sort_order)+1, 0) from public.member_addon where user_id = $1)))
     on conflict (user_id, url)
       do update set enabled = excluded.enabled, sort_order = excluded.sort_order, updated_at = now()
     returning ${RETURNING}`,
    [userId, url, enabled, opts.sortOrder ?? null],
  );
  return mapAddonRow(rows[0]);
}

export async function updateAddon(
  db: Db,
  addonId: number,
  fields: { url?: string; enabled?: boolean },
): Promise<AddonRow | null> {
  const url = fields.url === undefined ? null : normalizeAddonUrl(fields.url);
  const { rows } = await db.query(
    `update public.member_addon
        set url = coalesce($2, url), enabled = coalesce($3, enabled), updated_at = now()
      where id = $1 returning ${RETURNING}`,
    [addonId, url, fields.enabled ?? null],
  );
  return rows[0] ? mapAddonRow(rows[0]) : null;
}

export async function setEnabled(db: Db, addonId: number, enabled: boolean): Promise<AddonRow | null> {
  const { rows } = await db.query(
    `update public.member_addon set enabled = $2, updated_at = now() where id = $1 returning ${RETURNING}`,
    [addonId, enabled],
  );
  return rows[0] ? mapAddonRow(rows[0]) : null;
}

/** Rewrite sort_order so the member's addons follow `orderedIds`. Scoped to userId for safety. */
export async function reorder(db: Db, userId: string, orderedIds: number[]): Promise<void> {
  await db.query(
    `update public.member_addon m
        set sort_order = x.ord - 1, updated_at = now()
       from unnest($2::bigint[]) with ordinality as x(id, ord)
      where m.id = x.id and m.user_id = $1`,
    [userId, orderedIds],
  );
}

export async function deleteAddon(db: Db, addonId: number): Promise<void> {
  await db.query("delete from public.member_addon where id = $1", [addonId]);
}
