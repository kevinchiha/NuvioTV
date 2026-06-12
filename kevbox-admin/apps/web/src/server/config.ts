import { loadEncKey } from "@kevbox-admin/core";

export interface WebConfig {
  databaseUrl: string;
  supabaseUrl: string;
  supabaseAnonKey: string;
  adminEmails: string[];
  port: number;
  kevbox?: {
    encKey: Buffer;
    membersFile: string;
    streamsBaseUrl: string;
    addonSort: number;
  };
}

function required(name: string): string {
  const v = process.env[name];
  if (v === undefined || v.trim() === "") {
    throw new Error(`Missing required env var: ${name}`);
  }
  return v.trim();
}

/** Read & validate the server environment. Throws (fail-fast) on any missing required var. */
export function loadConfig(): WebConfig {
  const adminEmails = required("ADMIN_EMAILS")
    .split(",")
    .map((e) => e.trim().toLowerCase())
    .filter((e) => e.length > 0);
  if (adminEmails.length === 0) {
    throw new Error("ADMIN_EMAILS must contain at least one email");
  }
  const portRaw = process.env.PORT?.trim();
  const port = portRaw ? Number.parseInt(portRaw, 10) : 8787;
  if (!Number.isInteger(port) || port <= 0) {
    throw new Error(`PORT must be a positive integer, got: ${portRaw}`);
  }

  // Optional: only build the kevbox block when BOTH key + members-file are set. If exactly one is
  // set, the operator clearly intended kevbox — throw so a half-configured deploy fails loud.
  const encRaw = process.env.KEVBOX_ENC_KEY?.trim();
  const membersFile = process.env.KEVBOX_MEMBERS_FILE?.trim();
  let kevbox: WebConfig["kevbox"];
  if (encRaw || membersFile) {
    if (!encRaw || !membersFile) {
      throw new Error("KEVBOX_ENC_KEY and KEVBOX_MEMBERS_FILE must be set together (or both omitted)");
    }
    const addonSort = Number.parseInt(process.env.KEVBOX_ADDON_SORT?.trim() || "4", 10);
    // Mirror the existing PORT int-validation: a NaN here fails the int-NOT-NULL member_addon
    // insert and 500s every enroll/rotate/rename, so reject it at config load.
    if (!Number.isInteger(addonSort)) {
      throw new Error("KEVBOX_ADDON_SORT must be an integer");
    }
    kevbox = {
      encKey: loadEncKey(encRaw),
      membersFile,
      streamsBaseUrl: (process.env.KEVBOX_STREAMS_BASE_URL?.trim() || "https://streams.kevbox.dev").replace(/\/$/, ""),
      addonSort,
    };
  }

  return {
    databaseUrl: required("SUPABASE_DB_URL"),
    supabaseUrl: required("SUPABASE_URL"),
    supabaseAnonKey: required("SUPABASE_ANON_KEY"),
    adminEmails,
    port,
    kevbox,
  };
}
