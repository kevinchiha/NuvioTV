import { afterAll, afterEach, beforeEach, expect, test } from "vitest";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { buildApp } from "../../src/server/app.js";
import { pool, withRollback } from "../../../../packages/core/test/helpers.js";
import { tokenIsEmailVerifier, ADMIN_EMAILS } from "./helpers.js";

afterAll(async () => { await pool.end(); });

let dir: string;
beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "kevbox-public-"));
  writeFileSync(join(dir, "index.html"), "<!doctype html><title>SPA</title>");
});
afterEach(() => { rmSync(dir, { recursive: true, force: true }); });

test("serves index.html at / and falls back to it for unknown non-/api GETs", async () => {
  await withRollback(async (db) => {
    const app = buildApp({ db, verifier: tokenIsEmailVerifier, adminEmails: ADMIN_EMAILS, publicDir: dir });

    const root = await app.inject({ method: "GET", url: "/" });
    expect(root.statusCode).toBe(200);
    expect(root.body).toContain("SPA");

    const deep = await app.inject({ method: "GET", url: "/members/some@one.dev" });
    expect(deep.statusCode).toBe(200);
    expect(deep.body).toContain("SPA");

    await app.close();
  });
});

test("unknown /api routes still 404 (not the SPA fallback)", async () => {
  await withRollback(async (db) => {
    const app = buildApp({ db, verifier: tokenIsEmailVerifier, adminEmails: ADMIN_EMAILS, publicDir: dir });
    const res = await app.inject({
      method: "GET",
      url: "/api/does-not-exist",
      headers: { authorization: "Bearer admin@test.dev" },
    });
    expect(res.statusCode).toBe(404);
    expect(res.body).not.toContain("SPA");
    await app.close();
  });
});
