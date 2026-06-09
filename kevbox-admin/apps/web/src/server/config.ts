export interface WebConfig {
  databaseUrl: string;
  supabaseUrl: string;
  supabaseAnonKey: string;
  adminEmails: string[];
  port: number;
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
  return {
    databaseUrl: required("SUPABASE_DB_URL"),
    supabaseUrl: required("SUPABASE_URL"),
    supabaseAnonKey: required("SUPABASE_ANON_KEY"),
    adminEmails,
    port,
  };
}
