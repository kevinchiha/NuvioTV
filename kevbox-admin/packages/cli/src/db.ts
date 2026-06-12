import { readFileSync } from "node:fs";
import pg from "pg";
import type { Db } from "@kevbox-admin/core";
import { loadEncKey, type KevboxConfig } from "@kevbox-admin/core";

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

/**
 * Create a pg Pool from the resolved connection string. Caller owns pool.end().
 *
 * SSL: same Supabase-pooler handling as the web server — strip `sslmode` and set `ssl` explicitly
 * (encrypted, chain not verified) for Supabase/sslmode URLs, so the operator can point the CLI at
 * the pooler without the "self-signed certificate in certificate chain" failure; a plain local
 * docker URL gets no ssl.
 */
export function createPool(localPropertiesPath?: string): pg.Pool {
  const url = new URL(resolveDbUrl(localPropertiesPath));
  const wantsSsl = url.searchParams.has("sslmode") || /supabase\.(co|com)$/.test(url.hostname);
  url.searchParams.delete("sslmode");
  return new Pool({
    connectionString: url.toString(),
    ssl: wantsSsl ? { rejectUnauthorized: false } : undefined,
  });
}

/** Build the KevboxConfig from env (KEVBOX_ENC_KEY + KEVBOX_MEMBERS_FILE required). */
export function resolveKevboxConfig(): KevboxConfig {
  const encRaw = process.env.KEVBOX_ENC_KEY?.trim();
  const file = process.env.KEVBOX_MEMBERS_FILE?.trim();
  if (!encRaw) throw new Error("KEVBOX_ENC_KEY is required for kevbox commands");
  if (!file) throw new Error("KEVBOX_MEMBERS_FILE is required for kevbox commands");
  const addonSort = Number.parseInt(process.env.KEVBOX_ADDON_SORT?.trim() || "4", 10);
  // Mirror the existing PORT int-validation: a NaN here fails the int-NOT-NULL member_addon insert
  // and 500s every enroll/rotate/rename, so reject it before any command runs.
  if (!Number.isInteger(addonSort)) throw new Error("KEVBOX_ADDON_SORT must be an integer");
  return {
    encKey: loadEncKey(encRaw),
    membersFile: file,
    streamsBaseUrl: (process.env.KEVBOX_STREAMS_BASE_URL?.trim() || "https://streams.kevbox.dev").replace(/\/$/, ""),
    addonSort,
  };
}

/** Re-export for callers that only need the injected-query type. */
export type { Db };
