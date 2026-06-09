import { useCallback, useEffect, useMemo, useState } from "react";
import { Api, downloadSnapshot, type MemberSummary, type MemberDetail as MemberDetailType, type AddonRow as AddonRowType } from "./lib/api.js";
import { signIn, signOut, currentToken } from "./lib/supabase.js";
import { Login } from "./components/Login.js";
import { MemberList } from "./components/MemberList.js";
import { MemberDetail } from "./components/MemberDetail.js";
import { BulkOps } from "./components/BulkOps.js";
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
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<PendingConfirm | null>(null);
  const [view, setView] = useState<"member" | "bulk">("member");

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
      const { member } = await api.getMember(userId);
      setSelected(member);
    },
    [api],
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
    await withBusy(async () => {
      await reloadSelected(m.userId);
    });
  }

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
  const onOnboardDebrid = (premiumizeKey: string, aiostreamsUrl: string) =>
    withBusy(async () => {
      if (selected) { await api.onboardDebrid(selected.userId, { premiumizeKey, aiostreamsUrl }); await afterMutate(); }
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
        if (selected) await reloadSelected(selected.userId);
      }),
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
        {view === "bulk" ? (
          <BulkOps onBulkAdd={onBulkAdd} onBulkSwap={onBulkSwap} />
        ) : selected ? (
          <MemberDetail
            member={selected}
            busy={busy}
            onToggle={onToggle}
            onEditUrl={onEditUrl}
            onDelete={onDelete}
            onMove={onMove}
            onAdd={onAdd}
            onReset={onReset}
            onOnboardDebrid={onOnboardDebrid}
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
