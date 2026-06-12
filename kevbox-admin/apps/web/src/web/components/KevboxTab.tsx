import { useState } from "react";
import type { KevboxState } from "../lib/api.js";

export interface KevboxTabProps {
  kevbox: KevboxState | null;
  busy?: boolean;
  /** name optional (rename), premiumizeKey optional (rotate); enroll requires the key. */
  onSave: (body: { name?: string; premiumizeKey?: string }) => void;
  onUnenroll: () => void;
  onRevealUrl: () => void;
}

export function KevboxTab({ kevbox, busy = false, onSave, onUnenroll, onRevealUrl }: KevboxTabProps) {
  const enrolled = kevbox?.enrolled === true;
  const [name, setName] = useState(kevbox?.name ?? "");
  const [key, setKey] = useState("");

  return (
    <div>
      <h4>Kevbox</h4>
      <p className="muted">
        {enrolled ? (
          <>Status: <strong>Enrolled</strong> as <strong>{kevbox!.name}</strong>{kevbox!.hasKey ? "" : " (no key stored)"}</>
        ) : (
          <>Status: <strong>Not enrolled</strong></>
        )}
      </p>

      <p>
        <label className="muted">AIOStreams name (defaults to email local-part)</label>
        <input aria-label="kevbox-name" value={name} onChange={(e) => setName(e.target.value)} placeholder={kevbox?.name ?? "name"} />
      </p>
      <p>
        <label className="muted">Premiumize key {enrolled ? "(set to rotate)" : "(required to enroll)"}</label>
        <input aria-label="kevbox-key" value={key} onChange={(e) => setKey(e.target.value)} placeholder="••••••••" />
      </p>

      <div className="row">
        <button
          className="primary"
          disabled={busy || (!enrolled && key.trim() === "")}
          onClick={() => {
            const body: { name?: string; premiumizeKey?: string } = {};
            if (name.trim()) body.name = name.trim();
            if (key.trim()) body.premiumizeKey = key.trim();
            onSave(body);
            setKey("");
          }}
        >
          {enrolled ? "Save" : "Enroll"}
        </button>
        {enrolled && kevbox!.hasKey && (
          <button disabled={busy} onClick={onRevealUrl}>Reveal / copy URL</button>
        )}
      </div>

      {enrolled && (
        <p className="muted" style={{ marginTop: 8 }}>
          Rotating the key updates the member's remote addon URL — their TV picks it up on its next
          sync (no end-user reinstall).
        </p>
      )}

      {enrolled && (
        <div style={{ marginTop: 16 }}>
          <button className="danger" disabled={busy} onClick={onUnenroll}>Un-enroll</button>
        </div>
      )}
    </div>
  );
}
