import type { MemberActivity } from "../lib/api.js";

const fmt = (s: number) => `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;

export function ActivityTab({ activity }: { activity: MemberActivity | null }) {
  if (!activity) return <p className="muted">No activity recorded yet.</p>;
  return (
    <div>
      {activity.sharingSuspect && (
        <p className="danger">⚠ Possible sharing — a single day exceeded 18h of watch time.</p>
      )}
      <div className="row"><span>Today</span><strong>{fmt(activity.watchSecondsToday)}</strong></div>
      <div className="row"><span>Last 7 days</span><strong>{fmt(activity.watchSeconds7d)}</strong></div>
      <div className="row"><span>Last 30 days</span><strong>{fmt(activity.watchSeconds30d)}</strong></div>
      <div className="row"><span>Sessions (7d)</span><strong>{activity.sessions7d}</strong></div>
      <div className="row"><span>App version</span><strong>{activity.lastAppVersion ?? "—"}</strong></div>
      <div className="row"><span>Last seen</span><strong>{activity.lastHeartbeatAt ? new Date(activity.lastHeartbeatAt).toLocaleString() : "—"}</strong></div>
      <div className="row"><span>Playback errors (7d)</span><strong>{activity.errors7d}</strong></div>
    </div>
  );
}
