import type { FastifyInstance } from "fastify";
import type { Db } from "@kevbox-admin/core";
import { addAddon, updateAddon, setEnabled, reorder, deleteAddon, getMember } from "@kevbox-admin/core";

interface AddBody { url: string; enabled?: boolean; sortOrder?: number }
interface UpdateBody { url?: string; enabled?: boolean }
interface EnabledBody { enabled: boolean }
interface ReorderBody { orderedIds: number[] }

export function registerAddonRoutes(app: FastifyInstance, db: Db): void {
  // Add an addon to one member.
  app.post<{ Params: { userId: string }; Body: AddBody }>(
    "/members/:userId/addons",
    async (req, reply) => {
      const { url, enabled, sortOrder } = req.body ?? ({} as AddBody);
      if (typeof url !== "string" || url.trim() === "") {
        return reply.code(400).send({ error: "url is required" });
      }
      // Member-existence guard: match the CLI's not-found contract (clean 404) instead of
      // letting an unknown userId surface as a 500 (member_addon.user_id FK violation).
      if (!(await getMember(db, req.params.userId))) {
        return reply.code(404).send({ error: "member not found" });
      }
      const row = await addAddon(db, req.params.userId, { url, enabled, sortOrder });
      return reply.code(201).send({ addon: row });
    },
  );

  // Edit an addon's url and/or enabled.
  app.patch<{ Params: { addonId: string }; Body: UpdateBody }>(
    "/addons/:addonId",
    async (req, reply) => {
      const id = Number(req.params.addonId);
      if (!Number.isInteger(id)) return reply.code(400).send({ error: "invalid addon id" });
      const { url, enabled } = req.body ?? ({} as UpdateBody);
      const row = await updateAddon(db, id, { url, enabled });
      if (!row) return reply.code(404).send({ error: "addon not found" });
      return { addon: row };
    },
  );

  // Toggle enabled.
  app.put<{ Params: { addonId: string }; Body: EnabledBody }>(
    "/addons/:addonId/enabled",
    async (req, reply) => {
      const id = Number(req.params.addonId);
      if (!Number.isInteger(id)) return reply.code(400).send({ error: "invalid addon id" });
      const enabled = req.body?.enabled;
      if (typeof enabled !== "boolean") return reply.code(400).send({ error: "enabled must be boolean" });
      const row = await setEnabled(db, id, enabled);
      if (!row) return reply.code(404).send({ error: "addon not found" });
      return { addon: row };
    },
  );

  // Reorder a member's addons.
  app.put<{ Params: { userId: string }; Body: ReorderBody }>(
    "/members/:userId/addons/order",
    async (req, reply) => {
      const ids = req.body?.orderedIds;
      if (!Array.isArray(ids) || !ids.every((n) => Number.isInteger(n))) {
        return reply.code(400).send({ error: "orderedIds must be an array of integers" });
      }
      // Unknown member → 404 (not a silently-successful 200 over zero rows).
      if (!(await getMember(db, req.params.userId))) {
        return reply.code(404).send({ error: "member not found" });
      }
      await reorder(db, req.params.userId, ids);
      return { ok: true };
    },
  );

  // Delete an addon.
  app.delete<{ Params: { addonId: string } }>("/addons/:addonId", async (req, reply) => {
    const id = Number(req.params.addonId);
    if (!Number.isInteger(id)) return reply.code(400).send({ error: "invalid addon id" });
    await deleteAddon(db, id);
    return { ok: true };
  });
}
