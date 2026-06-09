import type { MemberSummary } from "../lib/api.js";

export interface MemberListProps {
  members: MemberSummary[];
  selectedUserId: string | null;
  onSelect: (m: MemberSummary) => void;
}

function fmtDate(iso: string): string {
  return new Date(iso).toLocaleDateString();
}

export function MemberList({ members, selectedUserId, onSelect }: MemberListProps) {
  if (members.length === 0) {
    return <p className="muted">No members yet.</p>;
  }
  return (
    <div>
      {members.map((m) => (
        <div
          key={m.userId}
          role="button"
          className={`member-row${m.userId === selectedUserId ? " selected" : ""}`}
          onClick={() => onSelect(m)}
        >
          <div>{m.email ?? <span className="muted">(no email)</span>}</div>
          <div className="muted">
            {fmtDate(m.createdAt)} · {m.addonCount} addons{m.hasDebrid ? " · debrid" : ""}
          </div>
        </div>
      ))}
    </div>
  );
}
