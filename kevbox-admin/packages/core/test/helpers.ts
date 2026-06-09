import pg from "pg";
import type { Db } from "../src/types.js";

const { Pool } = pg;

const TEST_DATABASE_URL =
  process.env.TEST_DATABASE_URL ??
  "postgres://postgres:test@localhost:5433/kevbox_test";

export const pool = new Pool({ connectionString: TEST_DATABASE_URL });

/** Run `fn` inside a transaction that ALWAYS rolls back — keeps tests isolated and side-effect free. */
export async function withRollback<T>(fn: (db: Db) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    return await fn(client);
  } finally {
    await client.query("ROLLBACK");
    client.release();
  }
}

/** Insert a throwaway member into the test auth.users; returns its userId. */
export async function createTestMember(db: Db, email: string): Promise<string> {
  const { rows } = await db.query<{ id: string }>(
    "insert into auth.users (email) values ($1) returning id",
    [email],
  );
  return rows[0].id;
}
