import type { FastifyInstance } from "fastify";
import type { Db, KevboxConfig } from "@kevbox-admin/core";
import {
  getMember, getKevbox, enrollMember, renameMember, rotateKey, unenrollMember,
  buildInstallUrl, withKevboxWrite, writeAudit,
} from "@kevbox-admin/core";

// The name field accepts both `aiostreamsName` (explicit enroll field, matching enrollMember's opts)
// and `name` (used for rename). `aiostreamsName` wins if both are present.
interface KevboxBody { name?: string; aiostreamsName?: string; premiumizeKey?: string }

export function registerKevboxRoutes(app: FastifyInstance, db: Db, cfg: KevboxConfig): void {
  // The ONLY endpoint that returns the key-bearing install URL (C5). Auditable on its own.
  app.get<{ Params: { userId: string } }>("/members/:userId/kevbox/install-url", async (req, reply) => {
    if (!(await getMember(db, req.params.userId))) return reply.code(404).send({ error: "member not found" });
    const installUrl = await buildInstallUrl(db, req.params.userId, cfg);
    if (!installUrl) return reply.code(404).send({ error: "no install url (member has no stored key)" });
    // §13: record that the key-bearing URL was revealed — the verb only, NEVER the URL/key value.
    await writeAudit(db, { adminEmail: req.adminUser?.email ?? null, userId: req.params.userId, action: "kevbox.reveal-url" });
    return { installUrl };
  });

  // Enroll / rename / rotate, routed by field combination (H1).
  app.put<{ Params: { userId: string }; Body: KevboxBody }>("/members/:userId/kevbox", async (req, reply) => {
    const userId = req.params.userId;
    if (!(await getMember(db, userId))) return reply.code(404).send({ error: "member not found" });
    const rawName = typeof req.body?.aiostreamsName === "string" ? req.body.aiostreamsName
      : typeof req.body?.name === "string" ? req.body.name : undefined;
    const name = typeof rawName === "string" ? rawName.trim() : undefined;
    const key = typeof req.body?.premiumizeKey === "string" ? req.body.premiumizeKey.trim() : undefined;
    const adminEmail = req.adminUser?.email ?? null;

    // TOCTOU fix (H2): read existence + decide enroll-vs-rename-vs-rotate INSIDE the lock, on the
    // LOCKED connection `d` — never on the outer pool `db` before the lock. Read+decide+mutate share
    // the one locked txn, so a concurrent writer can't change `enrolled`/`name` between the decision
    // and the mutation.
    await withKevboxWrite(db, cfg.membersFile, async (d) => {
      const current = await getKevbox(d, userId, cfg);
      const enrolled = current?.enrolled === true;
      if (!enrolled) {
        if (!key) { const e = new Error("premiumizeKey is required to enroll") as Error & { statusCode?: number }; e.statusCode = 400; throw e; }
        await enrollMember(d, userId, { aiostreamsName: name, premiumizeKey: key }, cfg);
        // §13: audit inside the lock so it commits/rolls back atomically with the mutation; verb only.
        await writeAudit(d, { adminEmail, userId, action: "kevbox.enroll" });
        return;
      }
      if (name && name !== current!.name) {
        await renameMember(d, userId, name, cfg);
        await writeAudit(d, { adminEmail, userId, action: "kevbox.rename" });
      }
      if (key) {
        await rotateKey(d, userId, key, cfg);
        await writeAudit(d, { adminEmail, userId, action: "kevbox.rotate" });
      }
      if (!name && !key) { const e = new Error("nothing to update") as Error & { statusCode?: number }; e.statusCode = 400; throw e; }
    });

    // Only the final NON-SECRET response read happens outside the lock (already committed).
    return { kevbox: await getKevbox(db, userId, cfg) };
  });

  // Un-enroll. LAST-MEMBER case (M11): un-enrolling the only enrolled member with no extras renders
  // an EMPTY set, which renderMembersFile refuses (safety floor). With the render INSIDE the txn
  // (Task 5), that would roll the un-enroll back — but the operator clearly intended to un-enroll, so
  // treat empty-on-unenroll as a SOFT success: commit the un-enroll WITHOUT rendering (leaving the
  // prior members.json intact, since the floor exists exactly to avoid blanking the allowlist) and
  // return 200 with a warning. Any other render failure still propagates as a 5xx.
  app.delete<{ Params: { userId: string } }>("/members/:userId/kevbox", async (req, reply) => {
    const userId = req.params.userId;
    if (!(await getMember(db, userId))) return reply.code(404).send({ error: "member not found" });
    const adminEmail = req.adminUser?.email ?? null;
    let warning: string | undefined;
    try {
      await withKevboxWrite(db, cfg.membersFile, async (d) => {
        await unenrollMember(d, userId, cfg);
        // §13: audit inside the lock — atomic with the un-enroll; verb only, no key/URL.
        await writeAudit(d, { adminEmail, userId, action: "kevbox.unenroll" });
      });
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (!/empty/i.test(msg)) throw e; // only the empty-set floor is soft; everything else 5xx
      // The withKevboxWrite txn rolled back (its in-txn render hit the floor, discarding the in-txn
      // audit). Re-run JUST the mutation directly on `db` — unenrollMember is a pure mutation that
      // does NOT render — so the DB un-enroll commits while the (now would-be-empty) members.json is
      // left untouched; re-record the audit on `db` so the un-enroll is still trailed.
      await unenrollMember(db, userId, cfg);
      await writeAudit(db, { adminEmail, userId, action: "kevbox.unenroll" });
      warning = "last enrolled member removed; members.json left intact (empty-set safety floor)";
    }
    return { kevbox: await getKevbox(db, userId, cfg), ...(warning ? { warning } : {}) };
  });
}
