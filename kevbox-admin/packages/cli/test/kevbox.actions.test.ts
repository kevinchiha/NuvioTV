import { afterAll, expect, test } from "vitest";
import { mkdtempSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { KevboxConfig } from "@kevbox-admin/core";
import { loadEncKey, getKevbox } from "@kevbox-admin/core";
import { pool, withRollback, createTestMember } from "../../core/test/helpers.js";
import { actionKevboxEnroll, actionKevboxMigrate } from "../src/actions.js";

afterAll(async () => { await pool.end(); });

function cfg(): KevboxConfig {
  return { encKey: loadEncKey("0".repeat(64)), membersFile: join(mkdtempSync(join(tmpdir(), "cli-")), "members.json"), streamsBaseUrl: "https://streams.kevbox.dev", addonSort: 4 };
}
function captureSink() { const out: string[] = []; const err: string[] = []; return { sink: { log: (s: string) => out.push(s), err: (s: string) => err.push(s) }, out, err }; }

test("actionKevboxEnroll enrolls + writes members.json", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "cli.one@test.dev");
    const c = cfg(); const { sink, out } = captureSink();
    await actionKevboxEnroll(db, "cli.one@test.dev", { premiumize: "PMK" }, c, sink);
    expect(out.join("\n")).toContain("/stremio/k/cli.one/PMK/manifest.json");
    expect((await getKevbox(db, uid, c))!.enrolled).toBe(true);
    expect(JSON.parse(readFileSync(c.membersFile, "utf8"))).toContain("cli.one");
  });
});

test("actionKevboxMigrate dry-run prints a report and writes nothing", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "cli.mig@test.dev");
    await db.query(`insert into public.member_addon (user_id, url, sort_order) values ($1, 'https://streams.kevbox.dev/stremio/k/cli.mig/K/manifest.json', 4)`, [uid]);
    const c = cfg(); const { sink, out } = captureSink();
    await actionKevboxMigrate(db, ["cli.mig"], { apply: false }, c, sink);
    expect(out.join("\n")).toMatch(/matched.*1/i);
    expect((await getKevbox(db, uid, c))).toBeNull(); // dry-run persisted nothing
  });
});
