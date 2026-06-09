import { expect, test } from "vitest";
import { bearerToken } from "../../src/server/auth.js";

test("bearerToken parses a valid Authorization header", () => {
  expect(bearerToken("Bearer abc.def.ghi")).toBe("abc.def.ghi");
  expect(bearerToken("bearer xyz")).toBe("xyz");
});

test("bearerToken returns null for missing or malformed headers", () => {
  expect(bearerToken(undefined)).toBeNull();
  expect(bearerToken("")).toBeNull();
  expect(bearerToken("Basic abc")).toBeNull();
  expect(bearerToken("Bearer ")).toBeNull();
});

import { afterAll } from "vitest";
import { pool, withRollback } from "../../../../packages/core/test/helpers.js";
import { buildTestApp, tokenIsEmailVerifier } from "./helpers.js";

afterAll(async () => { await pool.end(); });

test("GET /api/members rejects a missing token with 401", async () => {
  await withRollback(async (db) => {
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({ method: "GET", url: "/api/members" });
    expect(res.statusCode).toBe(401);
    await app.close();
  });
});

test("GET /api/members rejects an invalid token with 401", async () => {
  await withRollback(async (db) => {
    // empty-string token → verifier returns null
    const app = buildTestApp(db, async () => null);
    const res = await app.inject({
      method: "GET",
      url: "/api/members",
      headers: { authorization: "Bearer whatever" },
    });
    expect(res.statusCode).toBe(401);
    await app.close();
  });
});

test("GET /api/members rejects a valid NON-admin token with 403", async () => {
  await withRollback(async (db) => {
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "GET",
      url: "/api/members",
      headers: { authorization: "Bearer not-admin@test.dev" },
    });
    expect(res.statusCode).toBe(403);
    await app.close();
  });
});

test("GET /api/members accepts an admin token with 200", async () => {
  await withRollback(async (db) => {
    const app = buildTestApp(db, tokenIsEmailVerifier);
    const res = await app.inject({
      method: "GET",
      url: "/api/members",
      headers: { authorization: "Bearer admin@test.dev" },
    });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toHaveProperty("members");
    await app.close();
  });
});
