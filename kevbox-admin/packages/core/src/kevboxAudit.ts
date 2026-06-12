import type { Db } from "./types.js";

export interface AuditEntry {
  adminEmail: string | null;
  userId: string | null;
  action: string; // e.g. "kevbox.enroll", "kevbox.rename", "kevbox.rotate", "kevbox.unenroll", "kevbox.reveal-url"
}

/**
 * Append one audit row. NEVER pass a secret (key/URL) — only the action verb is recorded. Best-effort
 * within the caller's transaction; if it's part of withKevboxWrite it commits/rolls back atomically
 * with the mutation.
 */
export async function writeAudit(db: Db, entry: AuditEntry): Promise<void> {
  await db.query(
    "insert into public.kevbox_audit (admin_email, user_id, action) values ($1, $2, $3)",
    [entry.adminEmail, entry.userId, entry.action],
  );
}
