import { expect, test } from "vitest";
import { withRollback, createTestMember } from "./helpers.js";
import { onboardDebrid } from "../src/debrid.js";
import { getMember } from "../src/members.js";

test("onboardDebrid inserts Torrentio (sort 4) + AIOStreams (sort 5)", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "a@test.dev");
    await onboardDebrid(db, id, {
      premiumizeKey: "KEY123",
      aiostreamsUrl: "https://aiostreams.example/u/cfg/manifest.json",
    });
    const addons = (await getMember(db, id))!.addons;
    const torrentio = addons.find((a) => a.sortOrder === 4)!;
    const aio = addons.find((a) => a.sortOrder === 5)!;
    expect(torrentio.url).toContain("premiumize=KEY123");
    expect(aio.url).toBe("https://aiostreams.example/u/cfg/manifest.json");
  });
});

test("onboardDebrid is idempotent (re-run does not duplicate)", async () => {
  await withRollback(async (db) => {
    const id = await createTestMember(db, "b@test.dev");
    const args = { premiumizeKey: "K", aiostreamsUrl: "https://aio.example/m.json" } as const;
    await onboardDebrid(db, id, args);
    await onboardDebrid(db, id, args);
    expect((await getMember(db, id))!.addons).toHaveLength(2);
  });
});
