import { useState } from "react";

export interface ConfirmModalProps {
  title: string;
  /** What will happen — shown above the input. */
  message: React.ReactNode;
  /** The word the user must type (default "CONFIRM"). */
  confirmWord?: string;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmModal({
  title,
  message,
  confirmWord = "CONFIRM",
  busy = false,
  onConfirm,
  onCancel,
}: ConfirmModalProps) {
  const [typed, setTyped] = useState("");
  const ready = typed === confirmWord && !busy;
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label={title}>
      <div className="modal">
        <h3>{title}</h3>
        <div className="muted">{message}</div>
        <p>
          Type <strong>{confirmWord}</strong> to proceed:
        </p>
        <input
          aria-label="confirm-input"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          autoFocus
        />
        <div className="row" style={{ marginTop: 12, justifyContent: "flex-end" }}>
          <button onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button className="primary" onClick={onConfirm} disabled={!ready}>
            {busy ? "Working…" : "Confirm"}
          </button>
        </div>
      </div>
    </div>
  );
}
