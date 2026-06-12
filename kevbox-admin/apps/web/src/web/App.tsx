import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Api, downloadSnapshot, type MemberSummary, type MemberDetail as MemberDetailType, type AddonRow as AddonRowType, type AccessState, type DeviceRow, type MemberActivity } from "./lib/api.js";
import { signIn, signOut, currentToken } from "./lib/supabase.js";
import { resolveMemberDeepLink } from "./lib/deep-link.js";
import { Login } from "./components/Login.js";
import { MemberList } from "./components/MemberList.js";
import { MemberDetail } from "./components/MemberDetail.js";
import { BulkOps } from "./components/BulkOps.js";
import { FleetView } from "./components/FleetView.js";
import { ConfirmModal } from "./components/ConfirmModal.js";

/** A queued confirm action: shows the modal, runs `run()` on confirm. */
interface PendingConfirm {
  title: string;
  message: React.ReactNode;
  run: () => Promise<void>;
}

export function App() {
  const api = useMemo(() => new Api(currentToken), []);
  const [authed, setAuthed] = useState<boolean | null>(null);
  const [members, setMembers] = useState<MemberSummary[]>([]);
  const [selected, setSelected] = useState<MemberDetailType | null>(null);
  const [selectedAccess, setSelectedAccess] = useState<AccessState | null>(null);
  const [selectedActivity, setSelectedActivity] = useState<MemberActivity | null>(null);
  const [selectedKevbox, setSelectedKevbox] = useState<import("./lib/api.js").KevboxState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<PendingConfirm | null>(null);
  const [view, setView] = useState<"member" | "bulk" | "fleet">("member");

  // On mount, check for an existing session.
  useEffect(() => {
    void currentToken().then((t) => setAuthed(t !== null));
  }, []);

  const refreshMembers = useCallback(async () => {
    const { members } = await api.listMembers();
    setMembers(members);
  }, [api]);

  useEffect(() => {
    if (authed) void refreshMembers().catch((e) => setError(String(e)));
  }, [authed, refreshMembers]);

  const reloadSelected = useCallback(
    async (userId: string) => {
      const [{ member }, { access }, { activity }] = await Promise.all([
        api.getMember(userId),
        api.getAccess(userId),
        api.getMemberActivity(userId),
      ]);
      setSelected(member);
      setSelectedKevbox(member.kevbox);
      setSelectedAccess(access);
      setSelectedActivity(activity);
    },
    [api],
  );

  // Open a member by id, reusing the ?member= deep-link path (server lookup) rather than an
  // in-memory MemberSummary lookup — a leaderboard / going-dark userId may not be in `members`.
  const openMemberById = useCallback(
    (userId: string) => {
      void withBusy(async () => {
        const { member } = await api.getMember(userId);
        setView("member");
        await reloadSelected(member.userId);
      });
    },
    [api, reloadSelected],
  );

  async function withBusy(fn: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function handleSignIn(email: string, password: string) {
    await signIn(email, password);
    setAuthed(true);
  }

  async function selectMember(m: MemberSummary) {
    setView("member");
    setSelectedActivity(null); // clear prior member's activity until the fresh fetch lands
    await withBusy(async () => {
      await reloadSelected(m.userId);
    });
  }

  // Deep-link from www.kevbox.dev: ?member=<email|userId> auto-opens that member once the list
  // has loaded. Runs once; clears the param so a manual reload won't re-trigger. Resolves against
  // the loaded list first, then falls back to a server lookup so it still works if listMembers is
  // ever paginated.
  const deepLinkRef = useRef<string | null>(
    typeof window === "undefined"
      ? null
      : new URLSearchParams(window.location.search).get("member"),
  );
  const deepLinkHandled = useRef(false);
  useEffect(() => {
    if (deepLinkHandled.current || !authed || members.length === 0) return;
    const outcome = resolveMemberDeepLink(members, deepLinkRef.current);
    if (outcome.kind === "none") return;
    deepLinkHandled.current = true;

    // Strip ?member (preserving any other params) so a reload won't re-select.
    const params = new URLSearchParams(window.location.search);
    params.delete("member");
    const qs = params.toString();
    window.history.replaceState(null, "", qs ? `?${qs}` : window.location.pathname);

    if (outcome.kind === "select") {
      void selectMember(outcome.member);
      return;
    }
    // miss → ask the server (resolves email-or-userId); only error if it truly doesn't exist.
    void (async () => {
      try {
        const { member } = await api.getMember(outcome.ref);
        setView("member");
        await reloadSelected(member.userId);
      } catch {
        setError(`No member found for "${outcome.ref}"`);
      }
    })();
    // selectMember/reloadSelected/api are stable enough; the deepLinkHandled ref makes this run once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [authed, members]);

  // ---- addon op callbacks (optimistic-ish: re-fetch the member after each) ----
  function afterMutate() {
    return Promise.all([
      selected ? reloadSelected(selected.userId) : Promise.resolve(),
      refreshMembers(),
    ]).then(() => undefined);
  }

  const onToggle = (a: AddonRowType, enabled: boolean) =>
    withBusy(async () => { await api.setEnabled(a.id, enabled); await afterMutate(); });
  const onEditUrl = (a: AddonRowType, url: string) =>
    withBusy(async () => { await api.updateAddon(a.id, { url }); await afterMutate(); });
  const onDelete = (a: AddonRowType) =>
    withBusy(async () => { await api.deleteAddon(a.id); await afterMutate(); });
  const onMove = (a: AddonRowType, dir: -1 | 1) =>
    withBusy(async () => {
      if (!selected) return;
      const ids = selected.addons.map((x) => x.id);
      const i = ids.indexOf(a.id);
      const j = i + dir;
      if (j < 0 || j >= ids.length) return;
      [ids[i], ids[j]] = [ids[j], ids[i]];
      await api.reorder(selected.userId, ids);
      await afterMutate();
    });
  const onAdd = (url: string) =>
    withBusy(async () => { if (selected) { await api.addAddon(selected.userId, { url }); await afterMutate(); } });

  // ---- kevbox enrollment callbacks ----
  const onSaveKevbox = (body: { name?: string; premiumizeKey?: string }) =>
    withBusy(async () => { if (selected) { await api.putKevbox(selected.userId, body); await afterMutate(); } });
  function onUnenrollKevbox() {
    if (!selected) return;
    const userId = selected.userId;
    setPending({
      title: "Un-enroll from Kevbox",
      message: <>Remove <strong>{selected.email ?? userId}</strong> from the Kevbox allowlist? Their TV loses the kevbox addon on next sync.</>,
      run: () => withBusy(async () => { await api.unenrollKevbox(userId); await afterMutate(); }),
    });
  }
  const onRevealKevboxUrl = () =>
    withBusy(async () => {
      if (!selected) return;
      const { installUrl } = await api.getKevboxInstallUrl(selected.userId);
      await navigator.clipboard.writeText(installUrl).catch(() => undefined);
      window.prompt("Kevbox install URL (copied):", installUrl);
    });

  // ---- destructive / bulk actions go through the ConfirmModal ----
  function onReset() {
    if (!selected) return;
    const userId = selected.userId;
    setPending({
      title: "Reset to defaults",
      message: `Delete all of ${selected.email ?? userId}'s addons and re-seed the 4 universal defaults?`,
      run: () => withBusy(async () => { await api.reset(userId); await afterMutate(); }),
    });
  }
  function onBulkAdd(url: string, sortOrder: number) {
    setPending({
      title: "Add to everyone",
      message: <>Add <code>{url}</code> (sort {sortOrder}) to <strong>every</strong> member?</>,
      run: () => withBusy(async () => {
        const { snapshot } = await api.bulkAdd({ url, sortOrder, confirm: true });
        downloadSnapshot(snapshot, "bulk-add"); // pre-image backstop before this widely-applied change
        await refreshMembers();
      }),
    });
  }
  function onBulkSwap(fromUrl: string, toUrl: string) {
    setPending({
      title: "Swap URL everywhere",
      message: <>Swap <code>{fromUrl}</code> → <code>{toUrl}</code> across <strong>all</strong> members?</>,
      run: () => withBusy(async () => {
        const { snapshot } = await api.bulkSwap({ fromUrl, toUrl, confirm: true });
        downloadSnapshot(snapshot, "bulk-swap");
        await refreshMembers();
        if (selected) await reloadSelected(selected.userId); // onBulkSwap re-fetches access too (reloadSelected is the single fetch point).
      }),
    });
  }

  // ---- access kill-switch + device-limit callbacks ----
  // Enable is direct (re-grants access); Disable is destructive → routes through ConfirmModal.
  function onSetActive(active: boolean) {
    if (!selected) return;
    const userId = selected.userId;
    if (active) {
      void withBusy(async () => { await api.setActive(userId, true); await afterMutate(); });
      return;
    }
    setPending({
      title: "Disable member access",
      message: (
        <>
          Disable access for <strong>{selected.email ?? userId}</strong>? Their TV shows the
          locked-out screen within a few minutes.
        </>
      ),
      run: () => withBusy(async () => { await api.setActive(userId, false); await afterMutate(); }),
    });
  }
  function onSetMaxDevices(max: number) {
    if (!selected) return;
    const userId = selected.userId;
    void withBusy(async () => { await api.setMaxDevices(userId, max); await afterMutate(); });
  }
  function onRemoveDevice(device: DeviceRow) {
    if (!selected) return;
    const userId = selected.userId;
    const label = `${device.deviceName ?? "(unknown model)"} (${device.deviceId.slice(-8)})`;
    setPending({
      title: "Remove device",
      message: (
        <>
          Remove <strong>{label}</strong>? This is <strong>not undoable</strong> — a
          returning/cleared TV mints a new device id, so the binding is gone.
        </>
      ),
      run: () => withBusy(async () => { await api.removeDevice(userId, device.deviceId); await afterMutate(); }),
    });
  }
  function onRemoveAllDevices() {
    if (!selected) return;
    const userId = selected.userId;
    const count = selectedAccess?.devices.length ?? 0;
    setPending({
      title: "Remove all devices",
      message: (
        <>
          Remove all <strong>{count}</strong> device(s)? This is <strong>not undoable</strong> — a
          returning/cleared TV mints a new device id, so the bindings are gone.
        </>
      ),
      run: () => withBusy(async () => { await api.removeAllDevices(userId); await afterMutate(); }),
    });
  }

  if (authed === null) return <p style={{ padding: 24 }}>Loading…</p>;
  if (!authed) return <Login onSignIn={handleSignIn} />;

  return (
    <div className="app">
      <div className="pane left">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <strong>Members</strong>
          <button onClick={() => { void signOut().then(() => setAuthed(false)); }}>Sign out</button>
        </div>
        <button
          style={{ width: "100%", margin: "8px 0" }}
          className={view === "fleet" ? "primary" : ""}
          onClick={() => setView("fleet")}
        >
          Fleet
        </button>
        <button
          style={{ width: "100%", margin: "8px 0" }}
          className={view === "bulk" ? "primary" : ""}
          onClick={() => setView("bulk")}
        >
          Bulk operations
        </button>
        <MemberList
          members={members}
          selectedUserId={view === "member" ? selected?.userId ?? null : null}
          onSelect={(m) => void selectMember(m)}
        />
      </div>

      <div className="pane">
        {error && <p className="error">{error}</p>}
        {view === "fleet" ? (
          <FleetView api={api} onOpenMember={openMemberById} />
        ) : view === "bulk" ? (
          <BulkOps onBulkAdd={onBulkAdd} onBulkSwap={onBulkSwap} />
        ) : selected ? (
          <MemberDetail
            member={selected}
            busy={busy}
            access={selectedAccess}
            activity={selectedActivity}
            onToggle={onToggle}
            onEditUrl={onEditUrl}
            onDelete={onDelete}
            onMove={onMove}
            onAdd={onAdd}
            onReset={onReset}
            onSetActive={onSetActive}
            onSetMaxDevices={onSetMaxDevices}
            onRemoveDevice={onRemoveDevice}
            onRemoveAllDevices={onRemoveAllDevices}
            kevbox={selectedKevbox}
            onSaveKevbox={onSaveKevbox}
            onUnenrollKevbox={onUnenrollKevbox}
            onRevealKevboxUrl={onRevealKevboxUrl}
          />
        ) : (
          <p className="muted">Select a member from the left.</p>
        )}
      </div>

      {pending && (
        <ConfirmModal
          title={pending.title}
          message={pending.message}
          busy={busy}
          onCancel={() => setPending(null)}
          onConfirm={() => {
            const job = pending.run;
            setPending(null);
            void job();
          }}
        />
      )}
    </div>
  );
}
