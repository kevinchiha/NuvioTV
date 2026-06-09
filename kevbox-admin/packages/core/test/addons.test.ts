import { expect, test } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { addAddon, updateAddon, setEnabled, reorder, deleteAddon } from "../src/addons.js";
import { getMember } from "../src/members.js";

test("addAddon appends at next sort_order and upserts on duplicate url", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "a@test.dev");
    const first = await addAddon(db, id, { url: "https://a.example" });
    expect(first.sortOrder).toBe(0);
    const second = await addAddon(db, id, { url: "https://b.example" });
    expect(second.sortOrder).toBe(1);
    // duplicate url upserts (enabled flips), does not create a second row
    const dup = await addAddon(db, id, { url: "https://a.example", enabled: false });
    expect(dup.enabled).toBe(false);
    expect((await getMember(db, id))!.addons).toHaveLength(2);
  });
});

test("updateAddon changes only provided fields; setEnabled flips enabled", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "b@test.dev");
    const row = await addAddon(db, id, { url: "https://a.example" });
    const updated = await updateAddon(db, row.id, { url: "https://a2.example" });
    expect(updated!.url).toBe("https://a2.example");
    expect(updated!.enabled).toBe(true); // unchanged
    const toggled = await setEnabled(db, row.id, false);
    expect(toggled!.enabled).toBe(false);
  });
});

test("reorder rewrites sort_order to match the given id order (scoped to the member)", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "c@test.dev");
    const a = await addAddon(db, id, { url: "https://a.example" });
    const b = await addAddon(db, id, { url: "https://b.example" });
    const c = await addAddon(db, id, { url: "https://c.example" });
    await reorder(db, id, [c.id, a.id, b.id]);
    const urls = (await getMember(db, id))!.addons.map((x) => x.url);
    expect(urls).toEqual(["https://c.example", "https://a.example", "https://b.example"]);
  });
});

test("deleteAddon removes the row", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "d@test.dev");
    const row = await addAddon(db, id, { url: "https://a.example" });
    await deleteAddon(db, row.id);
    expect((await getMember(db, id))!.addons).toHaveLength(0);
  });
});
