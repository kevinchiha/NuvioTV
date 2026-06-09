import { useState } from "react";

export interface BulkOpsProps {
  onBulkAdd: (url: string, sortOrder: number) => void;
  onBulkSwap: (fromUrl: string, toUrl: string) => void;
}

export function BulkOps({ onBulkAdd, onBulkSwap }: BulkOpsProps) {
  const [addUrl, setAddUrl] = useState("");
  const [addSort, setAddSort] = useState("99");
  const [fromUrl, setFromUrl] = useState("");
  const [toUrl, setToUrl] = useState("");

  return (
    <div>
      <h3>Bulk operations</h3>
      <p className="muted">These apply to EVERY member and mirror to all TVs on next open. No undo.</p>

      <h4>Add to everyone</h4>
      <p>
        <input aria-label="bulk-add-url" placeholder="manifest url" value={addUrl} onChange={(e) => setAddUrl(e.target.value)} />
      </p>
      <p>
        <input aria-label="bulk-add-sort" placeholder="sort order" value={addSort} onChange={(e) => setAddSort(e.target.value)} />
      </p>
      <button
        disabled={addUrl.trim() === "" || !Number.isInteger(Number(addSort))}
        onClick={() => onBulkAdd(addUrl.trim(), Number(addSort))}
      >
        Add to everyone…
      </button>

      <h4 style={{ marginTop: 16 }}>Swap URL everywhere</h4>
      <p>
        <input aria-label="bulk-swap-from" placeholder="from url" value={fromUrl} onChange={(e) => setFromUrl(e.target.value)} />
      </p>
      <p>
        <input aria-label="bulk-swap-to" placeholder="to url" value={toUrl} onChange={(e) => setToUrl(e.target.value)} />
      </p>
      <button
        disabled={fromUrl.trim() === "" || toUrl.trim() === ""}
        onClick={() => onBulkSwap(fromUrl.trim(), toUrl.trim())}
      >
        Swap everywhere…
      </button>
    </div>
  );
}
