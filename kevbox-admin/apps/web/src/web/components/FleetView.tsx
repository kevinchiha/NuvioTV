import { useEffect, useState } from "react";
import type { FleetStats, ActivityRankRow, GoingDarkRow } from "../lib/api.js";
import { Api } from "../lib/api.js";

const fmt = (s: number) => `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;

export function FleetView({ api, onOpenMember }: { api: Api; onOpenMember: (userId: string) => void }) {
  const [stats, setStats] = useState<FleetStats | null>(null);
  const [most, setMost] = useState<ActivityRankRow[]>([]);
  const [least, setLeast] = useState<ActivityRankRow[]>([]);
  const [dark, setDark] = useState<GoingDarkRow[]>([]);
  const [window, setWindow] = useState<"today" | "7d" | "30d">("7d");

  useEffect(() => {
    api.getFleetStats().then((r) => setStats(r.stats));
    api.getGoingDark().then((r) => setDark(r.rows));
  }, [api]);
  useEffect(() => {
    api.getLeaderboard(window, "most").then((r) => setMost(r.rows));
    api.getLeaderboard(window, "least").then((r) => setLeast(r.rows));
  }, [api, window]);

  return (
    <div>
      <h3>Fleet</h3>
      {stats && (
        <div className="stat-grid">
          <div><span className="muted">DAU</span><strong>{stats.dau}</strong></div>
          <div><span className="muted">WAU</span><strong>{stats.wau}</strong></div>
          <div><span className="muted">MAU</span><strong>{stats.mau}</strong></div>
          <div><span className="muted">Watch (30d)</span><strong>{stats.totalWatchHours}h</strong></div>
          <div><span className="muted">Going dark</span><strong>{stats.goingDark}</strong></div>
          <div><span className="muted">Errors (7d)</span><strong>{stats.errors7d}</strong></div>
        </div>
      )}
      <label>Window:{" "}
        <select value={window} onChange={(e) => setWindow(e.target.value as any)}>
          <option value="today">Today</option><option value="7d">7 days</option><option value="30d">30 days</option>
        </select>
      </label>
      <h4>Most active</h4>
      {most.map((r) => (
        <div key={r.userId} className="member-row" role="button" onClick={() => onOpenMember(r.userId)}>
          <span>{r.email ?? r.userId}</span><span>{fmt(r.watchSeconds)}</span></div>
      ))}
      <h4>Least active</h4>
      {least.map((r) => (
        <div key={r.userId} className="member-row" role="button" onClick={() => onOpenMember(r.userId)}>
          <span>{r.email ?? r.userId}</span><span>{fmt(r.watchSeconds)}</span></div>
      ))}
      <h4>Going dark (active, no watch in 14d)</h4>
      {dark.map((r) => (
        <div key={r.userId} className="member-row" role="button" onClick={() => onOpenMember(r.userId)}>
          <span>{r.email ?? r.userId}</span>
          <span>{r.lastHeartbeatAt ? new Date(r.lastHeartbeatAt).toLocaleDateString() : "never"}</span></div>
      ))}
    </div>
  );
}
