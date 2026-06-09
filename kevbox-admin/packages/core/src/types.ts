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
