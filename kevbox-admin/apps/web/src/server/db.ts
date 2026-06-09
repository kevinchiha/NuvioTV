import pg from "pg";

const { Pool } = pg;

/**
 * Build the single server-side pg Pool from SUPABASE_DB_URL. Never used in the browser.
 *
 * SSL: Supabase's pooler presents a cert chained to its private "Supabase … CA", which is not in
 * the system trust store; newer `pg` treats `sslmode=require` as verify-full and rejects the chain
 * ("self-signed certificate in certificate chain"). We strip `sslmode` from the URL and set `ssl`
 * explicitly — the hop stays TLS-encrypted, we just don't verify the chain (fine for a
 * server→Supabase-pooler connection; to harden, pin Supabase's CA via `ssl.ca`). A non-Supabase,
 * non-sslmode URL (e.g. a local docker DB) gets no ssl, so plain connections still work.
 */
export function createPool(connectionString: string): pg.Pool {
  const url = new URL(connectionString);
  const wantsSsl = url.searchParams.has("sslmode") || /supabase\.(co|com)$/.test(url.hostname);
  url.searchParams.delete("sslmode");
  return new Pool({
    connectionString: url.toString(),
    max: 5,
    ssl: wantsSsl ? { rejectUnauthorized: false } : undefined,
  });
}
