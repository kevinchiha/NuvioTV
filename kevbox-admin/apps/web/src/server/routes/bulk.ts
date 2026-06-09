import type { FastifyInstance } from "fastify";
import type { Db } from "@kevbox-admin/core";
import { bulkAddAddon, bulkSwapUrl, snapshotAllAddons } from "@kevbox-admin/core";

interface BulkAddBody { url: string; sortOrder: number; confirm: boolean }
interface BulkSwapBody { fromUrl: string; toUrl: string; confirm: boolean }

export function registerBulkRoutes(app: FastifyInstance, db: Db): void {
  // Add a URL to EVERY member. confirm must be true.
  app.post<{ Body: BulkAddBody }>("/bulk/add", async (req, reply) => {
    const { url, sortOrder, confirm } = req.body ?? ({} as BulkAddBody);
    if (typeof url !== "string" || url.trim() === "") {
      return reply.code(400).send({ error: "url is required" });
    }
    if (!Number.isInteger(sortOrder)) {
      return reply.code(400).send({ error: "sortOrder must be an integer" });
    }
    if (confirm !== true) {
      return reply.code(400).send({ error: "confirm must be true" });
    }
    // Snapshot-before-bulk (spec §4.6, v1): capture the pre-image and return it so the SPA can
    // offer it as a downloadable JSON backstop before the destructive change is applied widely.
    const snapshot = await snapshotAllAddons(db);
    const inserted = await bulkAddAddon(db, { url, sortOrder }, true);
    return { inserted, snapshot };
  });

  // Swap fromUrl -> toUrl across ALL members. confirm must be true.
  app.post<{ Body: BulkSwapBody }>("/bulk/swap", async (req, reply) => {
    const { fromUrl, toUrl, confirm } = req.body ?? ({} as BulkSwapBody);
    if (typeof fromUrl !== "string" || fromUrl.trim() === "") {
      return reply.code(400).send({ error: "fromUrl is required" });
    }
    if (typeof toUrl !== "string" || toUrl.trim() === "") {
      return reply.code(400).send({ error: "toUrl is required" });
    }
    if (confirm !== true) {
      return reply.code(400).send({ error: "confirm must be true" });
    }
    const snapshot = await snapshotAllAddons(db); // pre-image backstop (see /bulk/add)
    await bulkSwapUrl(db, { fromUrl, toUrl }, true);
    return { ok: true, snapshot };
  });
}
