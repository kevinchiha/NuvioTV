import { describe, test, expect } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { getMemberActivity, listMembersByActivity, listGoingDark, getFleetStats } from "../src/activity.js";

async function seedDay(db: any, uid: string, daysAgo: number, seconds: number) {
  await db.query(
    "insert into public.member_activity_daily(user_id, day, watch_seconds, heartbeats, sessions) values ($1, (now() at time zone 'utc')::date - $2::int, $3, 1, 1) on conflict (user_id, day) do update set watch_seconds=excluded.watch_seconds",
    [uid, daysAgo, seconds],
  );
}

describe("getMemberActivity", () => {
  test("rolls up today / 7d / 30d watch seconds", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "a@test.dev");
      await seedDay(db, uid, 0, 100);
      await seedDay(db, uid, 3, 200);
      await seedDay(db, uid, 20, 400);
      const a = await getMemberActivity(db, uid);
      expect(a!.watchSecondsToday).toBe(100);
      expect(a!.watchSeconds7d).toBe(300);   // today + 3d
      expect(a!.watchSeconds30d).toBe(700);  // + 20d
    });
  });

  test("flags sharing-suspect when a day exceeds 18h", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "b@test.dev");
      await seedDay(db, uid, 1, 19 * 3600);
      const a = await getMemberActivity(db, uid);
      expect(a!.sharingSuspect).toBe(true);
    });
  });

  test("strict 7d/30d window boundaries exclude exactly day-7 and day-30", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "win@test.dev");
      await seedDay(db, uid, 6, 10);    // inside 7d (and 30d)
      await seedDay(db, uid, 7, 100);   // excluded by `> date - 7`; still inside 30d
      await seedDay(db, uid, 29, 20);   // inside 30d
      await seedDay(db, uid, 30, 200);  // excluded by `> date - 30`
      const a = await getMemberActivity(db, uid);
      expect(a!.watchSeconds7d).toBe(10);     // only day-6
      expect(a!.watchSeconds30d).toBe(130);   // day-6 + day-7 + day-29; day-30 excluded
    });
  });

  test("sharing-suspect boundary: 64800 true, 64799 false, 17h false (no false-positive on heavy use)", async () => {
    await withRollback(async (db) => {
      const over = await createTestMember(db, "over@test.dev");
      await seedDay(db, over, 1, 64800);
      expect((await getMemberActivity(db, over))!.sharingSuspect).toBe(true);
      const under = await createTestMember(db, "under@test.dev");
      await seedDay(db, under, 1, 64799);
      expect((await getMemberActivity(db, under))!.sharingSuspect).toBe(false);
      const heavy = await createTestMember(db, "heavy@test.dev");
      await seedDay(db, heavy, 1, 17 * 3600);
      expect((await getMemberActivity(db, heavy))!.sharingSuspect).toBe(false);
    });
  });

  test("null only when truly no data; a session_start-only day returns a real all-zero object", async () => {
    await withRollback(async (db) => {
      const empty = await createTestMember(db, "empty@test.dev");
      expect(await getMemberActivity(db, empty)).toBeNull();

      const sOnly = await createTestMember(db, "sonly@test.dev");
      await db.query(
        "insert into public.member_activity_daily(user_id, day, watch_seconds, heartbeats, sessions) values ($1, (now() at time zone 'utc')::date, 0, 0, 1)", [sOnly]);
      await db.query(
        "insert into public.member_heartbeat(user_id, device_id, last_heartbeat) values ($1,'dev-1', now())", [sOnly]);
      const a = await getMemberActivity(db, sOnly);
      expect(a).not.toBeNull();
      expect(a!.watchSecondsToday).toBe(0);
      expect(a!.sessions7d).toBeGreaterThanOrEqual(1);
    });
  });

  test("lastAppVersion reflects the most recent day, not text max (1.9.0 → 1.10.0)", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "ver@test.dev");
      await db.query(
        "insert into public.member_activity_daily(user_id, day, watch_seconds, last_app_version) values ($1, (now() at time zone 'utc')::date - 3, 10, '1.9.0')", [uid]);
      await db.query(
        "insert into public.member_activity_daily(user_id, day, watch_seconds, last_app_version) values ($1, (now() at time zone 'utc')::date, 10, '1.10.0')", [uid]);
      expect((await getMemberActivity(db, uid))!.lastAppVersion).toBe("1.10.0"); // text max() would wrongly pick 1.9.0
    });
  });
});

describe("listMembersByActivity", () => {
  test("orders most-active first over the window", async () => {
    await withRollback(async (db) => {
      const low = await createTestMember(db, "low@test.dev");
      const high = await createTestMember(db, "high@test.dev");
      await seedDay(db, low, 1, 100);
      await seedDay(db, high, 1, 5000);
      const rows = await listMembersByActivity(db, { window: "7d", order: "most", limit: 10 });
      expect(rows[0].userId).toBe(high);
      expect(rows[0].watchSeconds).toBe(5000);
    });
  });
});

describe("listGoingDark", () => {
  test("returns members with access but ~0 recent watch time", async () => {
    await withRollback(async (db) => {
      const dark = await createTestMember(db, "dark@test.dev");
      const live = await createTestMember(db, "live@test.dev");
      await db.query("insert into public.member_access(user_id, active) values ($1,true),($2,true)", [dark, live]);
      // both phoned home at some point (real churned members have an old heartbeat)
      await db.query("insert into public.member_heartbeat(user_id, device_id, last_heartbeat) values ($1,'d',now() - interval '40 days'),($2,'d',now())", [dark, live]);
      await seedDay(db, dark, 40, 5000); // last watched 40 days ago
      await seedDay(db, live, 1, 5000);  // watched yesterday
      const rows = await listGoingDark(db, { days: 14 });
      const ids = rows.map((r) => r.userId);
      expect(ids).toContain(dark);
      expect(ids).not.toContain(live);
    });
  });

  test("a tiny non-zero in-window watch keeps a member OFF the list", async () => {
    await withRollback(async (db) => {
      const blip = await createTestMember(db, "blip@test.dev");
      await db.query("insert into public.member_access(user_id, active) values ($1,true)", [blip]);
      await seedDay(db, blip, 2, 1); // 1 second within the 14d window
      const rows = await listGoingDark(db, { days: 14 });
      expect(rows.map((r) => r.userId)).not.toContain(blip);
    });
  });

  test("the 14d edge: watch exactly at day-14 is out-of-window → member IS dark", async () => {
    await withRollback(async (db) => {
      const edge = await createTestMember(db, "edge@test.dev");
      await db.query("insert into public.member_access(user_id, active) values ($1,true)", [edge]);
      await db.query("insert into public.member_heartbeat(user_id, device_id, last_heartbeat) values ($1,'d',now() - interval '14 days')", [edge]);
      await seedDay(db, edge, 14, 5000); // day-14 excluded by `> date - 14`
      const rows = await listGoingDark(db, { days: 14 });
      expect(rows.map((r) => r.userId)).toContain(edge);
    });
  });

  test("inactive access (active=false) is never listed even with zero watch", async () => {
    await withRollback(async (db) => {
      const off = await createTestMember(db, "off@test.dev");
      await db.query("insert into public.member_access(user_id, active) values ($1,false)", [off]);
      const rows = await listGoingDark(db, { days: 14 });
      expect(rows.map((r) => r.userId)).not.toContain(off);
    });
  });

  test("a member that never recorded a heartbeat is excluded (never onboarded, not churned)", async () => {
    await withRollback(async (db) => {
      const neverSeen = await createTestMember(db, "neverseen@test.dev");
      await db.query("insert into public.member_access(user_id, active) values ($1,true)", [neverSeen]);
      // access granted, zero watch, and NO member_heartbeat row → never phoned home
      const rows = await listGoingDark(db, { days: 14 });
      expect(rows.map((r) => r.userId)).not.toContain(neverSeen);
    });
  });
});

describe("getFleetStats", () => {
  test("counts active members and total watch hours", async () => {
    await withRollback(async (db) => {
      const u = await createTestMember(db, "u@test.dev");
      await seedDay(db, u, 0, 3600);
      const s = await getFleetStats(db);
      expect(s.dau).toBeGreaterThanOrEqual(1);
      expect(s.totalWatchHours).toBeGreaterThanOrEqual(1);
    });
  });
});
