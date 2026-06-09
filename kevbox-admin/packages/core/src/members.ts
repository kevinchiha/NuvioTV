import type { Db, MemberSummary, MemberDetail } from "./types.js";
import { mapAddonRow } from "./types.js";

export async function listMembers(db: Db): Promise<MemberSummary[]> {
  // hasDebrid is a COSMETIC overview badge only (no operation gates on it). It is an exact-URL
  // anti-join against default_member_addons(): "has any addon whose url is NOT a current default".
  // Caveat: if a default URL is refreshed in default_member_addons() while a member still holds the
  // old one (the token-rotation case the runbook calls out), that member is transiently flagged
  // hasDebrid=true until resetToDefaults re-seeds them. Acceptable because it is display-only.
  const { rows } = await db.query(
    `select u.id as user_id, u.email, u.created_at,
            (select count(*) from public.member_addon m where m.user_id = u.id)::int as addon_count,
            exists (
              select 1 from public.member_addon m
              where m.user_id = u.id
                and m.url not in (select url from public.default_member_addons())
            ) as has_debrid
       from auth.users u
      order by u.email nulls last`,
  );
  return rows.map((r: any) => ({
    userId: r.user_id,
    email: r.email,
    createdAt: new Date(r.created_at).toISOString(),
    addonCount: r.addon_count,
    hasDebrid: r.has_debrid,
  }));
}

/** ref = email or userId (uuid). Returns null if no such member. */
export async function getMember(db: Db, ref: string): Promise<MemberDetail | null> {
  const { rows: u } = await db.query(
    `select id, email from auth.users where id::text = $1 or email = $1 limit 1`,
    [ref],
  );
  if (u.length === 0) return null;
  const userId = u[0].id as string;
  const { rows } = await db.query(
    `select id, user_id, url, enabled, sort_order, updated_at
       from public.member_addon where user_id = $1 order by sort_order, id`,
    [userId],
  );
  return { userId, email: u[0].email, addons: rows.map(mapAddonRow) };
}
