import { expect, test } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { bulkAddAddon, bulkSwapUrl, snapshotAllAddons } from "../src/bulk.js";
import { getMember } from "../src/members.js";

test("bulkAddAddon refuses without confirm", async () => {
  await withRollback(async (db) => {
    await expect(bulkAddAddon(db, { url: "https://x.example", sortOrder: 99 }, false)).rejects.toThrow(/confirm/i);
  });
});

test("bulkAddAddon adds the url to every member (idempotent)", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "a@test.dev");
    const b = await createTestMember(db, "b@test.dev");
    const n = await bulkAddAddon(db, { url: "https://x.example", sortOrder: 99 }, true);
    expect(n).toBe(2);
    expect((await getMember(db, a))!.addons.some((x) => x.url === "https://x.example")).toBe(true);
    expect((await getMember(db, b))!.addons.some((x) => x.url === "https://x.example")).toBe(true);
    // re-run: no new rows
    expect(await bulkAddAddon(db, { url: "https://x.example", sortOrder: 99 }, true)).toBe(0);
  });
});

test("bulkSwapUrl swaps everywhere and collapses members who had both", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "a@test.dev");
    const b = await createTestMember(db, "b@test.dev");
    await db.query("insert into public.member_addon (user_id, url, sort_order) values ($1,'https://old.example',0)", [a]);
    // b already has BOTH old and new -> swap must not violate unique(user_id,url)
    await db.query("insert into public.member_addon (user_id, url, sort_order) values ($1,'https://old.example',0),($1,'https://new.example',1)", [b]);
    await bulkSwapUrl(db, { fromUrl: "https://old.example", toUrl: "https://new.example" }, true);
    expect((await getMember(db, a))!.addons.map((x) => x.url)).toEqual(["https://new.example"]);
    const burls = (await getMember(db, b))!.addons.map((x) => x.url);
    expect(burls).toContain("https://new.example");
    expect(burls).not.toContain("https://old.example");
    expect(burls.filter((u) => u === "https://new.example")).toHaveLength(1);
  });
});

test("snapshotAllAddons captures every member's rows with email (the pre-bulk backstop)", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "a@test.dev");
    await db.query("insert into public.member_addon (user_id, url, sort_order) values ($1,'https://old.example',0)", [a]);
    const snap = await snapshotAllAddons(db);
    const mine = snap.filter((r) => r.email === "a@test.dev");
    expect(mine).toHaveLength(1);
    expect(mine[0]).toMatchObject({ url: "https://old.example", enabled: true, sortOrder: 0 });
  });
});
