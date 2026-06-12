import { afterAll, expect, test } from "vitest";
import { mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { pool, withRollback, createTestMember } from "../../../../packages/core/test/helpers.js";
import { buildKevboxTestApp } from "./helpers.js";

const ADMIN = { authorization: "Bearer admin@test.dev" };
afterAll(async () => { await pool.end(); });

test("no request/response log line embeds the Premiumize key or install URL (§13)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "redact@test.dev");
    const file = join(mkdtempSync(join(tmpdir(), "redact-")), "members.json");
    const lines: string[] = [];
    // capture pino output by passing a stream into the test app's logger.
    const app = buildKevboxTestApp(db, file, { logStream: { write: (s: string) => { lines.push(s); } } });
    await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { aiostreamsName: "rdct", premiumizeKey: "SUPER-SECRET-KEY" } });
    await app.inject({ method: "GET", url: `/api/members/${uid}/kevbox/install-url`, headers: ADMIN });
    const all = lines.join("\n");
    expect(all).not.toContain("SUPER-SECRET-KEY"); // key never logged (redacted)
    expect(all).not.toMatch(/\/k\/rdct\/SUPER-SECRET-KEY\//); // install URL never logged
    await app.close();
  });
});
