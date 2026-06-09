import type { FastifyInstance } from "fastify";
import type { Db } from "@kevbox-admin/core";
import { getAccess, setActive, setMaxDevices, removeDevice, removeAllDevices, getMember } from "@kevbox-admin/core";

interface ActiveBody { active: boolean }
interface MaxDevicesBody { maxDevices: number }

export function registerAccessRoutes(app: FastifyInstance, db: Db): void {
  // Read a member's full access state (active flag, max_devices, devices).
  app.get<{ Params: { userId: string } }>(
    "/members/:userId/access",
    async (req, reply) => {
      // Member-existence guard: an unknown userId would otherwise read getAccess's fail-open
      // defaults (active=true / max=1) and masquerade as a real, enabled member.
      if (!(await getMember(db, req.params.userId))) {
        return reply.code(404).send({ error: "member not found" });
      }
      const access = await getAccess(db, req.params.userId);
      return { access };
    },
  );

  // Toggle the kill-switch.
  app.put<{ Params: { userId: string }; Body: ActiveBody }>(
    "/members/:userId/access/active",
    async (req, reply) => {
      const active = req.body?.active;
      if (typeof active !== "boolean") return reply.code(400).send({ error: "active must be boolean" });
      if (!(await getMember(db, req.params.userId))) {
        return reply.code(404).send({ error: "member not found" });
      }
      const access = await setActive(db, req.params.userId, active);
      return { access };
    },
  );

  // Set the per-member device cap (does NOT evict seated devices).
  app.put<{ Params: { userId: string }; Body: MaxDevicesBody }>(
    "/members/:userId/access/max-devices",
    async (req, reply) => {
      const maxDevices = req.body?.maxDevices;
      if (typeof maxDevices !== "number" || !Number.isInteger(maxDevices) || maxDevices < 1) {
        return reply.code(400).send({ error: "maxDevices must be an integer >= 1" });
      }
      if (!(await getMember(db, req.params.userId))) {
        return reply.code(404).send({ error: "member not found" });
      }
      const access = await setMaxDevices(db, req.params.userId, maxDevices);
      return { access };
    },
  );

  // Deauthorize a single device. deviceId is opaque TEXT — validate non-empty, do NOT Number-validate.
  app.delete<{ Params: { userId: string; deviceId: string } }>(
    "/members/:userId/devices/:deviceId",
    async (req, reply) => {
      const deviceId = req.params.deviceId;
      if (typeof deviceId !== "string" || deviceId.trim() === "") {
        return reply.code(400).send({ error: "invalid device id" });
      }
      if (!(await getMember(db, req.params.userId))) {
        return reply.code(404).send({ error: "member not found" });
      }
      // removeDevice throws a statusCode-404 error when zero rows are deleted; the app.ts error
      // handler maps err.statusCode → response, so a thrown 404 surfaces cleanly (anti-silent-success).
      await removeDevice(db, req.params.userId, deviceId);
      return { ok: true };
    },
  );

  // Deauthorize all of a member's devices.
  app.delete<{ Params: { userId: string } }>(
    "/members/:userId/devices",
    async (req, reply) => {
      if (!(await getMember(db, req.params.userId))) {
        return reply.code(404).send({ error: "member not found" });
      }
      await removeAllDevices(db, req.params.userId);
      return { ok: true };
    },
  );
}
