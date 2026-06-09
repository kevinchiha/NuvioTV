import { useState } from "react";
import type { MemberDetail as MemberDetailType, AddonRow as AddonRowType } from "../lib/api.js";
import { AddonRow } from "./AddonRow.js";
import { DebridForm } from "./DebridForm.js";

type Tab = "addons" | "access";

export interface MemberDetailProps {
  member: MemberDetailType;
  busy?: boolean;
  onToggle: (addon: AddonRowType, enabled: boolean) => void;
  onEditUrl: (addon: AddonRowType, url: string) => void;
  onDelete: (addon: AddonRowType) => void;
  onMove: (addon: AddonRowType, dir: -1 | 1) => void;
  onAdd: (url: string) => void;
  onReset: () => void;
  onOnboardDebrid: (premiumizeKey: string, aiostreamsUrl: string) => void;
}

export function MemberDetail({
  member,
  busy = false,
  onToggle,
  onEditUrl,
  onDelete,
  onMove,
  onAdd,
  onReset,
  onOnboardDebrid,
}: MemberDetailProps) {
  const [tab, setTab] = useState<Tab>("addons");
  const [newUrl, setNewUrl] = useState("");

  return (
    <div>
      <h2>{member.email ?? "(no email)"}</h2>
      <div className="muted">{member.userId}</div>

      <div className="tabs">
        <button
          className={`tab${tab === "addons" ? " active" : ""}`}
          onClick={() => setTab("addons")}
        >
          Addons
        </button>
        <button
          className={`tab${tab === "access" ? " active" : ""}`}
          onClick={() => setTab("access")}
        >
          Access
        </button>
      </div>

      {tab === "access" ? (
        <p className="muted">
          Access / kill-switch is not implemented in v1. This tab is reserved so it can be added
          without restructuring the layout (see plans/MEMBER-ACCESS-PLAN.md).
        </p>
      ) : (
        <>
          <div>
            {member.addons.length === 0 && <p className="muted">No addons.</p>}
            {member.addons.map((a, i) => (
              <AddonRow
                key={a.id}
                addon={a}
                isFirst={i === 0}
                isLast={i === member.addons.length - 1}
                onToggle={(enabled) => onToggle(a, enabled)}
                onEditUrl={(url) => onEditUrl(a, url)}
                onDelete={() => onDelete(a)}
                onMove={(dir) => onMove(a, dir)}
              />
            ))}
          </div>

          <div className="row" style={{ marginTop: 12 }}>
            <input
              aria-label="new-addon-url"
              placeholder="add manifest url…"
              value={newUrl}
              onChange={(e) => setNewUrl(e.target.value)}
            />
            <button
              className="primary"
              disabled={newUrl.trim() === "" || busy}
              onClick={() => {
                onAdd(newUrl.trim());
                setNewUrl("");
              }}
            >
              Add
            </button>
          </div>

          <div style={{ marginTop: 16 }}>
            <button className="danger" disabled={busy} onClick={onReset}>
              Reset to defaults
            </button>
          </div>

          <hr style={{ margin: "20px 0", borderColor: "var(--border)" }} />
          <DebridForm busy={busy} onSubmit={onOnboardDebrid} />
        </>
      )}
    </div>
  );
}
