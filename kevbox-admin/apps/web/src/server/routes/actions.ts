import type { FastifyInstance } from "fastify";
import type { Db } from "@kevbox-admin/core";
import { resetToDefaults, onboardDebrid, getMember } from "@kevbox-admin/core";

interface DebridBody { premiumizeKey: string; aiostreamsUrl: string }

export function registerActionRoutes(app: FastifyInstance, db: Db): void {
  // Reset a member to the baked-in universal defaults.
  app.post<{ Params: { userId: string } }>("/members/:userId/reset", async (req, reply) => {
    // Unknown member → 404, not a silent 200 that deletes nothing (matches the CLI contract).
    if (!(await getMember(db, req.params.userId))) {
      return reply.code(404).send({ error: "member not found" });
    }
    await resetToDefaults(db, req.params.userId);
    return { ok: true };
  });

  // Guided debrid onboarding: insert Torrentio (sort 4) + AIOStreams (sort 5).
  app.post<{ Params: { userId: string }; Body: DebridBody }>(
    "/members/:userId/debrid",
    async (req, reply) => {
      const { premiumizeKey, aiostreamsUrl } = req.body ?? ({} as DebridBody);
      if (typeof premiumizeKey !== "string" || premiumizeKey.trim() === "") {
        return reply.code(400).send({ error: "premiumizeKey is required" });
      }
      if (typeof aiostreamsUrl !== "string" || aiostreamsUrl.trim() === "") {
        return reply.code(400).send({ error: "aiostreamsUrl is required" });
      }
      if (!(await getMember(db, req.params.userId))) {
        return reply.code(404).send({ error: "member not found" });
      }
      await onboardDebrid(db, req.params.userId, { premiumizeKey, aiostreamsUrl });
      return reply.code(201).send({ ok: true });
    },
  );
}
