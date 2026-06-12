import { afterAll, expect, test } from "vitest";
import { mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { KevboxConfig } from "../src/types.js";
import { pool, withRollback, createTestMember } from "./helpers.js";
import { loadEncKey } from "../src/crypto.js";
import { resetToDefaults } from "../src/reset.js";
import {
  localPart,
  enrollMember,
  renameMember,
  rotateKey,
  unenrollMember,
  getKevbox,
  buildInstallUrl,
  reapplyKevboxAddon,
} from "../src/kevboxMember.js";

afterAll(async () => { await pool.end(); });

function cfg(): KevboxConfig {
  return {
    encKey: loadEncKey("0".repeat(64)),
    membersFile: join(mkdtempSync(join(tmpdir(), "kvm-")), "members.json"),
    streamsBaseUrl: "https://streams.kevbox.dev",
    addonSort: 4,
  };
}

const kevboxRows = (db: any, uid: string) =>
  db.query(`select url from public.member_addon where user_id = $1 and url like 'https://streams.kevbox.dev/stremio/k/%'`, [uid]).then((r: any) => r.rows.map((x: any) => x.url));

test("localPart derives the email local-part, lowercased", () => {
  expect(localPart("John.Doe@example.com")).toBe("john.doe");
  expect(localPart(null)).toBe("");
});

test("enroll stores verbatim name, encrypts key, adds the member_addon URL", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "Enroll.One@test.dev");
    const c = cfg();
    const { installUrl, name } = await enrollMember(db, uid, { premiumizeKey: "PMKEY" }, c);
    expect(name).toBe("enroll.one"); // defaulted from email local-part
    expect(installUrl).toBe("https://streams.kevbox.dev/stremio/k/enroll.one/PMKEY/manifest.json");

    const state = await getKevbox(db, uid, c);
    expect(state).toEqual({ name: "enroll.one", enrolled: true, hasKey: true });
    expect(await kevboxRows(db, uid)).toEqual([installUrl]);
    // the stored key round-trips via buildInstallUrl
    expect(await buildInstallUrl(db, uid, c)).toBe(installUrl);
  });
});

test("enroll honors an explicit verbatim name (C1)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "john@test.dev");
    const c = cfg();
    const { name } = await enrollMember(db, uid, { aiostreamsName: "john2", premiumizeKey: "K" }, c);
    expect(name).toBe("john2");
    expect((await getKevbox(db, uid, c))!.name).toBe("john2");
  });
});

test("enroll rejects a duplicate active name", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "dupe-a@test.dev");
    const b = await createTestMember(db, "dupe-b@test.dev");
    const c = cfg();
    await enrollMember(db, a, { aiostreamsName: "samename", premiumizeKey: "K" }, c);
    await expect(
      enrollMember(db, b, { aiostreamsName: "samename", premiumizeKey: "K" }, c),
    ).rejects.toThrow(/in use/i);
  });
});

test("rotateKey replaces the URL with the new key", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "rot@test.dev");
    const c = cfg();
    await enrollMember(db, uid, { aiostreamsName: "rotuser", premiumizeKey: "OLD" }, c);
    const { installUrl } = await rotateKey(db, uid, "NEW", c);
    expect(installUrl).toBe("https://streams.kevbox.dev/stremio/k/rotuser/NEW/manifest.json");
    expect(await kevboxRows(db, uid)).toEqual([installUrl]); // exactly one, the new one
  });
});

test("renameMember updates name + URL (H1)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "ren@test.dev");
    const c = cfg();
    await enrollMember(db, uid, { aiostreamsName: "oldname", premiumizeKey: "K" }, c);
    const { installUrl } = await renameMember(db, uid, "newname", c);
    expect(installUrl).toBe("https://streams.kevbox.dev/stremio/k/newname/K/manifest.json");
    expect((await getKevbox(db, uid, c))!.name).toBe("newname");
    expect(await kevboxRows(db, uid)).toEqual([installUrl]);
  });
});

test("unenroll flips enrolled=false and removes the kevbox URL (key retained)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "un@test.dev");
    const c = cfg();
    await enrollMember(db, uid, { aiostreamsName: "ununun", premiumizeKey: "K" }, c);
    await unenrollMember(db, uid, c);
    expect((await getKevbox(db, uid, c))).toEqual({ name: "ununun", enrolled: false, hasKey: true });
    expect(await kevboxRows(db, uid)).toEqual([]);
  });
});

test("getKevbox returns null for a never-enrolled member", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "none@test.dev");
    expect(await getKevbox(db, uid, cfg())).toBeNull();
  });
});

test("enroll throws for an unknown userId", async () => {
  await withRollback(async (db) => {
    await expect(
      enrollMember(db, "00000000-0000-0000-0000-0000000000ff", { premiumizeKey: "K" }, cfg()),
    ).rejects.toThrow(/not found/i);
  });
});

test("reset + reapplyKevboxAddon keeps an enrolled member's kevbox URL", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "reset.kev@test.dev");
    const c = cfg();
    const { installUrl } = await enrollMember(db, uid, { aiostreamsName: "resetkev", premiumizeKey: "K" }, c);
    await resetToDefaults(db, uid);
    expect(await kevboxRows(db, uid)).toEqual([]); // reset stripped it
    await reapplyKevboxAddon(db, uid, c);
    expect(await kevboxRows(db, uid)).toEqual([installUrl]); // restored
  });
});
