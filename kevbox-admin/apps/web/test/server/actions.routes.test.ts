import { afterAll, expect, test } from "vitest";
import { pool, withRollback, createTestMember } from "../../../../packages/core/test/helpers.js";
import { buildTestApp, tokenIsEmailVerifier } from "./helpers.js";

const ADMIN = { authorization: "Bearer admin@test.dev" };

afterAll(async () => { await pool.end(); });

test("POST /reset replaces a member's rows with the defaults", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "reset@test.dev");
    await db.query(
      "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://junk.example',9)",
      [id],
    );
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({ method: "POST", url: `/api/members/${id}/reset`, headers: ADMIN });
    expect(res.statusCode).toBe(200);

    const { rows } = await db.query("select url from public.default_member_addons() order by sort_order");
    const detail = await app.inject({ method: "GET", url: `/api/members/${id}`, headers: ADMIN });
    expect(detail.json().member.addons.map((a: any) => a.url)).toEqual(rows.map((r: any) => r.url));
    await app.close();
  });
});

test("POST /debrid inserts Torrentio (sort 4) + AIOStreams (sort 5)", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "debrid@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "POST",
      url: `/api/members/${id}/debrid`,
      headers: ADMIN,
      payload: { premiumizeKey: "KEY123", aiostreamsUrl: "https://aio.example/m.json" },
    });
    expect(res.statusCode).toBe(201);

    const detail = await app.inject({ method: "GET", url: `/api/members/${id}`, headers: ADMIN });
    const addons = detail.json().member.addons as any[];
    const torrentio = addons.find((a) => a.sortOrder === 4);
    const aio = addons.find((a) => a.sortOrder === 5);
    expect(torrentio.url).toContain("premiumize=KEY123");
    expect(aio.url).toBe("https://aio.example/m.json");
    await app.close();
  });
});

test("POST /debrid rejects a missing premiumizeKey with 400", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "debrid2@test.dev");
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "POST",
      url: `/api/members/${id}/debrid`,
      headers: ADMIN,
      payload: { premiumizeKey: "", aiostreamsUrl: "https://aio.example/m.json" },
    });
    expect(res.statusCode).toBe(400);
    await app.close();
  });
});
