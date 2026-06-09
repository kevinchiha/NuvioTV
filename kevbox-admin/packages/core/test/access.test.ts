import { expect, test } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import {
  getAccess,
  setActive,
  setMaxDevices,
  removeDevice,
  removeAllDevices,
} from "../src/access.js";

/** Insert a member_device row directly (the addons.test idiom — no RPC; access core hits tables). */
async function seedDevice(
  db: import("../src/types.js").Db,
  userId: string,
  deviceId: string,
  deviceName: string | null,
): Promise<void> {
  await db.query(
    "insert into public.member_device (user_id, device_id, device_name) values ($1, $2, $3)",
    [userId, deviceId, deviceName],
  );
}

test("getAccess returns fail-open defaults when no rows exist", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "a@test.dev");
    const state = await getAccess(db, id);
    expect(state.userId).toBe(id);
    expect(state.active).toBe(true); // no member_access row => active=true
    expect(state.maxDevices).toBe(1); // no policy row => 1
    expect(state.devices).toEqual([]);
  });
});

test("getAccess reflects the row-FOUND path (active=false + max_devices=2)", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "b@test.dev");
    await db.query(
      "insert into public.member_access (user_id, active) values ($1, false)",
      [id],
    );
    await db.query(
      "insert into public.member_device_policy (user_id, max_devices) values ($1, 2)",
      [id],
    );
    const state = await getAccess(db, id);
    expect(state.active).toBe(false);
    expect(state.maxDevices).toBe(2);
  });
});

test("getAccess returns devices newest-first by last_seen with mapped fields", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "c@test.dev");
    await db.query(
      `insert into public.member_device (user_id, device_id, device_name, last_seen)
       values ($1, 'dev-old', 'Old TV', now() - interval '1 day'),
              ($1, 'dev-new', 'New TV', now())`,
      [id],
    );
    const state = await getAccess(db, id);
    expect(state.devices.map((d) => d.deviceId)).toEqual(["dev-new", "dev-old"]);
    expect(state.devices[0].deviceName).toBe("New TV");
    expect(typeof state.devices[0].firstSeen).toBe("string");
    expect(typeof state.devices[0].lastSeen).toBe("string");
  });
});

test("setActive inserts when no row exists then updates an existing row", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "d@test.dev");
    // insert branch: no prior member_access row
    const disabled = await setActive(db, id, false);
    expect(disabled.active).toBe(false);
    // update branch: a row now exists
    const enabled = await setActive(db, id, true);
    expect(enabled.active).toBe(true);
  });
});

test("setActive double-disable is idempotent (stays active=false, no error)", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "e@test.dev");
    await setActive(db, id, false);
    const again = await setActive(db, id, false);
    expect(again.active).toBe(false);
    const { rows } = await db.query(
      "select count(*)::int as n from public.member_access where user_id = $1",
      [id],
    );
    expect(rows[0].n).toBe(1); // upsert, not a second row
  });
});

test("setMaxDevices rejects values < 1", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "f@test.dev");
    await expect(setMaxDevices(db, id, 0)).rejects.toThrow("maxDevices must be an integer >= 1");
    await expect(setMaxDevices(db, id, -1)).rejects.toThrow("maxDevices must be an integer >= 1");
    await expect(setMaxDevices(db, id, 1.5)).rejects.toThrow("maxDevices must be an integer >= 1");
  });
});

test("setMaxDevices lowers the cap below seated count WITHOUT evicting devices", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "g@test.dev");
    await seedDevice(db, id, "dev-1", "TV A");
    await seedDevice(db, id, "dev-2", "TV B");
    const state = await setMaxDevices(db, id, 1);
    expect(state.maxDevices).toBe(1);
    // both rows survive — lowering the cap does NOT evict
    expect(state.devices).toHaveLength(2);
    const { rows } = await db.query(
      "select count(*)::int as n from public.member_device where user_id = $1",
      [id],
    );
    expect(rows[0].n).toBe(2);
  });
});

test("removeDevice deletes the scoped row and returns full access on success path", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "h@test.dev");
    await seedDevice(db, id, "dev-1", "TV A");
    await seedDevice(db, id, "dev-2", "TV B");
    await removeDevice(db, id, "dev-1");
    const state = await getAccess(db, id);
    expect(state.devices.map((d) => d.deviceId)).toEqual(["dev-2"]);
  });
});

test("removeDevice throws notFound (404) for a missing device", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "i@test.dev");
    const err = await removeDevice(db, id, "nope").catch((e) => e as Error & { statusCode?: number });
    expect(err).toBeInstanceOf(Error);
    expect((err as Error & { statusCode?: number }).statusCode).toBe(404);
  });
});

test("removeDevice is scoped per-member (cross-member delete leaves the other member intact)", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "j-a@test.dev");
    const b = await createTestMember(db, "j-b@test.dev");
    await seedDevice(db, b, "dev-b", "B's TV");
    // memberA tries to remove memberB's device id — must 404 for A and NOT touch B's row
    const err = await removeDevice(db, a, "dev-b").catch((e) => e as Error & { statusCode?: number });
    expect((err as Error & { statusCode?: number }).statusCode).toBe(404);
    const stateB = await getAccess(db, b);
    expect(stateB.devices.map((d) => d.deviceId)).toEqual(["dev-b"]);
  });
});

test("removeAllDevices clears only the target member's rows", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "k-a@test.dev");
    const b = await createTestMember(db, "k-b@test.dev");
    await seedDevice(db, a, "a-1", "TV A1");
    await seedDevice(db, a, "a-2", "TV A2");
    await seedDevice(db, b, "b-1", "TV B1");
    await removeAllDevices(db, a);
    expect((await getAccess(db, a)).devices).toEqual([]);
    expect((await getAccess(db, b)).devices.map((d) => d.deviceId)).toEqual(["b-1"]);
  });
});
