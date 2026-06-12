import { expect, test } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { listMembers, getMember } from "../src/members.js";

test("listMembers reports addon count and enrolled", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "a@test.dev");
    const b = await createTestMember(db, "b@test.dev"); // no addons, not enrolled
    // a: two addons (addonCount coverage)
    await db.query(
      "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://v3-cinemeta.strem.io',0),($1,'https://torrentio.strem.fun/x/manifest.json',4)",
      [a],
    );
    // a is enrolled in kevbox; b is not
    await db.query(
      "insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'a',true)",
      [a],
    );
    const members = await listMembers(db);
    const ma = members.find((m) => m.email === "a@test.dev")!;
    const mb = members.find((m) => m.email === "b@test.dev")!;
    expect(ma.addonCount).toBe(2);
    expect(ma.enrolled).toBe(true);
    expect(mb.addonCount).toBe(0);
    expect(mb.enrolled).toBe(false);
  });
});

test("listMembers reports enrolled=true only for enrolled kevbox members", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "ls-on@test.dev");
    const b = await createTestMember(db, "ls-off@test.dev");
    await db.query(`insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'lson',true),($2,'lsoff',false)`, [a, b]);
    const members = await listMembers(db);
    const on = members.find((m) => m.userId === a)!;
    const off = members.find((m) => m.userId === b)!;
    expect(on.enrolled).toBe(true);
    expect(off.enrolled).toBe(false);
  });
});

test("getMember resolves by email or userId and returns addons in sort order", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "c@test.dev");
    await db.query(
      "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://b.example',1),($1,'https://a.example',0)",
      [id],
    );
    const byEmail = await getMember(db, "c@test.dev");
    const byId = await getMember(db, id);
    expect(byEmail!.userId).toBe(id);
    expect(byId!.email).toBe("c@test.dev");
    expect(byEmail!.addons.map((x) => x.url)).toEqual(["https://a.example", "https://b.example"]);
  });
});

test("getMember returns null for an unknown member", async () => {
  await withRollback(async (db) => {
    expect(await getMember(db, "nobody@test.dev")).toBeNull();
  });
});
