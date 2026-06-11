import type { Db } from "./types.js";

const SHARING_SUSPECT_SECONDS = 18 * 3600; // 64800

export interface MemberActivity {
  userId: string;
  watchSecondsToday: number;
  watchSeconds7d: number;
  watchSeconds30d: number;
  sessions7d: number;
  lastAppVersion: string | null;
  lastHeartbeatAt: string | null;
  errors7d: number;
  sharingSuspect: boolean;
}

export async function getMemberActivity(db: Db, userId: string): Promise<MemberActivity | null> {
  const { rows } = await db.query(
    `select
        coalesce(sum(watch_seconds) filter (where day = (now() at time zone 'utc')::date),0)::int as today,
        coalesce(sum(watch_seconds) filter (where day > (now() at time zone 'utc')::date - 7),0)::int as d7,
        coalesce(sum(watch_seconds) filter (where day > (now() at time zone 'utc')::date - 30),0)::int as d30,
        coalesce(sum(sessions) filter (where day > (now() at time zone 'utc')::date - 7),0)::int as sessions7d,
        coalesce(max(watch_seconds) filter (where day > (now() at time zone 'utc')::date - 30),0)::int as maxday30,
        count(*)::int as daily_rows,
        -- most-recent day's version, not text max() (so 1.9.0 → 1.10.0 reports 1.10.0, not 1.9.0).
        (array_agg(last_app_version order by day desc) filter (where last_app_version is not null))[1] as last_app_version
     from public.member_activity_daily where user_id = $1`,
    [userId],
  );
  const r = rows[0];
  const hb = await db.query(
    "select max(last_heartbeat) as last_heartbeat from public.member_heartbeat where user_id=$1", [userId]);
  const errs = await db.query(
    "select count(*)::int as n from public.member_event where user_id=$1 and kind='playback_error' and occurred_at > now() - interval '7 days'", [userId]);
  // No data at all (no daily rows AND no heartbeat) → null, matching getMember's null contract.
  // A session_start-only day (watch 0 but a row exists) returns a real all-zero object, not null.
  if (Number(r.daily_rows) === 0 && hb.rows[0].last_heartbeat == null) return null;
  return {
    userId,
    watchSecondsToday: r.today, watchSeconds7d: r.d7, watchSeconds30d: r.d30,
    sessions7d: r.sessions7d, lastAppVersion: r.last_app_version,
    lastHeartbeatAt: hb.rows[0].last_heartbeat ? new Date(hb.rows[0].last_heartbeat).toISOString() : null,
    errors7d: errs.rows[0].n,
    sharingSuspect: Number(r.maxday30) >= SHARING_SUSPECT_SECONDS,
  };
}

export interface ActivityRankRow { userId: string; email: string | null; watchSeconds: number; }

export async function listMembersByActivity(
  db: Db, opts: { window: "today" | "7d" | "30d"; order: "most" | "least"; limit: number },
): Promise<ActivityRankRow[]> {
  const days = opts.window === "today" ? 1 : opts.window === "7d" ? 7 : 30;
  const dir = opts.order === "most" ? "desc" : "asc";
  const { rows } = await db.query(
    `select d.user_id, u.email, coalesce(sum(d.watch_seconds),0)::int as watch_seconds
       from public.member_activity_daily d
       left join public.kevbox_auth_users u on u.id = d.user_id
      where d.day > (now() at time zone 'utc')::date - $1::int
      group by d.user_id, u.email
      order by watch_seconds ${dir}
      limit $2`,
    [days, opts.limit],
  );
  return rows.map((r: any) => ({ userId: r.user_id, email: r.email, watchSeconds: r.watch_seconds }));
}

export interface GoingDarkRow { userId: string; email: string | null; lastHeartbeatAt: string | null; }

export async function listGoingDark(db: Db, opts: { days: number }): Promise<GoingDarkRow[]> {
  const { rows } = await db.query(
    `select a.user_id, u.email, hb.last_heartbeat
       from public.member_access a
       left join public.kevbox_auth_users u on u.id = a.user_id
       left join (select user_id, max(last_heartbeat) as last_heartbeat from public.member_heartbeat group by user_id) hb on hb.user_id = a.user_id
      where a.active = true
        and coalesce((select sum(watch_seconds) from public.member_activity_daily d
                       where d.user_id = a.user_id and d.day > (now() at time zone 'utc')::date - $1::int), 0) = 0
      order by hb.last_heartbeat asc nulls first`,
    [opts.days],
  );
  return rows.map((r: any) => ({
    userId: r.user_id, email: r.email,
    lastHeartbeatAt: r.last_heartbeat ? new Date(r.last_heartbeat).toISOString() : null,
  }));
}

export interface FleetStats {
  dau: number; wau: number; mau: number; totalWatchHours: number;
  goingDark: number; errors7d: number; appVersions: { version: string; count: number }[];
}

export async function getFleetStats(db: Db): Promise<FleetStats> {
  const win = async (days: number) => Number((await db.query(
    `select count(distinct user_id)::int as n from public.member_activity_daily
      where day > (now() at time zone 'utc')::date - $1::int and watch_seconds > 0`, [days])).rows[0].n);
  const totalSec = Number((await db.query(
    "select coalesce(sum(watch_seconds),0)::bigint as s from public.member_activity_daily where day > (now() at time zone 'utc')::date - 30")).rows[0].s);
  const errors7d = Number((await db.query(
    "select count(*)::int as n from public.member_event where kind='playback_error' and occurred_at > now() - interval '7 days'")).rows[0].n);
  const versions = (await db.query(
    "select coalesce(app_version,'unknown') as version, count(*)::int as count from public.member_heartbeat group by 1 order by 2 desc")).rows
    .map((r: any) => ({ version: r.version, count: r.count }));
  const goingDark = (await listGoingDark(db, { days: 14 })).length;
  return {
    dau: await win(1), wau: await win(7), mau: await win(30),
    totalWatchHours: Math.round(totalSec / 3600), goingDark, errors7d, appVersions: versions,
  };
}
