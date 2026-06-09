import { expect, test } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { resetToDefaults } from "../src/reset.js";
import { addAddon } from "../src/addons.js";
import { getMember } from "../src/members.js";

test("resetToDefaults replaces a member's rows with default_member_addons()", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "a@test.dev");
    await addAddon(db, id, { url: "https://torrentio.strem.fun/x/manifest.json", sortOrder: 9 });
    await resetToDefaults(db, id);
    const { rows } = await db.query("select url, sort_order from public.default_member_addons() order by sort_order");
    const member = (await getMember(db, id))!;
    expect(member.addons.map((a) => a.url)).toEqual(rows.map((r: any) => r.url));
    expect(member.addons.every((a) => a.enabled)).toBe(true);
  });
});

test("resetToDefaults is idempotent + removes debrid rows with no unique violation, never zero addons", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "b@test.dev");
    await resetToDefaults(db, id); // seed the defaults
    await addAddon(db, id, { url: "https://torrentio.strem.fun/x/manifest.json", sortOrder: 4 });
    // Re-run while the member ALREADY holds every default url (the upsert path) — must not throw.
    await resetToDefaults(db, id);
    const { rows } = await db.query("select url from public.default_member_addons() order by sort_order");
    const member = (await getMember(db, id))!;
    expect(member.addons.map((a) => a.url)).toEqual(rows.map((r: any) => r.url)); // debrid row gone
    expect(member.addons.length).toBeGreaterThan(0); // never wiped to zero
  });
});
