import type { Db } from "./types.js";

export interface AddonSnapshotRow {
  userId: string;
  email: string | null;
  url: string;
  enabled: boolean;
  sortOrder: number;
}

/**
 * Capture EVERY member_addon row (with member email) as a plain array — the pre-image to persist
 * BEFORE a destructive bulk op so a wrong swap/add is recoverable (spec §4.6 snapshot-before-bulk,
 * v1). Pure read; the caller owns where the JSON lands (CLI: a local file; web: the response).
 */
export async function snapshotAllAddons(db: Db): Promise<AddonSnapshotRow[]> {
  const { rows } = await db.query(
    `select m.user_id, u.email, m.url, m.enabled, m.sort_order
       from public.member_addon m join auth.users u on u.id = m.user_id
      order by u.email nulls last, m.sort_order, m.id`,
  );
  return rows.map((r: any) => ({
    userId: r.user_id,
    email: r.email,
    url: r.url,
    enabled: r.enabled,
    sortOrder: r.sort_order,
  }));
}

/** Add `url` to EVERY member. Returns rows inserted. Requires confirm=true (no client-side undo). */
export async function bulkAddAddon(
  db: Db,
  opts: { url: string; sortOrder: number },
  confirm: boolean,
): Promise<number> {
  if (confirm !== true) throw new Error("bulkAddAddon requires confirm=true");
  const { rowCount } = await db.query(
    `insert into public.member_addon (user_id, url, sort_order)
     select id, $1, $2 from auth.users
     on conflict (user_id, url) do nothing`,
    [opts.url.trim(), opts.sortOrder],
  );
  return rowCount ?? 0;
}

/** Swap `fromUrl` -> `toUrl` across ALL members, safely handling members who already have toUrl. */
export async function bulkSwapUrl(
  db: Db,
  opts: { fromUrl: string; toUrl: string },
  confirm: boolean,
): Promise<void> {
  if (confirm !== true) throw new Error("bulkSwapUrl requires confirm=true");
  const from = opts.fromUrl.trim();
  const to = opts.toUrl.trim();
  // 1. swap where the member does NOT already have the target (avoids unique violation)
  await db.query(
    `update public.member_addon m set url = $2, updated_at = now()
      where m.url = $1
        and not exists (select 1 from public.member_addon m2 where m2.user_id = m.user_id and m2.url = $2)`,
    [from, to],
  );
  // 2. drop now-redundant originals for members who already had the target
  await db.query("delete from public.member_addon where url = $1", [from]);
}
