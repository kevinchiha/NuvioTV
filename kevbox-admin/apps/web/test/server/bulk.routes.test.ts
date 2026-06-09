import { afterAll, expect, test } from "vitest";
import { pool, withRollback, createTestMember } from "../../../../packages/core/test/helpers.js";
import { buildTestApp, tokenIsEmailVerifier } from "./helpers.js";

const ADMIN = { authorization: "Bearer admin@test.dev" };

afterAll(async () => { await pool.end(); });

test("POST /bulk/add without confirm is rejected with 400", async () => {
  await withRollback(async (db) => {
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "POST",
      url: "/api/bulk/add",
      headers: ADMIN,
      payload: { url: "https://x.example", sortOrder: 99, confirm: false },
    });
    expect(res.statusCode).toBe(400);
    await app.close();
  });
});

test("POST /bulk/add with confirm adds to every member", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "a@test.dev");
    const b = await createTestMember(db, "b@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "POST",
      url: "/api/bulk/add",
      headers: ADMIN,
      payload: { url: "https://x.example", sortOrder: 99, confirm: true },
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().inserted).toBe(2);

    for (const id of [a, b]) {
      const d = await app.inject({ method: "GET", url: `/api/members/${id}`, headers: ADMIN });
      expect(d.json().member.addons.some((x: any) => x.url === "https://x.example")).toBe(true);
    }
    await app.close();
  });
});

test("POST /bulk/swap with confirm swaps everywhere", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "a@test.dev");
    await db.query(
      "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://old.example',0)",
      [a],
    );
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "POST",
      url: "/api/bulk/swap",
      headers: ADMIN,
      payload: { fromUrl: "https://old.example", toUrl: "https://new.example", confirm: true },
    });
    expect(res.statusCode).toBe(200);
    const d = await app.inject({ method: "GET", url: `/api/members/${a}`, headers: ADMIN });
    expect(d.json().member.addons.map((x: any) => x.url)).toEqual(["https://new.example"]);
    await app.close();
  });
});
