import type { Db } from "./types.js";
import { buildTorrentioUrl, DEBRID_TORRENTIO_SORT, DEBRID_AIOSTREAMS_SORT } from "./defaults.js";

/** Add a member's two debrid sources from their OWN keys. Idempotent (on conflict do nothing). */
export async function onboardDebrid(
  db: Db,
  userId: string,
  opts: { premiumizeKey: string; aiostreamsUrl: string },
): Promise<void> {
  const aiostreams = opts.aiostreamsUrl.trim();
  if (!aiostreams) throw new Error("aiostreamsUrl is required");
  const torrentio = buildTorrentioUrl(opts.premiumizeKey);
  await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, true, $4), ($1, $3, true, $5)
     on conflict (user_id, url) do nothing`,
    [userId, torrentio, aiostreams, DEBRID_TORRENTIO_SORT, DEBRID_AIOSTREAMS_SORT],
  );
}
