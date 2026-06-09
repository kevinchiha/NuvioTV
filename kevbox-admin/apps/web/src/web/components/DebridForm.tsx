import { useMemo, useState } from "react";
import { buildTorrentioUrl, DEBRID_TORRENTIO_SORT, DEBRID_AIOSTREAMS_SORT } from "@kevbox-admin/core";

export interface DebridFormProps {
  busy?: boolean;
  onSubmit: (premiumizeKey: string, aiostreamsUrl: string) => void;
}

export function DebridForm({ busy = false, onSubmit }: DebridFormProps) {
  const [key, setKey] = useState("");
  const [aio, setAio] = useState("");

  // Live preview: exactly the two rows the server will insert (sort 4 + sort 5).
  const preview = useMemo(() => {
    const rows: { sortOrder: number; url: string }[] = [];
    if (key.trim()) {
      try {
        rows.push({ sortOrder: DEBRID_TORRENTIO_SORT, url: buildTorrentioUrl(key) });
      } catch {
        /* incomplete key → no torrentio preview row yet */
      }
    }
    if (aio.trim()) rows.push({ sortOrder: DEBRID_AIOSTREAMS_SORT, url: aio.trim() });
    return rows;
  }, [key, aio]);

  const ready = key.trim() !== "" && aio.trim() !== "" && !busy;

  return (
    <div>
      <h4>Guided debrid onboarding</h4>
      <p>
        <label className="muted">Premiumize API key</label>
        <input aria-label="premiumize-key" value={key} onChange={(e) => setKey(e.target.value)} />
      </p>
      <p>
        <label className="muted">AIOStreams manifest URL</label>
        <input aria-label="aiostreams-url" value={aio} onChange={(e) => setAio(e.target.value)} />
      </p>

      <div className="muted">Will insert these rows:</div>
      <div className="preview">
        {preview.length === 0 ? (
          <span className="muted">Enter a key and an AIOStreams URL to preview.</span>
        ) : (
          preview.map((r) => (
            <div key={r.sortOrder}>
              <strong>sort {r.sortOrder}:</strong> {r.url}
            </div>
          ))
        )}
      </div>

      <button
        className="primary"
        style={{ marginTop: 10 }}
        disabled={!ready}
        onClick={() => onSubmit(key.trim(), aio.trim())}
      >
        {busy ? "Saving…" : "Onboard debrid"}
      </button>
    </div>
  );
}
