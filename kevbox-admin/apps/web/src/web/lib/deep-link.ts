import type { MemberSummary } from "./api.js";

/**
 * Resolve a `?member=<ref>` deep-link to a loaded member. `ref` may be a Supabase user id
 * (uuid) or an email (matched case-insensitively). Returns undefined if there is no such
 * member in the loaded list.
 */
export function findMemberByRef(
  members: readonly MemberSummary[],
  ref: string,
): MemberSummary | undefined {
  const needle = ref.trim().toLowerCase();
  if (!needle) return undefined;
  return members.find(
    (m) => m.userId.toLowerCase() === needle || (m.email ?? "").toLowerCase() === needle,
  );
}

export type MemberDeepLinkResult =
  | { kind: "none" }
  | { kind: "select"; member: MemberSummary }
  | { kind: "miss"; ref: string };

/**
 * Decide what a `?member=` ref means against the loaded list. `select` → open it directly;
 * `miss` → the ref is valid-looking but not in the loaded list, so the caller should fall back
 * to a server lookup (api.getMember) before giving up; `none` → nothing to do.
 */
export function resolveMemberDeepLink(
  members: readonly MemberSummary[],
  ref: string | null,
): MemberDeepLinkResult {
  const needle = (ref ?? "").trim();
  if (!needle) return { kind: "none" };
  const member = findMemberByRef(members, needle);
  return member ? { kind: "select", member } : { kind: "miss", ref: needle };
}
