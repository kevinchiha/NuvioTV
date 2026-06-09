import type { Db } from "./types.js";

/**
 * Reset a member to the baked-in universal defaults. Reuses the DB's default_member_addons()
 * function (the single source of truth) instead of hardcoding URLs here.
 *
 * ATOMIC + crash-safe: this is ONE statement, so it is safe even when `db` is a pg.Pool (where
 * separate .query() calls run on different connections in autocommit). The CTE upserts the
 * universal defaults and the main DELETE removes only the member's NON-default rows. The two arms
 * touch disjoint row sets (default-url rows vs. everything else), so — unlike a delete-then-insert —
 * a failure can only leave an *incomplete* reset (safely re-runnable), NEVER a member with zero
 * addons. The `on conflict` upsert also makes re-running idempotent (no unique(user_id,url) violation
 * when the member already holds the defaults).
 */
export async function resetToDefaults(db: Db, userId: string): Promise<void> {
  await db.query(
    `with up as (
       insert into public.member_addon (user_id, url, enabled, sort_order)
       select $1, url, true, sort_order from public.default_member_addons()
       on conflict (user_id, url)
         do update set enabled = true, sort_order = excluded.sort_order, updated_at = now()
     )
     delete from public.member_addon
      where user_id = $1
        and url not in (select url from public.default_member_addons())`,
    [userId],
  );
}
