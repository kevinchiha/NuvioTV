import { describe, test, expect } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";

// Inner accrual fn is called directly with an explicit user id (no JWT needed in tests).
describe("accrue_heartbeat", () => {
  test("first heartbeat of a session accrues 0 and seeds baseline", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "a@test.dev");
      const { rows } = await db.query<{ accrue_heartbeat: number }>(
        "select public.accrue_heartbeat($1, $2, $3, 120) as accrue_heartbeat",
        [uid, "dev-1", "1.0.0"],
      );
      expect(rows[0].accrue_heartbeat).toBe(0);
      const daily = await db.query(
        "select watch_seconds, heartbeats from public.member_activity_daily where user_id=$1",
        [uid],
      );
      expect(daily.rows[0].watch_seconds).toBe(0);
      expect(daily.rows[0].heartbeats).toBe(1);
    });
  });

  test("accrues elapsed seconds since last heartbeat", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "b@test.dev");
      // Seed a baseline 30s in the past (now() is fixed within the txn).
      await db.query(
        "insert into public.member_heartbeat(user_id, device_id, last_heartbeat, app_version) values ($1,$2, now() - interval '30 seconds', $3)",
        [uid, "dev-1", "1.0.0"],
      );
      const { rows } = await db.query<{ accrue_heartbeat: number }>(
        "select public.accrue_heartbeat($1,$2,$3,120) as accrue_heartbeat",
        [uid, "dev-1", "1.0.0"],
      );
      expect(rows[0].accrue_heartbeat).toBe(30);
    });
  });

  test("caps accrual at p_cap_seconds (pauses/gaps cannot inflate)", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "c@test.dev");
      await db.query(
        "insert into public.member_heartbeat(user_id, device_id, last_heartbeat) values ($1,$2, now() - interval '10 minutes')",
        [uid, "dev-1"],
      );
      const { rows } = await db.query<{ accrue_heartbeat: number }>(
        "select public.accrue_heartbeat($1,$2,$3,120) as accrue_heartbeat",
        [uid, "dev-1", "1.0.0"],
      );
      expect(rows[0].accrue_heartbeat).toBe(120); // capped, not 600
    });
  });

  test("repeated accruals sum into the same day row", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "d@test.dev");
      await db.query(
        "insert into public.member_heartbeat(user_id, device_id, last_heartbeat) values ($1,$2, now() - interval '60 seconds')",
        [uid, "dev-1"],
      );
      await db.query("select public.accrue_heartbeat($1,$2,$3,120)", [uid, "dev-1", "1.0.0"]);
      // Move baseline back again and accrue once more.
      await db.query(
        "update public.member_heartbeat set last_heartbeat = now() - interval '60 seconds' where user_id=$1",
        [uid],
      );
      await db.query("select public.accrue_heartbeat($1,$2,$3,120)", [uid, "dev-1", "1.0.0"]);
      const daily = await db.query(
        "select watch_seconds, heartbeats from public.member_activity_daily where user_id=$1",
        [uid],
      );
      expect(daily.rows[0].watch_seconds).toBe(120);
      expect(daily.rows[0].heartbeats).toBe(2);
    });
  });

  test("two devices on one account each track their own baseline and sum into the day", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "multi@test.dev");
      for (const dev of ["dev-1", "dev-2"]) {
        await db.query(
          "insert into public.member_heartbeat(user_id, device_id, last_heartbeat) values ($1,$2, now() - interval '60 seconds')",
          [uid, dev],
        );
        await db.query("select public.accrue_heartbeat($1,$2,$3,120)", [uid, dev, "1.0.0"]);
      }
      const daily = await db.query(
        "select watch_seconds, heartbeats from public.member_activity_daily where user_id=$1", [uid]);
      expect(daily.rows[0].watch_seconds).toBe(120);  // 60 + 60, summed across devices (see Deviation 3 caveat)
      expect(daily.rows[0].heartbeats).toBe(2);
      const hb = await db.query(
        "select count(*)::int as n from public.member_heartbeat where user_id=$1", [uid]);
      expect(hb.rows[0].n).toBe(2);                    // one independent baseline row per device
    });
  });
});

describe("record_session_start", () => {
  test("increments sessions, logs an event, resets baseline", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "s@test.dev");
      await db.query("select public.record_session_start($1,$2,$3)", [uid, "dev-1", "1.0.0"]);
      const daily = await db.query(
        "select sessions from public.member_activity_daily where user_id=$1", [uid]);
      expect(daily.rows[0].sessions).toBe(1);
      const ev = await db.query(
        "select kind from public.member_event where user_id=$1", [uid]);
      expect(ev.rows.map((r: any) => r.kind)).toContain("session_start");
      const hb = await db.query(
        "select count(*)::int as n from public.member_heartbeat where user_id=$1", [uid]);
      expect(hb.rows[0].n).toBe(1);
    });
  });

  test("session_start then a playback accrual compose on the same day row (sessions preserved)", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "sc@test.dev");
      await db.query("select public.record_session_start($1,$2,$3)", [uid, "dev-1", "1.0.0"]);
      await db.query("update public.member_heartbeat set last_heartbeat = now() - interval '60 seconds' where user_id=$1", [uid]);
      await db.query("select public.accrue_heartbeat($1,$2,$3,120)", [uid, "dev-1", "1.0.0"]);
      const daily = await db.query("select sessions, watch_seconds, heartbeats from public.member_activity_daily where user_id=$1", [uid]);
      expect(daily.rows[0].sessions).toBe(1);       // preserved across the second upsert
      expect(daily.rows[0].watch_seconds).toBe(60); // accrued
      expect(daily.rows[0].heartbeats).toBe(1);
    });
  });
});

describe("record_error_event", () => {
  test("stores only allowlisted keys (no arbitrary payload)", async () => {
    await withRollback(async (db) => {
      const uid = await createTestMember(db, "e@test.dev");
      await db.query("select public.record_error_event($1,$2,$3,$4::jsonb)", [
        uid, "dev-1", "1.0.0",
        JSON.stringify({ code: "2001", message: "boom http://evil/x", secret: "debrid-token-xyz", url: "http://x" }),
      ]);
      const ev = await db.query(
        "select kind, detail from public.member_event where user_id=$1 and kind='playback_error'", [uid]);
      const detail = ev.rows[0].detail;
      expect(detail.code).toBe("2001");
      expect(detail.message_short).toBe("boom [url]"); // URL-ish tokens stripped server-side (spec §7/§11)
      expect(detail.message).toBeUndefined();          // renamed to message_short
      expect(detail.secret).toBeUndefined();           // dropped at the DB boundary
      expect(detail.url).toBeUndefined();
    });
  });
});
