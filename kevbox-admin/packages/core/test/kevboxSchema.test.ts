import { afterAll, expect, test } from "vitest";
import type { Db } from "../src/types.js";
import { pool, withRollback, createTestMember } from "./helpers.js";

afterAll(async () => { await pool.end(); });

/**
 * Assert that `run()` rejects, WITHOUT poisoning the surrounding withRollback
 * transaction. A Postgres constraint violation aborts the whole txn on a single
 * connection ("current transaction is aborted"), so we fence the failing query
 * inside a savepoint and roll back to it once it throws — letting the test keep
 * issuing queries on the same connection.
 */
async function expectQueryRejects(db: Db, run: () => Promise<unknown>): Promise<void> {
  await db.query("savepoint kvm_sp");
  await expect(run()).rejects.toThrow();
  await db.query("rollback to savepoint kvm_sp");
}

test("kevbox_member + kevbox_allowlist_extra exist with expected constraints", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "schema-kvm@test.dev");
    // insert is accepted
    await db.query(
      `insert into public.kevbox_member (user_id, aiostreams_name, premiumize_key_enc)
       values ($1, $2, $3)`,
      [uid, "schema-kvm", "v1.aaa.bbb.ccc"],
    );
    const { rows } = await db.query(
      "select aiostreams_name, enrolled, premiumize_key_enc from public.kevbox_member where user_id = $1",
      [uid],
    );
    expect(rows[0].enrolled).toBe(true);
    expect(rows[0].aiostreams_name).toBe("schema-kvm");

    // bad name rejected by the check constraint
    const uid2 = await createTestMember(db, "schema-bad@test.dev");
    await expectQueryRejects(db, () =>
      db.query(`insert into public.kevbox_member (user_id, aiostreams_name) values ($1, $2)`, [
        uid2,
        "BAD UPPER",
      ]),
    );

    // extras table accepts a verbatim name
    await db.query(`insert into public.kevbox_allowlist_extra (aiostreams_name) values ($1)`, [
      "legacy.only",
    ]);
    const { rows: ex } = await db.query(
      "select aiostreams_name from public.kevbox_allowlist_extra where aiostreams_name = 'legacy.only'",
    );
    expect(ex).toHaveLength(1);
  });
});

test("active-name uniqueness binds enrolled rows only (name reuse after un-enroll, H5)", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "reuse-a@test.dev");
    const b = await createTestMember(db, "reuse-b@test.dev");
    // member A enrolled as "shared"
    await db.query(
      `insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1, 'shared', true)`,
      [a],
    );
    // a SECOND enrolled "shared" must fail
    await expectQueryRejects(db, () =>
      db.query(
        `insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1, 'shared', true)`,
        [b],
      ),
    );
    // but once A is un-enrolled, B may take the name
    await db.query(`update public.kevbox_member set enrolled = false where user_id = $1`, [a]);
    await db.query(
      `insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1, 'shared', true)`,
      [b],
    );
    const { rows } = await db.query(
      "select count(*)::int as n from public.kevbox_member where aiostreams_name = 'shared'",
    );
    expect(rows[0].n).toBe(2);
  });
});
