import type { FastifyInstance } from "fastify";
import type { Db } from "@kevbox-admin/core";
import { getMemberActivity, listMembersByActivity, listGoingDark, getFleetStats, getMember, pruneTelemetry } from "@kevbox-admin/core";

// Clamp an untrusted ?limit= to [1,100] (default 25). Guards NaN/negative from reaching SQL LIMIT.
function clampLimit(raw?: string): number {
  const n = Number(raw ?? 25);
  return Number.isFinite(n) ? Math.min(Math.max(Math.trunc(n), 1), 100) : 25;
}

export function registerActivityRoutes(app: FastifyInstance, db: Db): void {
  app.get<{ Params: { userId: string } }>("/members/:userId/activity", async (req, reply) => {
    if (!(await getMember(db, req.params.userId))) return reply.code(404).send({ error: "member not found" });
    return { activity: await getMemberActivity(db, req.params.userId) };
  });

  app.get<{ Querystring: { window?: "today" | "7d" | "30d"; order?: "most" | "least"; limit?: string } }>(
    "/activity/leaderboard", async (req) => ({
      rows: await listMembersByActivity(db, {
        window: req.query.window ?? "7d",
        order: req.query.order ?? "most",
        limit: clampLimit(req.query.limit),
      }),
    }),
  );

  app.get("/activity/going-dark", async () => ({ rows: await listGoingDark(db, { days: 14 }) }));
  app.get("/activity/stats", async () => ({ stats: await getFleetStats(db) }));

  // Operator-initiated manual prune. Sits behind the `api` scope's requireAdmin, so it needs an
  // admin JWT and is NOT the headless/unattended path (L7) — the in-process daily timer in app.ts is.
  app.post("/activity/prune", async () => ({ result: await pruneTelemetry(db) }));
}
