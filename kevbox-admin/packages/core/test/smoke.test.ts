import { afterAll, expect, test } from "vitest";
import { pool, withRollback, createTestMember } from "./helpers.js";

afterAll(async () => { await pool.end(); });

test("test DB is reachable and schema is applied", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "smoke@test.dev");
    expect(id).toMatch(/^[0-9a-f-]{36}$/);
    const { rows } = await db.query("select count(*)::int as n from public.default_member_addons()");
    expect(rows[0].n).toBe(4);
  });
});
