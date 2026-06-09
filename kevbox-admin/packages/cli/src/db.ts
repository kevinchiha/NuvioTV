import { readFileSync } from "node:fs";
import pg from "pg";
import type { Db } from "@kevbox-admin/core";

const { Pool } = pg;

/** Default location of the NuvioTV Android project's local.properties (holds SUPABASE_DB_URL). */
export const DEFAULT_LOCAL_PROPERTIES = "/home/kevin/projects/NuvioTV/local.properties";

/** Read the `SUPABASE_DB_URL=...` line out of a local.properties file, or null if absent/unreadable. */
function readFromLocalProperties(path: string): string | null {
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch {
    return null;
  }
  for (const line of text.split(/\r?\n/)) {
    const eq = line.indexOf("=");
    if (eq === -1) continue;
    const key = line.slice(0, eq).trim();
    if (key !== "SUPABASE_DB_URL") continue;
    let value = line.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    return value || null;
  }
  return null;
}

/**
 * Resolve the Postgres connection string. Precedence:
 *   1. SUPABASE_DB_URL environment variable
 *   2. SUPABASE_DB_URL line in local.properties (NuvioTV Android project)
 * Throws if neither is present — the DB URL is the CLI's only credential.
 */
export function resolveDbUrl(localPropertiesPath: string = DEFAULT_LOCAL_PROPERTIES): string {
  const fromEnv = process.env.SUPABASE_DB_URL?.trim();
  if (fromEnv) return fromEnv;
  const fromProps = readFromLocalProperties(localPropertiesPath);
  if (fromProps) return fromProps;
  throw new Error(
    `SUPABASE_DB_URL not found. Set the SUPABASE_DB_URL env var, or add a ` +
      `SUPABASE_DB_URL=... line to ${localPropertiesPath}.`,
  );
}

/** Create a pg Pool from the resolved connection string. Caller owns pool.end(). */
export function createPool(localPropertiesPath?: string): pg.Pool {
  return new Pool({ connectionString: resolveDbUrl(localPropertiesPath) });
}

/** Re-export for callers that only need the injected-query type. */
export type { Db };
