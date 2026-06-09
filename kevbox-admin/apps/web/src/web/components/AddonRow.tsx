import { useState } from "react";
import type { AddonRow as AddonRowType } from "../lib/api.js";

export interface AddonRowProps {
  addon: AddonRowType;
  isFirst: boolean;
  isLast: boolean;
  onToggle: (enabled: boolean) => void;
  onEditUrl: (url: string) => void;
  onDelete: () => void;
  onMove: (dir: -1 | 1) => void;
}

export function AddonRow({ addon, isFirst, isLast, onToggle, onEditUrl, onDelete, onMove }: AddonRowProps) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(addon.url);

  return (
    <div className="addon">
      <input
        type="checkbox"
        aria-label={`enabled-${addon.id}`}
        checked={addon.enabled}
        onChange={(e) => onToggle(e.target.checked)}
        style={{ width: "auto" }}
      />
      {editing ? (
        <input aria-label={`url-${addon.id}`} value={draft} onChange={(e) => setDraft(e.target.value)} />
      ) : (
        <span className="url" style={{ opacity: addon.enabled ? 1 : 0.5 }}>
          {addon.url}
        </span>
      )}
      <button onClick={() => onMove(-1)} disabled={isFirst} aria-label={`up-${addon.id}`}>↑</button>
      <button onClick={() => onMove(1)} disabled={isLast} aria-label={`down-${addon.id}`}>↓</button>
      {editing ? (
        <button
          className="primary"
          onClick={() => {
            onEditUrl(draft.trim());
            setEditing(false);
          }}
        >
          Save
        </button>
      ) : (
        <button onClick={() => setEditing(true)}>Edit</button>
      )}
      <button className="danger" onClick={onDelete} aria-label={`delete-${addon.id}`}>✕</button>
    </div>
  );
}
