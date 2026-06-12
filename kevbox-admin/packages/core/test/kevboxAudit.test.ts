import { afterAll, expect, test } from "vitest";
import { pool, withRollback, createTestMember } from "./helpers.js";
import { writeAudit } from "../src/kevboxAudit.js";

afterAll(async () => { await pool.end(); });

test("writeAudit records action + admin email + user_id, never a secret", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "audit@test.dev");
    await writeAudit(db, { adminEmail: "admin@test.dev", userId: uid, action: "kevbox.enroll" });
    const { rows } = await db.query(
      "select admin_email, user_id, action from public.kevbox_audit where user_id = $1",
      [uid],
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ admin_email: "admin@test.dev", user_id: uid, action: "kevbox.enroll" });
    // structural guard: the audit row carries no secret-bearing column
    expect(Object.keys(rows[0])).not.toContain("premiumize_key_enc");
  });
});
