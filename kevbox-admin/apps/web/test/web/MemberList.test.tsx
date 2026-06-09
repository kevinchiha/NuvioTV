import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { MemberList } from "../../src/web/components/MemberList.js";
import type { MemberSummary } from "../../src/web/lib/api.js";

const members: MemberSummary[] = [
  { userId: "u1", email: "a@test.dev", createdAt: "2026-01-01T00:00:00.000Z", addonCount: 4, hasDebrid: false },
  { userId: "u2", email: "b@test.dev", createdAt: "2026-02-01T00:00:00.000Z", addonCount: 6, hasDebrid: true },
];

describe("MemberList", () => {
  it("renders a row per member and fires onSelect", () => {
    const onSelect = vi.fn();
    render(<MemberList members={members} selectedUserId={null} onSelect={onSelect} />);
    expect(screen.getByText("a@test.dev")).toBeInTheDocument();
    expect(screen.getByText("b@test.dev")).toBeInTheDocument();
    expect(screen.getByText(/debrid/)).toBeInTheDocument(); // only b has debrid
    fireEvent.click(screen.getByText("a@test.dev"));
    expect(onSelect).toHaveBeenCalledWith(members[0]);
  });

  it("shows an empty state with no members", () => {
    render(<MemberList members={[]} selectedUserId={null} onSelect={() => {}} />);
    expect(screen.getByText(/No members yet/)).toBeInTheDocument();
  });
});
