import Fastify, { type FastifyInstance } from "fastify";
import fastifyStatic from "@fastify/static";
import type { Db } from "@kevbox-admin/core";
import { requireAdmin, type Verifier } from "./auth.js";
import { registerMemberRoutes } from "./routes/members.js";
import { registerAddonRoutes } from "./routes/addons.js";
import { registerAccessRoutes } from "./routes/access.js";
import { registerActionRoutes } from "./routes/actions.js";
import { registerBulkRoutes } from "./routes/bulk.js";

export interface BuildAppOptions {
  db: Db;
  verifier: Verifier;
  adminEmails: string[];
  /** Absolute path to the built SPA (dist/public). Omit in tests to skip static serving. */
  publicDir?: string;
}

/**
 * Build the Fastify app. Everything is dependency-injected so tests can supply a
 * rollback-scoped `db` and a fake `verifier`. Production passes the real pool + Supabase verifier.
 */
export function buildApp(opts: BuildAppOptions): FastifyInstance {
  // logger ON → request/error lines go to stdout → journald (the deploy troubleshooting steps
  // rely on `journalctl -u kevbox-admin`). bodyLimit caps abuse (admin payloads are tiny JSON).
  // trustProxy: the only ingress is nginx on loopback, so trust its X-Forwarded-* headers.
  // In tests (no publicDir) keep the logger quiet.
  const app = Fastify({
    logger: opts.publicDir ? { level: "info" } : false,
    bodyLimit: 256 * 1024,
    trustProxy: true,
  });

  // Never leak internals: pg errors embed the query, role, and host. 4xx (validation) messages
  // are operator-facing and safe; 5xx collapse to a generic message (full error still logged).
  app.setErrorHandler((err, req, reply) => {
    req.log.error(err);
    const status = err.statusCode && err.statusCode >= 400 ? err.statusCode : 500;
    reply.code(status).send({ error: status < 500 ? err.message : "internal error" });
  });

  // Liveness + DB readiness (UNAUTHENTICATED, no secrets). deploy.sh smoke-checks this so a
  // green deploy proves the server reached Postgres — a static "/" check would pass even with
  // a wrong/unreachable SUPABASE_DB_URL. Rate-limiting for the public surface is handled at
  // nginx (limit_req); the SPA's login hits Supabase directly, which has its own throttling.
  app.get("/healthz", async (_req, reply) => {
    try {
      await opts.db.query("select 1");
      return { ok: true };
    } catch {
      return reply.code(503).send({ ok: false });
    }
  });

  // All /api/* routes are gated by requireAdmin. Encapsulated in a plugin so the
  // pre-handler only applies to this subtree (static SPA + /healthz stay public).
  app.register(async (api) => {
    api.addHook("preHandler", requireAdmin(opts.verifier, opts.adminEmails));
    registerMemberRoutes(api, opts.db);
    registerAddonRoutes(api, opts.db);
    registerAccessRoutes(api, opts.db);
    registerActionRoutes(api, opts.db);
    registerBulkRoutes(api, opts.db);
  }, { prefix: "/api" });

  // Serve the built SPA (production only). SPA fallback: any non-/api GET → index.html.
  if (opts.publicDir) {
    app.register(fastifyStatic, { root: opts.publicDir });
    app.setNotFoundHandler((req, reply) => {
      if (req.method === "GET" && !req.url.startsWith("/api")) {
        return reply.sendFile("index.html");
      }
      return reply.code(404).send({ error: "not found" });
    });
  }

  return app;
}
