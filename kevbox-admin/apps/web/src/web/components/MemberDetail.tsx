import { useState } from "react";
import type {
  MemberDetail as MemberDetailType,
  AddonRow as AddonRowType,
  AccessState,
  DeviceRow,
  MemberActivity,
} from "../lib/api.js";
import type { KevboxState } from "../lib/api.js";
import { AddonRow } from "./AddonRow.js";
import { AccessTab } from "./AccessTab.js";
import { ActivityTab } from "./ActivityTab.js";
import { KevboxTab } from "./KevboxTab.js";

type Tab = "addons" | "access" | "activity" | "kevbox";

export interface MemberDetailProps {
  member: MemberDetailType;
  busy?: boolean;
  access: AccessState | null;
  activity: MemberActivity | null;
  onToggle: (addon: AddonRowType, enabled: boolean) => void;
  onEditUrl: (addon: AddonRowType, url: string) => void;
  onDelete: (addon: AddonRowType) => void;
  onMove: (addon: AddonRowType, dir: -1 | 1) => void;
  onAdd: (url: string) => void;
  onReset: () => void;
  onSetActive: (active: boolean) => void;
  onSetMaxDevices: (max: number) => void;
  onRemoveDevice: (device: DeviceRow) => void;
  onRemoveAllDevices: () => void;
  kevbox: KevboxState | null;
  onSaveKevbox: (body: { name?: string; premiumizeKey?: string }) => void;
  onUnenrollKevbox: () => void;
  onRevealKevboxUrl: () => void;
}

export function MemberDetail({
  member,
  busy = false,
  access,
  activity,
  onToggle,
  onEditUrl,
  onDelete,
  onMove,
  onAdd,
  onReset,
  onSetActive,
  onSetMaxDevices,
  onRemoveDevice,
  onRemoveAllDevices,
  kevbox,
  onSaveKevbox,
  onUnenrollKevbox,
  onRevealKevboxUrl,
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
        <button
          className={`tab${tab === "activity" ? " active" : ""}`}
          onClick={() => setTab("activity")}
        >
          Activity
        </button>
        <button className={`tab${tab === "kevbox" ? " active" : ""}`} onClick={() => setTab("kevbox")}>Kevbox</button>
      </div>

      {tab === "access" ? (
        <AccessTab
          access={access}
          busy={busy}
          onSetActive={onSetActive}
          onSetMaxDevices={onSetMaxDevices}
          onRemoveDevice={onRemoveDevice}
          onRemoveAllDevices={onRemoveAllDevices}
        />
      ) : tab === "activity" ? (
        <ActivityTab activity={activity} />
      ) : tab === "kevbox" ? (
        <KevboxTab
          kevbox={kevbox}
          busy={busy}
          onSave={onSaveKevbox}
          onUnenroll={onUnenrollKevbox}
          onRevealUrl={onRevealKevboxUrl}
        />
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
        </>
      )}
    </div>
  );
}
