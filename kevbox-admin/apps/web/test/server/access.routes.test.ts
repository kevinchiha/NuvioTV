import { afterAll, expect, test } from "vitest";
import { pool, withRollback, createTestMember } from "../../../../packages/core/test/helpers.js";
import { buildTestApp, tokenIsEmailVerifier } from "./helpers.js";

const ADMIN = { authorization: "Bearer admin@test.dev" };

afterAll(async () => { await pool.end(); });

test("GET access → defaults (Enabled, max 1, no devices)", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "access-default@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);

    const res = await app.inject({ method: "GET", url: `/api/members/${id}/access`, headers: ADMIN });
    expect(res.statusCode).toBe(200);
    const access = res.json().access;
    expect(access.userId).toBe(id);
    expect(access.active).toBe(true);
    expect(access.maxDevices).toBe(1);
    expect(access.devices).toEqual([]);

    await app.close();
  });
});

test("PUT active false → GET reflects active=false", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "access-disable@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);

    const put = await app.inject({
      method: "PUT",
      url: `/api/members/${id}/access/active`,
      headers: ADMIN,
      payload: { active: false },
    });
    expect(put.statusCode).toBe(200);
    expect(put.json().access.active).toBe(false);

    const get = await app.inject({ method: "GET", url: `/api/members/${id}/access`, headers: ADMIN });
    expect(get.json().access.active).toBe(false);

    await app.close();
  });
});

test("PUT max-devices 2 → GET reflects maxDevices=2", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "access-max@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);

    const put = await app.inject({
      method: "PUT",
      url: `/api/members/${id}/access/max-devices`,
      headers: ADMIN,
      payload: { maxDevices: 2 },
    });
    expect(put.statusCode).toBe(200);
    expect(put.json().access.maxDevices).toBe(2);

    const get = await app.inject({ method: "GET", url: `/api/members/${id}/access`, headers: ADMIN });
    expect(get.json().access.maxDevices).toBe(2);

    await app.close();
  });
});

test("DELETE a seeded device → 200 and GET shows it gone", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "access-rmdev@test.dev");
    await db.query(
      "insert into public.member_device (user_id, device_id, device_name) values ($1, $2, $3)",
      [id, "device-abc", "Acme TV"],
    );
    const app = buildTestApp(db, tokenIsEmailVerifier);

    const before = await app.inject({ method: "GET", url: `/api/members/${id}/access`, headers: ADMIN });
    expect(before.json().access.devices.map((d: any) => d.deviceId)).toEqual(["device-abc"]);

    const del = await app.inject({
      method: "DELETE",
      url: `/api/members/${id}/devices/device-abc`,
      headers: ADMIN,
    });
    expect(del.statusCode).toBe(200);
    expect(del.json().ok).toBe(true);

    const after = await app.inject({ method: "GET", url: `/api/members/${id}/access`, headers: ADMIN });
    expect(after.json().access.devices).toEqual([]);

    await app.close();
  });
});

test("DELETE all devices → 200 and GET shows none", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "access-rmall@test.dev");
    await db.query(
      "insert into public.member_device (user_id, device_id, device_name) values ($1, $2, $3), ($1, $4, $5)",
      [id, "device-1", "TV One", "device-2", "TV Two"],
    );
    const app = buildTestApp(db, tokenIsEmailVerifier);

    const del = await app.inject({ method: "DELETE", url: `/api/members/${id}/devices`, headers: ADMIN });
    expect(del.statusCode).toBe(200);
    expect(del.json().ok).toBe(true);

    const after = await app.inject({ method: "GET", url: `/api/members/${id}/access`, headers: ADMIN });
    expect(after.json().access.devices).toEqual([]);

    await app.close();
  });
});

test("DELETE a device with a whitespace deviceId returns 400", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "access-baddev@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "DELETE",
      url: `/api/members/${id}/devices/${encodeURIComponent("   ")}`,
      headers: ADMIN,
    });
    expect(res.statusCode).toBe(400);
    await app.close();
  });
});

test("PUT max-devices < 1 returns 400", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "access-badmax@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "PUT",
      url: `/api/members/${id}/access/max-devices`,
      headers: ADMIN,
      payload: { maxDevices: 0 },
    });
    expect(res.statusCode).toBe(400);
    await app.close();
  });
});

test("PUT active with a non-boolean returns 400", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "access-badactive@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "PUT",
      url: `/api/members/${id}/access/active`,
      headers: ADMIN,
      payload: { active: "nope" },
    });
    expect(res.statusCode).toBe(400);
    await app.close();
  });
});

test("unknown member returns 404 on every access route", async () => {
  await withRollback(async (db) => {
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const ghost = "00000000-0000-0000-0000-0000000000ff";

    const get = await app.inject({ method: "GET", url: `/api/members/${ghost}/access`, headers: ADMIN });
    expect(get.statusCode).toBe(404);

    const active = await app.inject({
      method: "PUT",
      url: `/api/members/${ghost}/access/active`,
      headers: ADMIN,
      payload: { active: false },
    });
    expect(active.statusCode).toBe(404);

    const max = await app.inject({
      method: "PUT",
      url: `/api/members/${ghost}/access/max-devices`,
      headers: ADMIN,
      payload: { maxDevices: 2 },
    });
    expect(max.statusCode).toBe(404);

    const rmDev = await app.inject({
      method: "DELETE",
      url: `/api/members/${ghost}/devices/device-abc`,
      headers: ADMIN,
    });
    expect(rmDev.statusCode).toBe(404);

    const rmAll = await app.inject({ method: "DELETE", url: `/api/members/${ghost}/devices`, headers: ADMIN });
    expect(rmAll.statusCode).toBe(404);

    await app.close();
  });
});

test("DELETE a non-existent device for a real member returns 404", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "access-nodev@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "DELETE",
      url: `/api/members/${id}/devices/does-not-exist`,
      headers: ADMIN,
    });
    expect(res.statusCode).toBe(404);
    await app.close();
  });
});
