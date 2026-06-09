import { afterAll, expect, test } from "vitest";
import { pool, withRollback, createTestMember } from "../../../../packages/core/test/helpers.js";
import { buildTestApp, tokenIsEmailVerifier } from "./helpers.js";

const ADMIN = { authorization: "Bearer admin@test.dev" };

afterAll(async () => { await pool.end(); });

test("GET /api/members returns members with counts", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "m@test.dev");
    await db.query(
      "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://v3-cinemeta.strem.io',0),($1,'https://torrentio.strem.fun/x/manifest.json',4)",
      [id],
    );
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({ method: "GET", url: "/api/members", headers: ADMIN });
    expect(res.statusCode).toBe(200);
    const member = res.json().members.find((x: any) => x.email === "m@test.dev");
    expect(member.addonCount).toBe(2);
    expect(member.hasDebrid).toBe(true);
    await app.close();
  });
});

test("GET /api/members/:ref resolves by email and by userId", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "detail@test.dev");
    await db.query(
      "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://a.example',0)",
      [id],
    );
    const app = buildTestApp(db, tokenIsEmailVerifier);

    const byEmail = await app.inject({ method: "GET", url: "/api/members/detail@test.dev", headers: ADMIN });
    expect(byEmail.statusCode).toBe(200);
    expect(byEmail.json().member.userId).toBe(id);
    expect(byEmail.json().member.addons).toHaveLength(1);

    const byId = await app.inject({ method: "GET", url: `/api/members/${id}`, headers: ADMIN });
    expect(byId.statusCode).toBe(200);
    expect(byId.json().member.email).toBe("detail@test.dev");
    await app.close();
  });
});

test("GET /api/members/:ref returns 404 for an unknown member", async () => {
  await withRollback(async (db) => {
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({ method: "GET", url: "/api/members/nobody@test.dev", headers: ADMIN });
    expect(res.statusCode).toBe(404);
    await app.close();
  });
});
