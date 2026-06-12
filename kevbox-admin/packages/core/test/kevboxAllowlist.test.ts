import { afterAll, afterEach, expect, test } from "vitest";
import { readFileSync, rmSync, statSync, mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { pool, withRollback, createTestMember } from "./helpers.js";
import { renderMembersFile } from "../src/kevboxAllowlist.js";

afterAll(async () => { await pool.end(); });

const tmpFiles: string[] = [];
function tmpMembersPath(): string {
  const dir = mkdtempSync(join(tmpdir(), "kevbox-render-"));
  const p = join(dir, "members.json");
  tmpFiles.push(p);
  return p;
}
afterEach(() => { for (const p of tmpFiles.splice(0)) { try { rmSync(p); } catch { /* ignore */ } } });

test("renders enrolled members + extras, sorted, deduped", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "rend-a@test.dev");
    const b = await createTestMember(db, "rend-b@test.dev");
    await db.query(`insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'bravo',true),($2,'alpha',true)`, [a, b]);
    await db.query(`insert into public.kevbox_allowlist_extra (aiostreams_name) values ('alpha'),('zulu')`); // 'alpha' dup
    const file = tmpMembersPath();

    const names = await renderMembersFile(db, file);
    expect(names).toEqual(["alpha", "bravo", "zulu"]);
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["alpha", "bravo", "zulu"]);
  });
});

test("excludes un-enrolled members", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "rend-off@test.dev");
    await db.query(`insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'ghost',false)`, [a]);
    await db.query(`insert into public.kevbox_allowlist_extra (aiostreams_name) values ('kept')`);
    const file = tmpMembersPath();
    expect(await renderMembersFile(db, file)).toEqual(["kept"]);
  });
});

test("empty set refuses to write (C2 safety floor) and leaves any prior file intact", async () => {
  await withRollback(async (db) => {
    const file = tmpMembersPath();
    // no rows at all → must throw, must not create the file
    await expect(renderMembersFile(db, file)).rejects.toThrow(/empty/i);
    expect(() => statSync(file)).toThrow();
  });
});

test("written file is group-readable (mode 0664, C4)", async () => {
  await withRollback(async (db) => {
    const a = await createTestMember(db, "rend-perm@test.dev");
    await db.query(`insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'permname',true)`, [a]);
    const file = tmpMembersPath();
    await renderMembersFile(db, file);
    // low 9 perm bits == rw-rw-r-- (0o664). umask may strip group-write on tmp; assert at least group-read.
    const mode = statSync(file).mode & 0o060;
    expect(mode & 0o040).toBe(0o040); // group read bit set
  });
});
