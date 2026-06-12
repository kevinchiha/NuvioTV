import { open, rename } from "node:fs/promises";
import { dirname, join } from "node:path";
import type { Db } from "./types.js";

const NAME_RE = /^[a-z0-9._+-]{1,64}$/;

/**
 * Atomic, group-readable write: write a sibling .tmp, fchmod 0664, fsync, rename over the
 * target (so a reader never sees a half-written file). The shared dir should be setgid so the
 * tmp inherits group `kevbox`; we still fchmod 0664 explicitly so a tight umask can't make the
 * file unreadable to the container's group (C4).
 */
async function atomicWriteFile(filePath: string, contents: string): Promise<void> {
  const tmp = join(dirname(filePath), `${".tmp-"}${process.pid}-members.json`);
  const fh = await open(tmp, "w", 0o664);
  try {
    await fh.writeFile(contents, "utf8");
    await fh.chmod(0o664);
    await fh.sync();
  } finally {
    await fh.close();
  }
  await rename(tmp, filePath);
}

/**
 * Render the full allowlist snapshot from the DB and atomically write it to `filePath`.
 * Returns the rendered names. EMPTY-SET SAFETY FLOOR (C2): if the resolved set is empty,
 * THROW and do not write — a bad query / half-applied migration must never blank the
 * allowlist and lock everyone out; the previous file stays intact.
 */
export async function renderMembersFile(db: Db, filePath: string): Promise<string[]> {
  const { rows } = await db.query<{ aiostreams_name: string }>(
    `select aiostreams_name from public.kevbox_member where enrolled = true
     union
     select aiostreams_name from public.kevbox_allowlist_extra
     order by 1`,
  );
  const names = rows.map((r) => r.aiostreams_name).filter((n) => NAME_RE.test(n));
  if (names.length === 0) {
    throw new Error(
      "refusing to write empty members.json (safety floor): rendered 0 names; previous file left intact",
    );
  }
  await atomicWriteFile(filePath, JSON.stringify(names));
  return names;
}
