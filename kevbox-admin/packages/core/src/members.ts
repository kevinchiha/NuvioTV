import type { Db, MemberSummary, MemberDetail } from "./types.js";
import { mapAddonRow } from "./types.js";

export async function listMembers(db: Db): Promise<MemberSummary[]> {
  // `enrolled` is a display badge: true iff the member has an enrolled kevbox_member row.
  const { rows } = await db.query(
    `select u.id as user_id, u.email, u.created_at,
            (select count(*) from public.member_addon m where m.user_id = u.id)::int as addon_count,
            exists (
              select 1 from public.kevbox_member k
              where k.user_id = u.id and k.enrolled
            ) as enrolled
       from public.kevbox_auth_users u
      order by u.email nulls last`,
  );
  return rows.map((r: any) => ({
    userId: r.user_id,
    email: r.email,
    createdAt: new Date(r.created_at).toISOString(),
    addonCount: r.addon_count,
    enrolled: r.enrolled,
  }));
}

/** ref = email or userId (uuid). Returns null if no such member. */
export async function getMember(db: Db, ref: string): Promise<MemberDetail | null> {
  const { rows: u } = await db.query(
    `select id, email from public.kevbox_auth_users where id::text = $1 or email = $1 limit 1`,
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
