import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { renderMembersFile } from "@kevbox-admin/core";
import { loadConfig } from "./config.js";
import { createPool } from "./db.js";
import { supabaseVerifier } from "./auth.js";
import { buildApp } from "./app.js";

async function main(): Promise<void> {
  const cfg = loadConfig();
  const pool = createPool(cfg.databaseUrl);
  const verifier = supabaseVerifier(cfg.supabaseUrl, cfg.supabaseAnonKey);

  // The esbuild bundle is dist/server.js, so the built SPA (dist/public) is a SIBLING.
  const here = dirname(fileURLToPath(import.meta.url));
  const publicDir = join(here, "public");

  const app = buildApp({
    db: pool,
    verifier,
    adminEmails: cfg.adminEmails,
    publicDir,
    kevbox: cfg.kevbox,
  });

  app.addHook("onClose", async () => { await pool.end(); });

  // Boot-render (H3): reconcile members.json from the DB once at startup. Gated on ≥1 enrolled row so
  // the empty-set floor (Task 3) can't trip pre-migration; fail-soft so a render error never crashes boot.
  if (cfg.kevbox) {
    try {
      const { rows } = await pool.query<{ n: number }>(
        "select count(*)::int as n from public.kevbox_member where enrolled",
      );
      if (rows[0]!.n > 0) {
        await renderMembersFile(pool, cfg.kevbox.membersFile);
        app.log.info("kevbox: boot-render reconciled members.json");
      } else {
        app.log.info("kevbox: boot-render skipped (no enrolled members yet)");
      }
    } catch (err) {
      app.log.error({ err }, "kevbox: boot-render failed (continuing; next mutation will retry)");
    }
  }

  try {
    await app.listen({ port: cfg.port, host: "127.0.0.1" });
    // eslint-disable-next-line no-console
    console.log(`kevbox-admin web listening on http://127.0.0.1:${cfg.port}`);
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error(err);
    process.exit(1);
  }
}

void main();
