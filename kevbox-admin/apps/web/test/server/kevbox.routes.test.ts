import { afterAll, afterEach, expect, test } from "vitest";
import { readFileSync, writeFileSync, rmSync, mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { Db } from "@kevbox-admin/core";
import { renderMembersFile } from "@kevbox-admin/core";
import { pool, withRollback, createTestMember } from "../../../../packages/core/test/helpers.js";
import { buildKevboxTestApp } from "./helpers.js";

const ADMIN = { authorization: "Bearer admin@test.dev" };
afterAll(async () => { await pool.end(); });

const files: string[] = [];
function membersFile(): string { const f = join(mkdtempSync(join(tmpdir(), "kr-")), "members.json"); files.push(f); return f; }
afterEach(() => { for (const f of files.splice(0)) { try { rmSync(f); } catch { /* */ } } });

test("PUT enroll → GET member shows kevbox block (no key); members.json written", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.one@test.dev");
    const file = membersFile();
    const app = buildKevboxTestApp(db, file);

    const put = await app.inject({
      method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN,
      payload: { premiumizeKey: "PMK" },
    });
    expect(put.statusCode).toBe(200);
    expect(put.json().kevbox).toEqual({ name: "route.one", enrolled: true, hasKey: true });

    const audit = await db.query(
      "select admin_email, action from public.kevbox_audit where user_id = $1 order by id",
      [uid],
    );
    expect(audit.rows.map((r) => r.action)).toContain("kevbox.enroll");
    expect(audit.rows[0].admin_email).toBe("admin@test.dev");
    expect(JSON.stringify(audit.rows)).not.toContain("PMK"); // no secret in the audit trail

    const get = await app.inject({ method: "GET", url: `/api/members/${uid}`, headers: ADMIN });
    expect(get.json().member.kevbox).toEqual({ name: "route.one", enrolled: true, hasKey: true });
    expect(JSON.stringify(get.json())).not.toContain("PMK"); // key never in the default fetch (C5)
    expect(JSON.parse(readFileSync(file, "utf8"))).toContain("route.one");

    await app.close();
  });
});

test("GET install-url is the only endpoint that returns the key-bearing URL (C5)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.url@test.dev");
    const app = buildKevboxTestApp(db, membersFile());
    await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { aiostreamsName: "rurl", premiumizeKey: "SECRET" } });

    const res = await app.inject({ method: "GET", url: `/api/members/${uid}/kevbox/install-url`, headers: ADMIN });
    expect(res.statusCode).toBe(200);
    expect(res.json().installUrl).toBe("https://streams.kevbox.dev/stremio/k/rurl/SECRET/manifest.json");
    await app.close();
  });
});

test("PUT with name only on an unenrolled member → 400 (enroll requires a key)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.nokey@test.dev");
    const app = buildKevboxTestApp(db, membersFile());
    const res = await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { name: "nokey" } });
    expect(res.statusCode).toBe(400);
    await app.close();
  });
});

test("PUT name-change on enrolled member renames (URL rebuilt)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.ren@test.dev");
    const app = buildKevboxTestApp(db, membersFile());
    await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { aiostreamsName: "before", premiumizeKey: "K" } });
    const res = await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { name: "after" } });
    expect(res.statusCode).toBe(200);
    expect(res.json().kevbox.name).toBe("after");
    const url = await app.inject({ method: "GET", url: `/api/members/${uid}/kevbox/install-url`, headers: ADMIN });
    expect(url.json().installUrl).toContain("/k/after/K/");
    await app.close();
  });
});

test("DELETE un-enrolls when others remain (file re-rendered)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.del@test.dev");
    const file = membersFile();
    const app = buildKevboxTestApp(db, file);
    await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { aiostreamsName: "deluser", premiumizeKey: "K" } });
    // a second extra remains, so the render still produces a non-empty set (normal path)
    await db.query(`insert into public.kevbox_allowlist_extra (aiostreams_name) values ('keepalive')`);
    const res = await app.inject({ method: "DELETE", url: `/api/members/${uid}/kevbox`, headers: ADMIN });
    expect(res.statusCode).toBe(200);
    expect(res.json().warning).toBeUndefined(); // non-empty render → no soft-success warning
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["keepalive"]); // re-rendered without deluser
    const get = await app.inject({ method: "GET", url: `/api/members/${uid}`, headers: ADMIN });
    expect(get.json().member.kevbox.enrolled).toBe(false);
    await app.close();
  });
});

test("DELETE the LAST enrolled member (no extras) is a soft success; file left intact (M11)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "route.last@test.dev");
    const file = membersFile();
    const app = buildKevboxTestApp(db, file);
    // enroll renders ["onlyone"]; this is the only enrolled member and there are NO extras
    await app.inject({ method: "PUT", url: `/api/members/${uid}/kevbox`, headers: ADMIN, payload: { aiostreamsName: "onlyone", premiumizeKey: "K" } });
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["onlyone"]);
    // un-enrolling the last member would render an EMPTY set → soft success, file untouched
    const res = await app.inject({ method: "DELETE", url: `/api/members/${uid}/kevbox`, headers: ADMIN });
    expect(res.statusCode).toBe(200);
    expect(res.json().warning).toMatch(/safety floor/i);
    expect(res.json().kevbox.enrolled).toBe(false); // un-enroll DID commit
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["onlyone"]); // prior file left intact
    await app.close();
  });
});

test("unknown member → 404", async () => {
  await withRollback(async (db) => {
    const app = buildKevboxTestApp(db, membersFile());
    const ghost = "00000000-0000-0000-0000-0000000000ff";
    const res = await app.inject({ method: "PUT", url: `/api/members/${ghost}/kevbox`, headers: ADMIN, payload: { premiumizeKey: "K" } });
    expect(res.statusCode).toBe(404);
    await app.close();
  });
});

// Mirror index.ts Step 8b: gated, fail-soft boot-render.
async function bootRender(db: Db, file: string): Promise<void> {
  const { rows } = await db.query<{ n: number }>("select count(*)::int as n from public.kevbox_member where enrolled");
  if (rows[0]!.n > 0) await renderMembersFile(db, file);
}

test("boot-render reconciles a stale members.json from the DB (H3)", async () => {
  await withRollback(async (db) => {
    const uid = await createTestMember(db, "boot.render@test.dev");
    await db.query("insert into public.kevbox_member (user_id, aiostreams_name, enrolled) values ($1,'bootname',true)", [uid]);
    const file = membersFile();
    writeFileSync(file, JSON.stringify(["STALE-do-not-keep"])); // drifted file on disk
    await bootRender(db, file);
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["bootname"]); // reconciled from the DB
  });
});

test("boot-render is skipped (empty-set gate) when no member is enrolled (H3)", async () => {
  await withRollback(async (db) => {
    const file = membersFile();
    writeFileSync(file, JSON.stringify(["prior"])); // a pre-existing file
    await bootRender(db, file); // 0 enrolled rows → gate skips the render, floor never trips
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual(["prior"]); // left intact
  });
});
