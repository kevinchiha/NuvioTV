import pg from "pg";

const { Pool } = pg;

const TEST_DATABASE_URL =
  process.env.TEST_DATABASE_URL ??
  "postgres://postgres:test@localhost:5433/kevbox_test";

/** Shared pool for all CLI integration tests (Plan 1 docker test-db on :5433). */
export const pool = new Pool({ connectionString: TEST_DATABASE_URL });

/** Insert a throwaway member; returns its userId. */
export async function seedMember(email: string): Promise<string> {
  const { rows } = await pool.query<{ id: string }>(
    "insert into auth.users (email) values ($1) returning id",
    [email],
  );
  return rows[0].id;
}

/**
 * Delete every test member whose email ends with the given suffix. ON DELETE CASCADE
 * removes their member_addon rows too. Call in afterEach/afterAll to isolate tests.
 */
export async function cleanup(emailSuffix: string): Promise<void> {
  await pool.query("delete from auth.users where email like $1", [`%${emailSuffix}`]);
}
