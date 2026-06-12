import { afterAll, afterEach, expect, test } from "vitest";
import { existsSync, readFileSync, rmSync, mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { KevboxConfig } from "../src/types.js";
import { pool, withRollback, createTestMember } from "./helpers.js";
import { loadEncKey } from "../src/crypto.js";
import { enrollMember } from "../src/kevboxMember.js";
import { withKevboxWrite } from "../src/kevboxWrite.js";

afterAll(async () => { await pool.end(); });

const made: string[] = [];
function cfg(): KevboxConfig {
  const file = join(mkdtempSync(join(tmpdir(), "kww-")), "members.json");
  made.push(file);
  return { encKey: loadEncKey("0".repeat(64)), membersFile: file, streamsBaseUrl: "https://streams.kevbox.dev", addonSort: 4 };
}
afterEach(() => { for (const f of made.splice(0)) { try { rmSync(f); } catch { /* */ } } });

// Client path (rollback): runs fn inline on the same connection, then renders.
test("renders members.json after the mutation (client path)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "ww-a@test.dev");
    const c = cfg();
    const res = await withKevboxWrite(db, c.membersFile, (d) =>
      enrollMember(d, uid, { aiostreamsName: "wwa", premiumizeKey: "K" }, c),
    );
    expect(res.name).toBe("wwa");
    expect(JSON.parse(readFileSync(c.membersFile, "utf8"))).toContain("wwa");
  });
});

test("a throwing fn does not write the file and rethrows", async () => {
  await withRollback(async (db) => {
    const c = cfg();
    await expect(
      withKevboxWrite(db, c.membersFile, async () => { throw new Error("boom"); }),
    ).rejects.toThrow("boom");
    expect(existsSync(c.membersFile)).toBe(false);
  });
});

// Pool path (real txns, NOT under withRollback): two distinct pool connections each enroll a
// DIFFERENT member concurrently. The xact advisory lock serializes the two writers, so BOTH names
// land in members.json with no transient drop (the second render sees the first committed row).
// These rows commit for real, so clean them up afterward.
test("concurrent writers on distinct connections both land in members.json (serialized, no drop)", async () => {
  const c = cfg();
  // seed two members on real connections (committed — withKevboxWrite uses pool txns)
  const a = await createTestMember(pool, "serial-a@test.dev");
  const b = await createTestMember(pool, "serial-b@test.dev");
  try {
    await Promise.all([
      withKevboxWrite(pool, c.membersFile, (d) => enrollMember(d, a, { aiostreamsName: "serala", premiumizeKey: "K" }, c)),
      withKevboxWrite(pool, c.membersFile, (d) => enrollMember(d, b, { aiostreamsName: "seralb", premiumizeKey: "K" }, c)),
    ]);
    const names = JSON.parse(readFileSync(c.membersFile, "utf8"));
    expect(names).toContain("serala");
    expect(names).toContain("seralb"); // neither writer's name was dropped by the other's render
  } finally {
    await pool.query("delete from public.kevbox_member where user_id = any($1::uuid[])", [[a, b]]);
    await pool.query("delete from public.member_addon where user_id = any($1::uuid[])", [[a, b]]);
    await pool.query("delete from public.kevbox_auth_users where id = any($1::uuid[])", [[a, b]]).catch(() => undefined);
  }
});
