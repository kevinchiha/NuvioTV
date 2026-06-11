import type { MemberSummary, MemberDetail, AddonRow, AccessState, DeviceRow } from "@kevbox-admin/core";
import type { MemberActivity, ActivityRankRow, GoingDarkRow, FleetStats } from "@kevbox-admin/core";

export type { MemberSummary, MemberDetail, AddonRow, AccessState, DeviceRow };
export type { MemberActivity, ActivityRankRow, GoingDarkRow, FleetStats } from "@kevbox-admin/core";

/** A function that returns the current bearer token (or null if signed out). */
export type TokenProvider = () => Promise<string | null>;

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export class Api {
  constructor(private getToken: TokenProvider) {}

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const token = await this.getToken();
    const headers: Record<string, string> = {};
    if (token) headers.authorization = `Bearer ${token}`;
    if (body !== undefined) headers["content-type"] = "application/json";
    const res = await fetch(`/api${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      let message = res.statusText;
      try {
        const j = await res.json();
        if (j && typeof j.error === "string") message = j.error;
      } catch {
        /* keep statusText */
      }
      throw new ApiError(res.status, message);
    }
    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  listMembers(): Promise<{ members: MemberSummary[] }> {
    return this.request("GET", "/members");
  }
  getMember(ref: string): Promise<{ member: MemberDetail }> {
    return this.request("GET", `/members/${encodeURIComponent(ref)}`);
  }
  addAddon(userId: string, body: { url: string; enabled?: boolean; sortOrder?: number }): Promise<{ addon: AddonRow }> {
    return this.request("POST", `/members/${encodeURIComponent(userId)}/addons`, body);
  }
  updateAddon(addonId: number, body: { url?: string; enabled?: boolean }): Promise<{ addon: AddonRow }> {
    return this.request("PATCH", `/addons/${addonId}`, body);
  }
  setEnabled(addonId: number, enabled: boolean): Promise<{ addon: AddonRow }> {
    return this.request("PUT", `/addons/${addonId}/enabled`, { enabled });
  }
  reorder(userId: string, orderedIds: number[]): Promise<{ ok: true }> {
    return this.request("PUT", `/members/${encodeURIComponent(userId)}/addons/order`, { orderedIds });
  }
  deleteAddon(addonId: number): Promise<{ ok: true }> {
    return this.request("DELETE", `/addons/${addonId}`);
  }
  reset(userId: string): Promise<{ ok: true }> {
    return this.request("POST", `/members/${encodeURIComponent(userId)}/reset`);
  }
  onboardDebrid(userId: string, body: { premiumizeKey: string; aiostreamsUrl: string }): Promise<{ ok: true }> {
    return this.request("POST", `/members/${encodeURIComponent(userId)}/debrid`, body);
  }
  // Bulk responses include `snapshot` — the pre-change pre-image (spec §4.6) the SPA offers as a download.
  bulkAdd(body: { url: string; sortOrder: number; confirm: boolean }): Promise<{ inserted: number; snapshot: unknown[] }> {
    return this.request("POST", "/bulk/add", body);
  }
  bulkSwap(body: { fromUrl: string; toUrl: string; confirm: boolean }): Promise<{ ok: true; snapshot: unknown[] }> {
    return this.request("POST", "/bulk/swap", body);
  }
  getAccess(userId: string): Promise<{ access: AccessState }> {
    return this.request("GET", `/members/${encodeURIComponent(userId)}/access`);
  }
  setActive(userId: string, active: boolean): Promise<{ access: AccessState }> {
    return this.request("PUT", `/members/${encodeURIComponent(userId)}/access/active`, { active });
  }
  setMaxDevices(userId: string, maxDevices: number): Promise<{ access: AccessState }> {
    return this.request("PUT", `/members/${encodeURIComponent(userId)}/access/max-devices`, { maxDevices });
  }
  removeDevice(userId: string, deviceId: string): Promise<{ ok: true }> {
    return this.request("DELETE", `/members/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}`);
  }
  removeAllDevices(userId: string): Promise<{ ok: true }> {
    return this.request("DELETE", `/members/${encodeURIComponent(userId)}/devices`);
  }
  getMemberActivity(userId: string): Promise<{ activity: MemberActivity | null }> {
    return this.request("GET", `/members/${encodeURIComponent(userId)}/activity`);
  }
  getLeaderboard(window: "today" | "7d" | "30d", order: "most" | "least"): Promise<{ rows: ActivityRankRow[] }> {
    return this.request("GET", `/activity/leaderboard?window=${window}&order=${order}`);
  }
  getGoingDark(): Promise<{ rows: GoingDarkRow[] }> { return this.request("GET", "/activity/going-dark"); }
  getFleetStats(): Promise<{ stats: FleetStats }> { return this.request("GET", "/activity/stats"); }
}

/** Trigger a browser download of the pre-bulk snapshot JSON so a wrong bulk op is recoverable. */
export function downloadSnapshot(rows: unknown[], label: string): void {
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const blob = new Blob([JSON.stringify(rows, null, 2)], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `kevbox-admin-snapshot-${label}-${stamp}.json`;
  a.click();
  URL.revokeObjectURL(a.href);
}
