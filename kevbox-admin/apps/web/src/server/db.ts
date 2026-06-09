import pg from "pg";

const { Pool } = pg;

/** Build the single server-side pg Pool from SUPABASE_DB_URL. Never used in the browser. */
export function createPool(connectionString: string): pg.Pool {
  return new Pool({ connectionString, max: 5 });
}
