import type { Db, AccessState } from "./types.js";
import { mapDeviceRow } from "./types.js";

/**
 * Access kill-switch + per-member device cap. Mirrors addons.ts: functions take `db: Db` first, run
 * parameterized db.query, throw validationError() (400) for bad input, return mapped objects.
 *
 * EVERY MUTATION IS A SINGLE ATOMIC SQL STATEMENT (upsert / scoped delete / delete-all). Production
 * `db` is a pg.Pool (autocommit per query — separate .query() calls run on different connections), so
 * a multi-statement delete-then-insert would re-introduce a crash window. Keep these single-statement.
 *
 * Concurrency is LAST-WRITE-WINS: the upserts set updated_at = now() with no precondition (no
 * compare-and-set), so two overlapping writes simply resolve to whichever lands last. Acceptable for
 * the single-operator admin scope; documented here so it is a decision, not an accident.
 */

/** A caller-input error. `statusCode` lets the web layer return 400 (not a generic 500). */
function validationError(message: string): Error {
  const e = new Error(message) as Error & { statusCode?: number };
  e.statusCode = 400;
  return e;
}

/** A not-found error. `statusCode` lets the web layer return 404 (not a generic 500). */
function notFound(message: string): Error {
  const e = new Error(message) as Error & { statusCode?: number };
  e.statusCode = 404;
  return e;
}

/**
 * Read a member's full access state. All three reads fail open to the deployed defaults when no row
 * exists (matching the client/RPC semantics): no member_access row => active=true; no
 * member_device_policy row => maxDevices=1; devices are returned newest-first by last_seen.
 */
export async function getAccess(db: Db, userId: string): Promise<AccessState> {
  const { rows: accessRows } = await db.query(
    "select active from public.member_access where user_id = $1",
    [userId],
  );
  const active = accessRows[0] ? Boolean(accessRows[0].active) : true;

  const { rows: policyRows } = await db.query(
    "select max_devices from public.member_device_policy where user_id = $1",
    [userId],
  );
  const maxDevices = policyRows[0] ? Number(policyRows[0].max_devices) : 1;

  const { rows: deviceRows } = await db.query(
    `select device_id, device_name, first_seen, last_seen
       from public.member_device where user_id = $1 order by last_seen desc`,
    [userId],
  );

  return { userId, active, maxDevices, devices: deviceRows.map(mapDeviceRow) };
}

/** Toggle the kill-switch. Single atomic upsert on member_access; returns the refreshed state. */
export async function setActive(db: Db, userId: string, active: boolean): Promise<AccessState> {
  await db.query(
    `insert into public.member_access (user_id, active, updated_at)
     values ($1, $2, now())
     on conflict (user_id)
       do update set active = excluded.active, updated_at = now()`,
    [userId, active],
  );
  return getAccess(db, userId);
}

/**
 * Set the per-member device cap. Single atomic upsert on member_device_policy; returns the refreshed
 * state. Does NOT evict excess devices: lowering the cap below the seated device count leaves the
 * existing member_device rows intact (claim_device refreshes an already-bound device before the count
 * check), so to actually reduce a member to N TVs the operator must also remove the excess rows.
 */
export async function setMaxDevices(db: Db, userId: string, max: number): Promise<AccessState> {
  if (!Number.isInteger(max) || max < 1) {
    throw validationError("maxDevices must be an integer >= 1");
  }
  await db.query(
    `insert into public.member_device_policy (user_id, max_devices, updated_at)
     values ($1, $2, now())
     on conflict (user_id)
       do update set max_devices = excluded.max_devices, updated_at = now()`,
    [userId, max],
  );
  return getAccess(db, userId);
}

/**
 * Deauthorize a single device. Scoped to BOTH user_id and device_id so a member can never delete
 * another member's device row. Throws notFound (404) when no matching row exists (no silent no-op).
 */
export async function removeDevice(db: Db, userId: string, deviceId: string): Promise<void> {
  const { rowCount } = await db.query(
    "delete from public.member_device where user_id = $1 and device_id = $2",
    [userId, deviceId],
  );
  if (rowCount === 0) {
    throw notFound(`no such device: ${deviceId}`);
  }
}

/** Deauthorize all of a member's devices. Scoped to user_id — only that member's rows are removed. */
export async function removeAllDevices(db: Db, userId: string): Promise<void> {
  await db.query("delete from public.member_device where user_id = $1", [userId]);
}
