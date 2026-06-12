import type { FastifyInstance } from "fastify";
import type { Db, KevboxConfig } from "@kevbox-admin/core";
import { resetToDefaults, reapplyKevboxAddon, getMember } from "@kevbox-admin/core";

export function registerActionRoutes(app: FastifyInstance, db: Db, kevbox?: KevboxConfig): void {
  // Reset a member to the baked-in universal defaults.
  app.post<{ Params: { userId: string } }>("/members/:userId/reset", async (req, reply) => {
    // Unknown member → 404, not a silent 200 that deletes nothing (matches the CLI contract).
    if (!(await getMember(db, req.params.userId))) {
      return reply.code(404).send({ error: "member not found" });
    }
    await resetToDefaults(db, req.params.userId);
    // A reset wipes member_addon, including an enrolled member's kevbox addon — reapply it so the
    // reset never silently strips them off the box.
    if (kevbox) await reapplyKevboxAddon(db, req.params.userId, kevbox);
    return { ok: true };
  });
}
