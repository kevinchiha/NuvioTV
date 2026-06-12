import type { MemberSummary, MemberDetail, AddonRow, AccessState, DeviceRow } from "@kevbox-admin/core";

/** Render the member overview table as a single multi-line string. */
export function formatMembers(members: MemberSummary[]): string {
  if (members.length === 0) return "(no members)";
  const header = ["EMAIL", "USER_ID", "ADDONS", "KEVBOX", "CREATED"];
  const rows = members.map((m) => [
    m.email ?? "(no email)",
    m.userId,
    String(m.addonCount),
    m.enrolled ? "yes" : "no",
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

/** Render a member's access state (kill-switch + device usage + device table). */
export function formatAccess(state: AccessState): string {
  const status = state.active ? "Enabled" : "Disabled";
  const usage = `${state.devices.length} of ${state.maxDevices} devices used`;
  return `Access: ${status}\n${usage}\n${formatDevices(state.devices)}`;
}

/** Render the member's bound devices as a table; "(no devices)" when empty. */
export function formatDevices(devices: DeviceRow[]): string {
  if (devices.length === 0) return "(no devices)";
  const header = ["DEVICE_ID", "NAME", "FIRST_SEEN", "LAST_SEEN"];
  const rows = devices.map((d) => [
    d.deviceId,
    d.deviceName ?? "(no name)",
    d.firstSeen,
    d.lastSeen,
  ]);
  return renderTable(header, rows);
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
