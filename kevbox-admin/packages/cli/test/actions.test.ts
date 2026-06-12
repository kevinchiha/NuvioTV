import { afterEach, expect, test } from "vitest";
import { pool, seedMember, cleanup } from "./helpers.js";
import {
  actionList,
  actionShow,
  actionAdd,
  actionUpdate,
  actionToggle,
  actionReorder,
  actionReset,
  actionBulkAdd,
  actionBulkSwap,
  actionAccess,
  actionAccessDisable,
  actionAccessEnable,
  actionAccessMaxDevices,
  actionDeviceRemove,
  actionDeviceRemoveAll,
} from "../src/actions.js";

const SUFFIX = "@cli-test.dev";

afterEach(async () => {
  await cleanup(SUFFIX);
});

/** Collect everything an action prints into a single string. */
function makeSink() {
  const out: string[] = [];
  return { sink: { log: (s: string) => out.push(s), err: (s: string) => out.push(s) }, text: () => out.join("\n") };
}

test("actionList prints seeded members", async () => {
  await seedMember(`list-a${SUFFIX}`);
  const { sink, text } = makeSink();
  await actionList(pool, sink);
  expect(text()).toContain(`list-a${SUFFIX}`);
});

test("actionShow prints a member's addons (resolved by email)", async () => {
  const id = await seedMember(`show${SUFFIX}`);
  await pool.query(
    "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://a.example',0)",
    [id],
  );
  const { sink, text } = makeSink();
  await actionShow(pool, `show${SUFFIX}`, sink);
  expect(text()).toContain("https://a.example");
});

test("actionShow reports a missing member", async () => {
  const { sink, text } = makeSink();
  await actionShow(pool, `ghost${SUFFIX}`, sink);
  expect(text()).toMatch(/not found/i);
});

test("actionAdd inserts an addon, honouring --disabled and --sort", async () => {
  await seedMember(`add${SUFFIX}`);
  const { sink, text } = makeSink();
  await actionAdd(pool, `add${SUFFIX}`, "https://added.example", { disabled: true, sort: 7 }, sink);
  expect(text()).toContain("https://added.example");
  expect(text()).toContain("sort=7");
  expect(text()).toContain("off");
});

test("actionAdd errors on an unknown member", async () => {
  const { sink, text } = makeSink();
  await actionAdd(pool, `nope${SUFFIX}`, "https://x.example", {}, sink);
  expect(text()).toMatch(/not found/i);
});

test("actionUpdate changes url and enable state by addon id", async () => {
  const id = await seedMember(`upd${SUFFIX}`);
  const { rows } = await pool.query<{ id: string }>(
    "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://old.example',0) returning id",
    [id],
  );
  const addonId = Number(rows[0].id);
  const { sink, text } = makeSink();
  await actionUpdate(pool, addonId, { url: "https://new.example", disable: true }, sink);
  expect(text()).toContain("https://new.example");
  expect(text()).toContain("off");
});

test("actionToggle flips enabled", async () => {
  const id = await seedMember(`tog${SUFFIX}`);
  const { rows } = await pool.query<{ id: string }>(
    "insert into public.member_addon (user_id, url, sort_order, enabled) values ($1,'https://t.example',0,true) returning id",
    [id],
  );
  const addonId = Number(rows[0].id);
  const { sink, text } = makeSink();
  await actionToggle(pool, addonId, sink);
  expect(text()).toContain("off");
});

test("actionReorder applies a new order from the prompt answer", async () => {
  const id = await seedMember(`ord${SUFFIX}`);
  const a = await pool.query<{ id: string }>(
    "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://a.example',0) returning id",
    [id],
  );
  const b = await pool.query<{ id: string }>(
    "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://b.example',1) returning id",
    [id],
  );
  const aId = Number(a.rows[0].id);
  const bId = Number(b.rows[0].id);
  const { sink } = makeSink();
  // Prompt returns the reversed order.
  const askFake = async () => `${bId} ${aId}`;
  await actionReorder(pool, `ord${SUFFIX}`, askFake, sink);
  const { rows } = await pool.query<{ url: string }>(
    "select url from public.member_addon where user_id = $1 order by sort_order, id",
    [id],
  );
  expect(rows.map((r) => r.url)).toEqual(["https://b.example", "https://a.example"]);
});

test("actionReset re-seeds defaults after a confirmed prompt", async () => {
  const id = await seedMember(`rst${SUFFIX}`);
  await pool.query(
    "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://custom.example',9)",
    [id],
  );
  const { sink } = makeSink();
  const askYes = async () => "y";
  await actionReset(pool, `rst${SUFFIX}`, askYes, sink);
  const { rows } = await pool.query<{ n: number }>(
    "select count(*)::int as n from public.member_addon where user_id = $1 and url = 'https://custom.example'",
    [id],
  );
  expect(rows[0].n).toBe(0);
  const def = await pool.query<{ n: number }>(
    "select count(*)::int as n from public.member_addon where user_id = $1",
    [id],
  );
  expect(def.rows[0].n).toBe(4);
});

test("actionReset aborts when the prompt is not confirmed", async () => {
  const id = await seedMember(`rstn${SUFFIX}`);
  await pool.query(
    "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://keep.example',9)",
    [id],
  );
  const { sink, text } = makeSink();
  const askNo = async () => "n";
  await actionReset(pool, `rstn${SUFFIX}`, askNo, sink);
  expect(text()).toMatch(/aborted/i);
  const { rows } = await pool.query<{ n: number }>(
    "select count(*)::int as n from public.member_addon where user_id = $1 and url = 'https://keep.example'",
    [id],
  );
  expect(rows[0].n).toBe(1);
});

test("actionBulkAdd requires --yes (refuses without it)", async () => {
  await seedMember(`ba1${SUFFIX}`);
  const { sink, text } = makeSink();
  await actionBulkAdd(pool, "https://bulk.example", { sort: 99, yes: false }, sink);
  expect(text()).toMatch(/--yes/i);
  const { rows } = await pool.query<{ n: number }>(
    "select count(*)::int as n from public.member_addon where url = 'https://bulk.example'",
  );
  expect(rows[0].n).toBe(0);
});

test("actionBulkAdd with --yes adds to every member", async () => {
  await seedMember(`ba2a${SUFFIX}`);
  await seedMember(`ba2b${SUFFIX}`);
  const { sink, text } = makeSink();
  await actionBulkAdd(pool, "https://bulk2.example", { sort: 99, yes: true }, sink);
  expect(text()).toMatch(/2/);
  const { rows } = await pool.query<{ n: number }>(
    "select count(*)::int as n from public.member_addon where url = 'https://bulk2.example'",
  );
  expect(rows[0].n).toBeGreaterThanOrEqual(2);
});

test("actionBulkSwap with --yes swaps the url everywhere", async () => {
  const id = await seedMember(`bs${SUFFIX}`);
  await pool.query(
    "insert into public.member_addon (user_id, url, sort_order) values ($1,'https://swapfrom.example',0)",
    [id],
  );
  const { sink } = makeSink();
  await actionBulkSwap(pool, "https://swapfrom.example", "https://swapto.example", { yes: true }, sink);
  const { rows } = await pool.query<{ url: string }>(
    "select url from public.member_addon where user_id = $1",
    [id],
  );
  expect(rows.map((r) => r.url)).toEqual(["https://swapto.example"]);
});

test("actionAccess shows the fail-open defaults (Enabled) for a fresh member", async () => {
  await seedMember(`acc${SUFFIX}`);
  const { sink, text } = makeSink();
  await actionAccess(pool, `acc${SUFFIX}`, sink);
  expect(text()).toContain("Enabled");
  expect(text()).toContain("0 of 1 devices used");
  expect(text()).toContain("(no devices)");
});

test("actionAccess reports an unknown member", async () => {
  const { sink, text } = makeSink();
  await actionAccess(pool, `accghost${SUFFIX}`, sink);
  expect(text()).toMatch(/not found/i);
});

test("actionAccessDisable flips active to false", async () => {
  const id = await seedMember(`accdis${SUFFIX}`);
  const { sink, text } = makeSink();
  await actionAccessDisable(pool, `accdis${SUFFIX}`, sink);
  expect(text()).toContain("Disabled");
  const { rows } = await pool.query<{ active: boolean }>(
    "select active from public.member_access where user_id = $1",
    [id],
  );
  expect(rows[0].active).toBe(false);
});

test("actionAccessEnable flips active back to true", async () => {
  const id = await seedMember(`accen${SUFFIX}`);
  await pool.query(
    "insert into public.member_access (user_id, active) values ($1, false)",
    [id],
  );
  const { sink, text } = makeSink();
  await actionAccessEnable(pool, `accen${SUFFIX}`, sink);
  expect(text()).toContain("Enabled");
  const { rows } = await pool.query<{ active: boolean }>(
    "select active from public.member_access where user_id = $1",
    [id],
  );
  expect(rows[0].active).toBe(true);
});

test("actionAccessMaxDevices sets max_devices", async () => {
  const id = await seedMember(`accmax${SUFFIX}`);
  const { sink, text } = makeSink();
  await actionAccessMaxDevices(pool, `accmax${SUFFIX}`, 3, sink);
  expect(text()).toContain("0 of 3 devices used");
  const { rows } = await pool.query<{ max_devices: number }>(
    "select max_devices from public.member_device_policy where user_id = $1",
    [id],
  );
  expect(Number(rows[0].max_devices)).toBe(3);
});

test("actionDeviceRemove removes a seeded device row", async () => {
  const id = await seedMember(`devrm${SUFFIX}`);
  await pool.query(
    "insert into public.member_device (user_id, device_id, device_name) values ($1,'dev-1','KevBox A')",
    [id],
  );
  const { sink, text } = makeSink();
  await actionDeviceRemove(pool, `devrm${SUFFIX}`, "dev-1", sink);
  expect(text()).toMatch(/removed/i);
  const { rows } = await pool.query<{ n: number }>(
    "select count(*)::int as n from public.member_device where user_id = $1",
    [id],
  );
  expect(rows[0].n).toBe(0);
});

test("actionDeviceRemove reports a missing device without crashing", async () => {
  await seedMember(`devnf${SUFFIX}`);
  const { sink, text } = makeSink();
  await actionDeviceRemove(pool, `devnf${SUFFIX}`, "ghost-device", sink);
  expect(text()).toMatch(/not found/i);
});

test("actionDeviceRemoveAll clears the member's device rows", async () => {
  const id = await seedMember(`devall${SUFFIX}`);
  await pool.query(
    "insert into public.member_device (user_id, device_id) values ($1,'d-a'), ($1,'d-b')",
    [id],
  );
  const { sink, text } = makeSink();
  await actionDeviceRemoveAll(pool, `devall${SUFFIX}`, sink);
  expect(text()).toMatch(/2 device/i);
  const { rows } = await pool.query<{ n: number }>(
    "select count(*)::int as n from public.member_device where user_id = $1",
    [id],
  );
  expect(rows[0].n).toBe(0);
});
