import type { MemberSummary, MemberDetail, AddonRow } from "@kevbox-admin/core";

/** Render the member overview table as a single multi-line string. */
export function formatMembers(members: MemberSummary[]): string {
  if (members.length === 0) return "(no members)";
  const header = ["EMAIL", "USER_ID", "ADDONS", "DEBRID", "CREATED"];
  const rows = members.map((m) => [
    m.email ?? "(no email)",
    m.userId,
    String(m.addonCount),
    m.hasDebrid ? "yes" : "no",
    m.createdAt,
  ]);
  return renderTable(header, rows);
}

/** Render one member's detail (header line + their addons). */
export function formatMember(member: MemberDetail): string {
  const head = `${member.email ?? "(no email)"}  [${member.userId}]`;
  if (member.addons.length === 0) return `${head}\n  (no addons)`;
  const header = ["ID", "SORT", "ON", "URL"];
  const rows = member.addons.map((a) => [
    String(a.id),
    String(a.sortOrder),
    a.enabled ? "on" : "off",
    a.url,
  ]);
  return `${head}\n${renderTable(header, rows)}`;
}

/** Render a single addon row as one line (used after add/update/toggle). */
export function formatAddon(a: AddonRow): string {
  return `#${a.id}  sort=${a.sortOrder}  ${a.enabled ? "on" : "off"}  ${a.url}`;
}

/** Left-aligned, column-padded text table. */
function renderTable(header: string[], rows: string[][]): string {
  const widths = header.map((h, i) =>
    Math.max(h.length, ...rows.map((r) => r[i].length)),
  );
  const line = (cells: string[]) =>
    cells.map((c, i) => c.padEnd(widths[i])).join("  ").trimEnd();
  return [line(header), ...rows.map(line)].join("\n");
}
