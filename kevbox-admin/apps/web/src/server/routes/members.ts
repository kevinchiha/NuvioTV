import type { FastifyInstance } from "fastify";
import type { Db } from "@kevbox-admin/core";
import { listMembers, getMember } from "@kevbox-admin/core";

export function registerMemberRoutes(app: FastifyInstance, db: Db): void {
  app.get("/members", async () => {
    return { members: await listMembers(db) };
  });

  app.get<{ Params: { ref: string } }>("/members/:ref", async (req, reply) => {
    const member = await getMember(db, decodeURIComponent(req.params.ref));
    if (!member) return reply.code(404).send({ error: "member not found" });
    return { member };
  });
}
