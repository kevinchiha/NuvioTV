import { afterAll, expect, test } from "vitest";
import { pool, withRollback, createTestMember } from "../../../../packages/core/test/helpers.js";
import { buildTestApp, tokenIsEmailVerifier } from "./helpers.js";

const ADMIN = { authorization: "Bearer admin@test.dev" };

afterAll(async () => { await pool.end(); });

test("POST add → PATCH edit → PUT enabled → PUT order → DELETE", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "ops@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);

    // add
    const add = await app.inject({
      method: "POST",
      url: `/api/members/${id}/addons`,
      headers: ADMIN,
      payload: { url: "https://a.example" },
    });
    expect(add.statusCode).toBe(201);
    const addonId = add.json().addon.id as number;
    expect(add.json().addon.sortOrder).toBe(0);

    // a second addon (for reorder)
    const add2 = await app.inject({
      method: "POST",
      url: `/api/members/${id}/addons`,
      headers: ADMIN,
      payload: { url: "https://b.example" },
    });
    const addonId2 = add2.json().addon.id as number;

    // edit url
    const patch = await app.inject({
      method: "PATCH",
      url: `/api/addons/${addonId}`,
      headers: ADMIN,
      payload: { url: "https://a2.example" },
    });
    expect(patch.statusCode).toBe(200);
    expect(patch.json().addon.url).toBe("https://a2.example");

    // toggle enabled
    const toggle = await app.inject({
      method: "PUT",
      url: `/api/addons/${addonId}/enabled`,
      headers: ADMIN,
      payload: { enabled: false },
    });
    expect(toggle.statusCode).toBe(200);
    expect(toggle.json().addon.enabled).toBe(false);

    // reorder (b before a)
    const order = await app.inject({
      method: "PUT",
      url: `/api/members/${id}/addons/order`,
      headers: ADMIN,
      payload: { orderedIds: [addonId2, addonId] },
    });
    expect(order.statusCode).toBe(200);
    const detail = await app.inject({ method: "GET", url: `/api/members/${id}`, headers: ADMIN });
    expect(detail.json().member.addons.map((a: any) => a.id)).toEqual([addonId2, addonId]);

    // delete
    const del = await app.inject({ method: "DELETE", url: `/api/addons/${addonId}`, headers: ADMIN });
    expect(del.statusCode).toBe(200);
    const after = await app.inject({ method: "GET", url: `/api/members/${id}`, headers: ADMIN });
    expect(after.json().member.addons.map((a: any) => a.id)).toEqual([addonId2]);

    await app.close();
  });
});

test("POST add rejects an empty url with 400", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "bad@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "POST",
      url: `/api/members/${id}/addons`,
      headers: ADMIN,
      payload: { url: "   " },
    });
    expect(res.statusCode).toBe(400);
    await app.close();
  });
});

test("PATCH a missing addon returns 404", async () => {
  await withRollback(async (db) => {
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "PATCH",
      url: "/api/addons/99999999",
      headers: ADMIN,
      payload: { enabled: false },
    });
    expect(res.statusCode).toBe(404);
    await app.close();
  });
});
