import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
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
  });

  app.addHook("onClose", async () => { await pool.end(); });

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
