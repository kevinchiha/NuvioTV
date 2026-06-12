import type { Pool, PoolClient } from "pg";

/** Any pg connection we can run queries on — a Pool or a checked-out client (used in tests/txns). */
export type Db = Pick<Pool | PoolClient, "query">;

export interface MemberSummary {
  userId: string;
  email: string | null;
  createdAt: string;
  addonCount: number;
  /** true if the member has any addon NOT in default_member_addons() (e.g. a debrid source). */
  hasDebrid: boolean;
}

export interface AddonRow {
  id: number;
  userId: string;
  url: string;
  enabled: boolean;
  sortOrder: number;
  updatedAt: string;
}

export interface MemberDetail {
  userId: string;
  email: string | null;
  addons: AddonRow[];
}

/** Maps a raw member_addon DB row (bigint id arrives as string) to AddonRow. */
export function mapAddonRow(r: {
  id: string | number;
  user_id: string;
  url: string;
  enabled: boolean;
  sort_order: number;
  updated_at: string | Date;
}): AddonRow {
  return {
    id: Number(r.id),
    userId: r.user_id,
    url: r.url,
    enabled: r.enabled,
    sortOrder: r.sort_order,
    updatedAt: new Date(r.updated_at).toISOString(),
  };
}

export interface DeviceRow {
  deviceId: string;
  deviceName: string | null;
  firstSeen: string;
  lastSeen: string;
}

export interface AccessState {
  userId: string;
  active: boolean;
  maxDevices: number;
  devices: DeviceRow[];
}

/**
 * Maps a raw member_device DB row to DeviceRow. Unlike mapAddonRow, device_id is an opaque
 * client-generated TEXT id (a UUID minted on the TV) — NOT a numeric PK — so it is carried through
 * verbatim with no Number() coercion.
 */
export function mapDeviceRow(r: {
  device_id: string;
  device_name: string | null;
  first_seen: string | Date;
  last_seen: string | Date;
}): DeviceRow {
  return {
    deviceId: r.device_id,
    deviceName: r.device_name,
    firstSeen: new Date(r.first_seen).toISOString(),
    lastSeen: new Date(r.last_seen).toISOString(),
  };
}

/** Config the kevbox mutations + renderer need (built from env in server/cli). */
export interface KevboxConfig {
  encKey: Buffer;
  membersFile: string;
  streamsBaseUrl: string; // e.g. https://streams.kevbox.dev (no trailing slash)
  addonSort: number; // KEVBOX_ADDON_SORT, default 4
}

/** Non-secret enrollment view returned to the dashboard (never the key or install URL). */
export interface KevboxState {
  name: string;
  enrolled: boolean;
  hasKey: boolean;
}
