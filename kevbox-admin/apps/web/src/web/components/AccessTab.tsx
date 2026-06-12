import { useState } from "react";
import type { AccessState, DeviceRow } from "../lib/api.js";

export interface AccessTabProps {
  access: AccessState | null;
  busy?: boolean;
  onSetActive: (active: boolean) => void;
  onSetMaxDevices: (max: number) => void;
  onRemoveDevice: (device: DeviceRow) => void;
  onRemoveAllDevices: () => void;
}

/** Last 8 chars of the opaque client-generated device id — enough to disambiguate non-unique model names. */
function shortId(deviceId: string): string {
  return deviceId.length <= 8 ? deviceId : `…${deviceId.slice(-8)}`;
}

/** A coarse relative hint (e.g. "3h ago") alongside the absolute ISO string. */
function relative(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return "";
  const secs = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (secs < 60) return "just now";
  const mins = Math.round(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  return `${days}d ago`;
}

/**
 * Presentational Access tab (split into its own component). Reflects operator INTENT (the member_access
 * flag + member_device rows) — NOT live enforcement: if a panic-button RPC rollback is engaged the
 * toggles here have no effect on the TV. All state comes from the parent; this component only renders.
 */
export function AccessTab({
  access,
  busy = false,
  onSetActive,
  onSetMaxDevices,
  onRemoveDevice,
  onRemoveAllDevices,
}: AccessTabProps) {
  const [maxDraft, setMaxDraft] = useState("");

  if (access === null) return <p className="muted">Loading…</p>;

  const { active, maxDevices, devices } = access;
  const overLimit = devices.length > maxDevices;

  return (
    <div>
      <h4>Access</h4>

      <div className="row">
        <strong>{active ? "Enabled" : "Disabled"}</strong>
        {active ? (
          <button className="danger" disabled={busy} onClick={() => onSetActive(false)}>
            Disable
          </button>
        ) : (
          <button className="primary" disabled={busy} onClick={() => onSetActive(true)}>
            Enable
          </button>
        )}
      </div>
      <p className="muted">
        Disabling locks within ~2 min while the TV is awake &amp; online; up to ~5 min if offline; a
        sleeping/backgrounded TV locks when it next wakes.
      </p>
      <p className="muted">
        This tab reflects <strong>intent</strong>, not live enforcement — if a panic-button RPC
        rollback is engaged, toggles here have no effect.
      </p>

      <h4 style={{ marginTop: 20 }}>Devices</h4>
      {devices.length === 0 && <p className="muted">No devices bound.</p>}
      {devices.map((d, i) => (
        <div key={d.deviceId} className="addon">
          <span className="url">
            <strong>{d.deviceName ?? "(unknown model)"}</strong>{" "}
            <span className="muted">{shortId(d.deviceId)}</span>
            {i === 0 && <span className="muted"> · likely active</span>}
            <br />
            <span className="muted">
              first seen {d.firstSeen} ({relative(d.firstSeen)}) · last seen {d.lastSeen} (
              {relative(d.lastSeen)})
            </span>
          </span>
          <button
            className="danger"
            disabled={busy}
            onClick={() => onRemoveDevice(d)}
            aria-label={`remove-${d.deviceId}`}
          >
            Remove
          </button>
        </div>
      ))}

      <h4 style={{ marginTop: 20 }}>Device limit</h4>
      <div className="row">
        <input
          type="number"
          min={1}
          aria-label="max-devices"
          placeholder={String(maxDevices)}
          value={maxDraft}
          onChange={(e) => setMaxDraft(e.target.value)}
          style={{ width: 80 }}
        />
        <button
          className="primary"
          disabled={busy || maxDraft.trim() === ""}
          onClick={() => {
            const n = parseInt(maxDraft, 10);
            if (Number.isInteger(n)) onSetMaxDevices(n);
            setMaxDraft("");
          }}
        >
          Set
        </button>
        <span className="muted">
          {devices.length} of {maxDevices} device(s) used
        </span>
      </div>
      {overLimit && (
        <p className="error">
          {devices.length} devices still authorized; remove {devices.length - maxDevices} to enforce
          the limit of {maxDevices} — lowering the cap does not evict seated devices.
        </p>
      )}

      <div style={{ marginTop: 20 }}>
        <button className="danger" disabled={busy || devices.length === 0} onClick={onRemoveAllDevices}>
          Remove all devices
        </button>
      </div>
      <p className="muted">
        To swap a TV under a 1-device limit: power off / uninstall the old TV first (or it re-claims
        the slot within ~2 min), OR disable→remove→re-enable, OR temporarily raise max-devices to 2.
      </p>
    </div>
  );
}
