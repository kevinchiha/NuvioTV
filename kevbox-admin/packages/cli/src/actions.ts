import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import type { Db, KevboxConfig } from "@kevbox-admin/core";
import {
  listMembers,
  getMember,
  addAddon,
  updateAddon,
  setEnabled,
  reorder,
  resetToDefaults,
  onboardDebrid,
  bulkAddAddon,
  bulkSwapUrl,
  snapshotAllAddons,
  getAccess,
  setActive,
  setMaxDevices,
  removeDevice,
  removeAllDevices,
  enrollMember,
  rotateKey,
  renameMember,
  unenrollMember,
  buildInstallUrl,
  withKevboxWrite,
  migrate261,
} from "@kevbox-admin/core";
import { formatMembers, formatMember, formatAddon, formatAccess } from "./format.js";
import type { Ask } from "./prompt.js";
import { confirm } from "./prompt.js";

/** Output sink so tests can capture without touching the real console. */
export interface Sink {
  log: (s: string) => void;
  err: (s: string) => void;
}

/** Default sink: prints to stdout/stderr. */
export const consoleSink: Sink = {
  log: (s) => console.log(s),
  err: (s) => console.error(s),
};

/**
 * Snapshot-before-bulk (spec §4.6, v1): dump all member_addon rows to a timestamped JSON file
 * BEFORE a destructive bulk op so a wrong swap/add is recoverable. Written to KEVBOX_SNAPSHOT_DIR
 * (default: the OS temp dir, always writable and never litters the repo); the absolute path is printed.
 */
async function writeBulkSnapshot(db: Db, label: string, sink: Sink): Promise<void> {
  const rows = await snapshotAllAddons(db);
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const dir = process.env.KEVBOX_SNAPSHOT_DIR ?? tmpdir();
  const file = join(dir, `kevbox-admin-snapshot-${label}-${stamp}.json`);
  writeFileSync(file, JSON.stringify(rows, null, 2));
  sink.log(`Snapshot (${rows.length} rows) saved to ${file} — re-import this if the bulk op was wrong.`);
}

/** Resolve a member by email|userId or print "not found" and return null. */
async function requireMember(db: Db, ref: string, sink: Sink) {
  const member = await getMember(db, ref);
  if (!member) {
    sink.err(`Member not found: ${ref}`);
    return null;
  }
  return member;
}

/** kevbox-admin list */
export async function actionList(db: Db, sink: Sink = consoleSink): Promise<void> {
  const members = await listMembers(db);
  sink.log(formatMembers(members));
}

/** kevbox-admin show <ref> */
export async function actionShow(db: Db, ref: string, sink: Sink = consoleSink): Promise<void> {
  const member = await getMember(db, ref);
  if (!member) {
    sink.err(`Member not found: ${ref}`);
    return;
  }
  sink.log(formatMember(member));
}

/** kevbox-admin add <ref> <url> [--disabled] [--sort N] */
export async function actionAdd(
  db: Db,
  ref: string,
  url: string,
  opts: { disabled?: boolean; sort?: number },
  sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const row = await addAddon(db, member.userId, {
    url,
    enabled: opts.disabled ? false : true,
    sortOrder: opts.sort,
  });
  sink.log(`Added: ${formatAddon(row)}`);
}

/** kevbox-admin update <addonId> [--url U] [--enable|--disable] */
export async function actionUpdate(
  db: Db,
  addonId: number,
  opts: { url?: string; enable?: boolean; disable?: boolean },
  sink: Sink = consoleSink,
): Promise<void> {
  let enabled: boolean | undefined;
  if (opts.enable) enabled = true;
  if (opts.disable) enabled = false;
  const row = await updateAddon(db, addonId, { url: opts.url, enabled });
  if (!row) {
    sink.err(`Addon not found: #${addonId}`);
    return;
  }
  sink.log(`Updated: ${formatAddon(row)}`);
}

/** kevbox-admin toggle <addonId> */
export async function actionToggle(db: Db, addonId: number, sink: Sink = consoleSink): Promise<void> {
  const current = await getAddon(db, addonId);
  if (!current) {
    sink.err(`Addon not found: #${addonId}`);
    return;
  }
  const row = await setEnabled(db, addonId, !current.enabled);
  sink.log(`Toggled: ${formatAddon(row!)}`);
}

/** Look up a single addon's current enabled state (for toggle). */
async function getAddon(db: Db, addonId: number): Promise<{ enabled: boolean } | null> {
  const { rows } = await db.query<{ enabled: boolean }>(
    "select enabled from public.member_addon where id = $1",
    [addonId],
  );
  return rows[0] ?? null;
}

/** kevbox-admin reorder <ref> — interactive: print current order, read new order, apply. */
export async function actionReorder(
  db: Db,
  ref: string,
  ask: Ask,
  sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  if (member.addons.length === 0) {
    sink.log("(no addons to reorder)");
    return;
  }
  sink.log("Current order:");
  sink.log(formatMember(member));
  const answer = await ask(
    "Enter the addon IDs in the desired order (space or comma separated): ",
  );
  const orderedIds = answer
    .split(/[\s,]+/)
    .filter((s) => s.length > 0)
    .map((s) => Number(s));
  const valid = new Set(member.addons.map((a) => a.id));
  if (orderedIds.length !== member.addons.length || orderedIds.some((id) => !valid.has(id))) {
    sink.err("Aborted: the IDs you entered do not match this member's addons exactly.");
    return;
  }
  await reorder(db, member.userId, orderedIds);
  const updated = await getMember(db, member.userId);
  sink.log("New order:");
  sink.log(formatMember(updated!));
}

/** kevbox-admin reset <ref> — confirm, then re-seed defaults. */
export async function actionReset(
  db: Db,
  ref: string,
  ask: Ask,
  sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const ok = await confirm(
    ask,
    `Reset ${member.email ?? member.userId} to the universal defaults? This deletes all their current addons.`,
  );
  if (!ok) {
    sink.log("Aborted.");
    return;
  }
  await resetToDefaults(db, member.userId);
  const updated = await getMember(db, member.userId);
  sink.log("Reset complete.");
  sink.log(formatMember(updated!));
}

/** kevbox-admin onboard-debrid <ref> --premiumize <key> --aiostreams <url> */
export async function actionOnboardDebrid(
  db: Db,
  ref: string,
  opts: { premiumize: string; aiostreams: string },
  sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  await onboardDebrid(db, member.userId, {
    premiumizeKey: opts.premiumize,
    aiostreamsUrl: opts.aiostreams,
  });
  const updated = await getMember(db, member.userId);
  sink.log(`Debrid onboarded for ${member.email ?? member.userId}.`);
  sink.log(formatMember(updated!));
}

/** kevbox-admin bulk-add <url> --sort N --yes */
export async function actionBulkAdd(
  db: Db,
  url: string,
  opts: { sort: number; yes?: boolean },
  sink: Sink = consoleSink,
): Promise<void> {
  if (!opts.yes) {
    sink.err("Refusing to run a bulk op without --yes (this changes every member).");
    return;
  }
  await writeBulkSnapshot(db, "bulk-add", sink);
  const n = await bulkAddAddon(db, { url, sortOrder: opts.sort }, true);
  sink.log(`Added "${url}" to ${n} member(s).`);
}

/** kevbox-admin bulk-swap <fromUrl> <toUrl> --yes */
export async function actionBulkSwap(
  db: Db,
  fromUrl: string,
  toUrl: string,
  opts: { yes?: boolean },
  sink: Sink = consoleSink,
): Promise<void> {
  if (!opts.yes) {
    sink.err("Refusing to run a bulk op without --yes (this changes every member).");
    return;
  }
  await writeBulkSnapshot(db, "bulk-swap", sink);
  await bulkSwapUrl(db, { fromUrl, toUrl }, true);
  sink.log(`Swapped "${fromUrl}" -> "${toUrl}" across all members.`);
}

/**
 * kevbox-admin access <ref> — show a member's kill-switch state, device cap, and bound devices.
 * Resolves `ref` via requireMember FIRST so a typo never hits getAccess's fail-open default
 * (no row => active=true) and masquerades as a real member.
 */
export async function actionAccess(db: Db, ref: string, sink: Sink = consoleSink): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const state = await getAccess(db, member.userId);
  sink.log(formatAccess(state));
}

/** kevbox-admin access-disable <ref> — flip the kill-switch off (locks the member out). */
export async function actionAccessDisable(db: Db, ref: string, sink: Sink = consoleSink): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const updated = await setActive(db, member.userId, false);
  sink.log(`Access disabled for ${member.email ?? member.userId}.`);
  sink.log(formatAccess(updated));
}

/** kevbox-admin access-enable <ref> — flip the kill-switch back on. */
export async function actionAccessEnable(db: Db, ref: string, sink: Sink = consoleSink): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const updated = await setActive(db, member.userId, true);
  sink.log(`Access enabled for ${member.email ?? member.userId}.`);
  sink.log(formatAccess(updated));
}

/**
 * kevbox-admin access-max-devices <ref> <n> — set the per-member device cap. Core validates n>=1
 * and throws a validationError on bad input; let it propagate so index withPool prints it.
 */
export async function actionAccessMaxDevices(
  db: Db,
  ref: string,
  n: number,
  sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const updated = await setMaxDevices(db, member.userId, n);
  sink.log(`Device cap set to ${updated.maxDevices} for ${member.email ?? member.userId}.`);
  sink.log(formatAccess(updated));
}

/**
 * kevbox-admin device-remove <ref> <deviceId> — deauthorize a single device. Catches the core
 * notFound (statusCode 404) and reports a clean "Device not found" message instead of crashing.
 */
export async function actionDeviceRemove(
  db: Db,
  ref: string,
  deviceId: string,
  sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  try {
    await removeDevice(db, member.userId, deviceId);
    sink.log(`Removed device ${deviceId} from ${member.email ?? member.userId}.`);
  } catch (err) {
    if ((err as { statusCode?: number }).statusCode === 404) {
      sink.err(`Device not found: ${deviceId}`);
      return;
    }
    throw err;
  }
}

/** kevbox-admin device-remove-all <ref> — deauthorize all of a member's devices. */
export async function actionDeviceRemoveAll(db: Db, ref: string, sink: Sink = consoleSink): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const before = await getAccess(db, member.userId);
  await removeAllDevices(db, member.userId);
  sink.log(`Removed ${before.devices.length} device(s) from ${member.email ?? member.userId}.`);
}

/** kevbox-enroll <ref> --premiumize <key> [--name <n>] */
export async function actionKevboxEnroll(
  db: Db, ref: string, opts: { premiumize: string; name?: string }, cfg: KevboxConfig, sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const { installUrl } = await withKevboxWrite(db, cfg.membersFile, (d) =>
    enrollMember(d, member.userId, { aiostreamsName: opts.name, premiumizeKey: opts.premiumize }, cfg),
  );
  sink.log(`Enrolled ${member.email ?? member.userId}. Install URL: ${installUrl}`);
}

/** kevbox-rotate <ref> --premiumize <key> */
export async function actionKevboxRotate(
  db: Db, ref: string, opts: { premiumize: string }, cfg: KevboxConfig, sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const { installUrl } = await withKevboxWrite(db, cfg.membersFile, (d) => rotateKey(d, member.userId, opts.premiumize, cfg));
  sink.log(`Rotated key. New install URL: ${installUrl}`);
}

/** kevbox-rename <ref> <newName> */
export async function actionKevboxRename(
  db: Db, ref: string, newName: string, cfg: KevboxConfig, sink: Sink = consoleSink,
): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const res = await withKevboxWrite(db, cfg.membersFile, (d) => renameMember(d, member.userId, newName, cfg));
  sink.log(res.keyless ? `Renamed to ${newName} (no key stored — re-issue a key/URL).` : `Renamed. New install URL: ${res.installUrl}`);
}

/** kevbox-unenroll <ref> */
export async function actionKevboxUnenroll(db: Db, ref: string, cfg: KevboxConfig, sink: Sink = consoleSink): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  await withKevboxWrite(db, cfg.membersFile, (d) => unenrollMember(d, member.userId, cfg));
  sink.log(`Un-enrolled ${member.email ?? member.userId}.`);
}

/** kevbox-url <ref> — print the key-bearing install URL (reveal). */
export async function actionKevboxUrl(db: Db, ref: string, cfg: KevboxConfig, sink: Sink = consoleSink): Promise<void> {
  const member = await requireMember(db, ref, sink);
  if (!member) return;
  const url = await buildInstallUrl(db, member.userId, cfg);
  if (!url) { sink.err("No install URL (member has no stored key)."); return; }
  sink.log(url);
}

/** kevbox-migrate --names <comma-list|@file> [--apply] */
export async function actionKevboxMigrate(
  db: Db, names: string[], opts: { apply: boolean }, cfg: KevboxConfig, sink: Sink = consoleSink,
): Promise<void> {
  // migrate261 THROWS under --apply if there are malformed/lost/conflict names (it blocks the write);
  // let that propagate so the CLI exits non-zero with the at-risk list. Dry-run never throws.
  const r = await migrate261(db, names, cfg, opts);
  sink.log(
    `${r.applied ? "APPLIED" : "DRY-RUN"}: total ${r.total}, matched ${r.matched}, backfilled ${r.backfilled}, ` +
      `extras ${r.extras.length}, conflicts ${r.conflicts.length}, malformed ${r.malformed.length}, ` +
      `lost ${r.lost.length}, renamed ${r.renamed.length}, added ${r.added.length}, rendered ${r.rendered.length}.`,
  );
  if (r.extras.length) sink.log(`Extras (unmanaged): ${r.extras.join(", ")}`);
  if (r.renamed.length) sink.log(`Renamed (verbatim drift): ${r.renamed.join(", ")}`);
  if (r.added.length) sink.log(`Added (not in input): ${r.added.join(", ")}`);
  if (r.conflicts.length) sink.err(`Conflicts (skipped): ${r.conflicts.join(", ")}`);
  if (r.malformed.length) sink.err(`Malformed (at risk — blocks --apply): ${r.malformed.join(", ")}`);
  if (r.lost.length) sink.err(`LOST (would drop from allowlist — blocks --apply): ${r.lost.join(", ")}`);
}
