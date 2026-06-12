import type { FastifyInstance } from "fastify";
import type { Db, KevboxConfig } from "@kevbox-admin/core";
import { listMembers, getMember, getKevbox } from "@kevbox-admin/core";

export function registerMemberRoutes(app: FastifyInstance, db: Db, kevbox?: KevboxConfig): void {
  app.get("/members", async () => {
    return { members: await listMembers(db) };
  });

  app.get<{ Params: { ref: string } }>("/members/:ref", async (req, reply) => {
    const member = await getMember(db, decodeURIComponent(req.params.ref));
    if (!member) return reply.code(404).send({ error: "member not found" });
    // Attach the non-secret kevbox block (name/enrolled/hasKey) — NEVER the install URL (C5).
    const kev = kevbox ? await getKevbox(db, member.userId, kevbox) : null;
    // The kevbox addon is stored as a member_addon row whose URL embeds the Premiumize key
    // ({base}/stremio/k/{name}/{KEY}/manifest.json). The default member fetch must NEVER leak that
    // key (C5) — only GET …/kevbox/install-url may reveal it. Censor the key segment here.
    const addons = kevbox ? member.addons.map((a) => redactKevboxKey(a, kevbox)) : member.addons;
    return { member: { ...member, addons, kevbox: kev } };
  });
}

/**
 * If `addon.url` is a kevbox install URL ({base}/stremio/k/{name}/{key}/manifest.json), replace the
 * key segment with "[redacted]" so the default member fetch never exposes the Premiumize key (C5).
 * Non-kevbox addons pass through unchanged.
 */
function redactKevboxKey<T extends { url: string }>(addon: T, kevbox: KevboxConfig): T {
  const prefix = `${kevbox.streamsBaseUrl}/stremio/k/`;
  if (!addon.url.startsWith(prefix)) return addon;
  const rest = addon.url.slice(prefix.length); // {name}/{key}/manifest.json
  const censored = `${prefix}${rest.replace(/^([^/]+)\/[^/]+\//, "$1/[redacted]/")}`;
  return { ...addon, url: censored };
}
