import type { Db, KevboxConfig, KevboxState } from "./types.js";
import { encryptSecret, decryptSecret } from "./crypto.js";

const NAME_RE = /^[a-z0-9._+-]{1,64}$/;

function validationError(message: string): Error {
  const e = new Error(message) as Error & { statusCode?: number };
  e.statusCode = 400;
  return e;
}
function notFound(message: string): Error {
  const e = new Error(message) as Error & { statusCode?: number };
  e.statusCode = 404;
  return e;
}

/** Lowercased email local-part (the default aiostreams_name). "" when email is null. */
export function localPart(email: string | null): string {
  if (!email) return "";
  return email.split("@")[0]!.trim().toLowerCase();
}

function validateName(raw: string): string {
  const name = (raw ?? "").trim();
  if (!NAME_RE.test(name)) {
    throw validationError(`invalid aiostreams name: "${raw}" (must match ${NAME_RE})`);
  }
  return name;
}

/** Load a member's email or throw 404. */
async function memberEmail(db: Db, userId: string): Promise<string | null> {
  const { rows } = await db.query<{ email: string | null }>(
    "select email from public.kevbox_auth_users where id = $1",
    [userId],
  );
  if (rows.length === 0) throw notFound(`member not found: ${userId}`);
  return rows[0].email;
}

/** Throw if `name` is already used by a DIFFERENT enrolled member. */
async function assertActiveNameFree(db: Db, name: string, userId: string): Promise<void> {
  const { rows } = await db.query(
    "select 1 from public.kevbox_member where aiostreams_name = $1 and enrolled and user_id <> $2",
    [name, userId],
  );
  if (rows.length > 0) throw validationError(`aiostreams name already in use: ${name}`);
}

function installUrl(cfg: KevboxConfig, name: string, key: string): string {
  return `${cfg.streamsBaseUrl}/stremio/k/${name}/${key}/manifest.json`;
}

/** Delete this member's kevbox member_addon row(s) (URL prefix match on the streams base). */
async function deleteKevboxAddon(db: Db, userId: string, cfg: KevboxConfig): Promise<void> {
  await db.query("delete from public.member_addon where user_id = $1 and url like $2", [
    userId,
    `${cfg.streamsBaseUrl}/stremio/k/%`,
  ]);
}

/** Load the kevbox_member row (or null). */
async function loadRow(db: Db, userId: string) {
  const { rows } = await db.query<{
    aiostreams_name: string;
    premiumize_key_enc: string | null;
    enrolled: boolean;
  }>(
    "select aiostreams_name, premiumize_key_enc, enrolled from public.kevbox_member where user_id = $1",
    [userId],
  );
  return rows[0] ?? null;
}

/** Non-secret enrollment view (name/enrolled/hasKey), or null if never enrolled. */
export async function getKevbox(db: Db, userId: string, _cfg: KevboxConfig): Promise<KevboxState | null> {
  const row = await loadRow(db, userId);
  if (!row) return null;
  return { name: row.aiostreams_name, enrolled: row.enrolled, hasKey: row.premiumize_key_enc !== null };
}

/** Build the key-bearing install URL (decrypts the stored key). null if no key stored. */
export async function buildInstallUrl(db: Db, userId: string, cfg: KevboxConfig): Promise<string | null> {
  const row = await loadRow(db, userId);
  if (!row || row.premiumize_key_enc === null) return null;
  const key = decryptSecret(row.premiumize_key_enc, userId, cfg.encKey);
  return installUrl(cfg, row.aiostreams_name, key);
}

/** Enroll (or re-enroll) a member. Requires a Premiumize key. Returns the install URL + stored name. */
export async function enrollMember(
  db: Db,
  userId: string,
  opts: { aiostreamsName?: string; premiumizeKey: string },
  cfg: KevboxConfig,
): Promise<{ installUrl: string; name: string }> {
  const key = (opts.premiumizeKey ?? "").trim();
  if (!key) throw validationError("premiumizeKey is required");
  const email = await memberEmail(db, userId);
  const name = validateName(opts.aiostreamsName ?? localPart(email));
  await assertActiveNameFree(db, name, userId);

  const enc = encryptSecret(key, userId, cfg.encKey);
  await db.query(
    `insert into public.kevbox_member (user_id, aiostreams_name, premiumize_key_enc, enrolled, updated_at)
     values ($1, $2, $3, true, now())
     on conflict (user_id) do update
       set aiostreams_name = excluded.aiostreams_name,
           premiumize_key_enc = excluded.premiumize_key_enc,
           enrolled = true, updated_at = now()`,
    [userId, name, enc],
  );

  const url = installUrl(cfg, name, key);
  await deleteKevboxAddon(db, userId, cfg); // drop any stale kevbox row first (name/key may have changed)
  await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, true, $3)
     on conflict (user_id, url) do nothing`,
    [userId, url, cfg.addonSort],
  );
  return { installUrl: url, name };
}

/** Re-key an enrolled member; replaces the kevbox URL. Member must already have a row. */
export async function rotateKey(
  db: Db,
  userId: string,
  premiumizeKey: string,
  cfg: KevboxConfig,
): Promise<{ installUrl: string }> {
  const key = (premiumizeKey ?? "").trim();
  if (!key) throw validationError("premiumizeKey is required");
  const row = await loadRow(db, userId);
  if (!row) throw notFound(`member is not enrolled: ${userId}`);
  const enc = encryptSecret(key, userId, cfg.encKey);
  await db.query(
    "update public.kevbox_member set premiumize_key_enc = $2, updated_at = now() where user_id = $1",
    [userId, enc],
  );
  const url = installUrl(cfg, row.aiostreams_name, key);
  await deleteKevboxAddon(db, userId, cfg);
  await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, true, $3) on conflict (user_id, url) do nothing`,
    [userId, url, cfg.addonSort],
  );
  return { installUrl: url };
}

/** Rename an enrolled member (name is in the URL path). Rebuilds the URL when a key is stored. */
export async function renameMember(
  db: Db,
  userId: string,
  newName: string,
  cfg: KevboxConfig,
): Promise<{ installUrl: string | null; keyless: boolean }> {
  const name = validateName(newName);
  const row = await loadRow(db, userId);
  if (!row) throw notFound(`member is not enrolled: ${userId}`);
  await assertActiveNameFree(db, name, userId);
  await db.query(
    "update public.kevbox_member set aiostreams_name = $2, updated_at = now() where user_id = $1",
    [userId, name],
  );
  if (row.premiumize_key_enc === null) {
    await deleteKevboxAddon(db, userId, cfg); // no key → can't rebuild; drop the stale URL
    return { installUrl: null, keyless: true };
  }
  const key = decryptSecret(row.premiumize_key_enc, userId, cfg.encKey);
  const url = installUrl(cfg, name, key);
  await deleteKevboxAddon(db, userId, cfg);
  await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, true, $3) on conflict (user_id, url) do nothing`,
    [userId, url, cfg.addonSort],
  );
  return { installUrl: url, keyless: false };
}

/** Un-enroll: flip enrolled=false, drop the kevbox URL. Encrypted key retained (default, §8). */
export async function unenrollMember(db: Db, userId: string, cfg: KevboxConfig): Promise<void> {
  const row = await loadRow(db, userId);
  if (!row) throw notFound(`member is not enrolled: ${userId}`);
  await db.query(
    "update public.kevbox_member set enrolled = false, updated_at = now() where user_id = $1",
    [userId],
  );
  await deleteKevboxAddon(db, userId, cfg);
}

/**
 * Re-add the kevbox member_addon URL after a reset-to-defaults wiped it. No-op when the member is
 * not enrolled or has no stored key. Rebuilds `/stremio/k/<name>/<key>/manifest.json` from the
 * stored (decrypted) key so a Reset never silently strips an enrolled member's kevbox addon.
 */
export async function reapplyKevboxAddon(db: Db, userId: string, cfg: KevboxConfig): Promise<void> {
  const row = await loadRow(db, userId);
  if (!row || !row.enrolled || row.premiumize_key_enc === null) return;
  const key = decryptSecret(row.premiumize_key_enc, userId, cfg.encKey);
  const url = installUrl(cfg, row.aiostreams_name, key);
  await deleteKevboxAddon(db, userId, cfg);
  await db.query(
    `insert into public.member_addon (user_id, url, enabled, sort_order)
     values ($1, $2, true, $3) on conflict (user_id, url) do nothing`,
    [userId, url, cfg.addonSort],
  );
}
