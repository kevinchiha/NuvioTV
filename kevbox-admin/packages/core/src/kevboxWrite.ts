import pg from "pg";
import type { Pool } from "pg";
import type { Db } from "./types.js";
import { renderMembersFile } from "./kevboxAllowlist.js";

/** Stable advisory-lock key for the single-writer guarantee (H2). */
const KEVBOX_LOCK_KEY = 4242042042;

/**
 * Run a kevbox mutation under the single-writer guarantee, then re-render members.json.
 *
 * Production (`db` is a pg.Pool — POSITIVE `db instanceof pg.Pool` check): check out a dedicated
 * client, BEGIN, take a TRANSACTION advisory lock (`pg_advisory_xact_lock` — auto-released when the
 * txn ends, so there is NO leaked-lock failure mode: a swallowed unlock can't return a still-locked
 * connection to the max:5 pool and block future kevbox writes, M9), run `fn`, then render the file
 * WHILE STILL INSIDE the txn (before COMMIT). Rendering before COMMIT means a render failure rolls
 * back the DB too (the file write and the row change fail together) — boot-render reconciles any
 * post-COMMIT drift on the next startup (H3). The xact lock is released automatically by COMMIT or
 * ROLLBACK; no explicit unlock, so no `.catch(()=>{})` leak path.
 *
 * Test/single-client (`db` is a rollback PoolClient — NOT a pg.Pool): run `fn` inline on the same
 * connection and render. We must NOT duck-type on `.connect`, because a pg PoolClient IS a
 * pg.Client and HAS `.connect` — duck-typing would route the test client down the prod path and
 * throw "Client has already been connected". No separate txn/lock (the caller's withRollback owns
 * the txn); the lock is a production-only concern, documented here as a decision.
 */
export async function withKevboxWrite<T>(
  db: Db,
  membersFile: string,
  fn: (db: Db) => Promise<T>,
): Promise<T> {
  if (!(db instanceof pg.Pool)) {
    const result = await fn(db);
    await renderMembersFile(db, membersFile);
    return result;
  }

  const client = await (db as Pool).connect();
  try {
    await client.query("BEGIN");
    try {
      // Transaction-scoped advisory lock: auto-released at COMMIT/ROLLBACK. No explicit unlock and
      // therefore no leaked-lock path (M9).
      await client.query("select pg_advisory_xact_lock($1)", [KEVBOX_LOCK_KEY]);
      const result = await fn(client);
      // Render INSIDE the txn (before COMMIT): a render failure rolls the DB back with it, so the
      // file and the row never diverge mid-write; boot-render reconciles on next boot (H3).
      await renderMembersFile(client, membersFile);
      await client.query("COMMIT");
      return result;
    } catch (err) {
      await client.query("ROLLBACK").catch(() => undefined);
      throw err;
    }
  } finally {
    // The xact lock is already gone (released by COMMIT/ROLLBACK) — just hand the client back.
    client.release();
  }
}
