import { describe, it, expect } from "vitest";
import { findMemberByRef, resolveMemberDeepLink } from "../../src/web/lib/deep-link.js";
import type { MemberSummary } from "../../src/web/lib/api.js";

function member(overrides: Partial<MemberSummary>): MemberSummary {
  return {
    userId: "11111111-1111-1111-1111-111111111111",
    email: "Jane@Example.com",
    createdAt: "2026-01-01T00:00:00Z",
    addonCount: 0,
    enrolled: false,
    ...overrides,
  } as MemberSummary;
}

const members: MemberSummary[] = [
  member({}),
  member({ userId: "22222222-2222-2222-2222-222222222222", email: null }),
  member({ userId: "33333333-3333-3333-3333-333333333333", email: "a+tag@example.com" }),
];

describe("findMemberByRef", () => {
  it("matches by email, case-insensitively", () => {
    expect(findMemberByRef(members, "jane@example.com")?.userId).toBe(
      "11111111-1111-1111-1111-111111111111",
    );
  });
  it("matches by user id", () => {
    expect(findMemberByRef(members, "22222222-2222-2222-2222-222222222222")?.email).toBeNull();
  });
  it("matches an email containing '+'", () => {
    expect(findMemberByRef(members, "a+tag@example.com")?.userId).toBe(
      "33333333-3333-3333-3333-333333333333",
    );
  });
  it("returns undefined for an unknown ref", () => {
    expect(findMemberByRef(members, "nobody@nowhere.com")).toBeUndefined();
  });
  it("returns undefined for an empty ref", () => {
    expect(findMemberByRef(members, "  ")).toBeUndefined();
  });
});

describe("resolveMemberDeepLink", () => {
  it("returns 'none' for a null/blank ref", () => {
    expect(resolveMemberDeepLink(members, null)).toEqual({ kind: "none" });
    expect(resolveMemberDeepLink(members, "  ")).toEqual({ kind: "none" });
  });
  it("returns 'select' with the member for a loaded hit", () => {
    expect(resolveMemberDeepLink(members, "jane@example.com")).toMatchObject({ kind: "select" });
  });
  it("returns 'miss' (try the server) for an unknown ref", () => {
    expect(resolveMemberDeepLink(members, "ghost@nowhere.com")).toEqual({
      kind: "miss",
      ref: "ghost@nowhere.com",
    });
  });
});
