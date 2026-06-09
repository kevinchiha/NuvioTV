#!/usr/bin/env node
import { Command } from "commander";
import { createPool } from "./db.js";
import { ask } from "./prompt.js";
import { consoleSink } from "./actions.js";
import {
  actionList,
  actionShow,
  actionAdd,
  actionUpdate,
  actionToggle,
  actionReorder,
  actionReset,
  actionOnboardDebrid,
  actionBulkAdd,
  actionBulkSwap,
  actionAccess,
  actionAccessDisable,
  actionAccessEnable,
  actionAccessMaxDevices,
  actionDeviceRemove,
  actionDeviceRemoveAll,
} from "./actions.js";

/** Resolve a pool, run `fn(pool)`, always close the pool, and exit non-zero on error. */
async function withPool(fn: (pool: ReturnType<typeof createPool>) => Promise<void>): Promise<void> {
  let pool: ReturnType<typeof createPool>;
  try {
    pool = createPool();
  } catch (err) {
    consoleSink.err((err as Error).message);
    process.exitCode = 1;
    return;
  }
  try {
    await fn(pool);
  } catch (err) {
    consoleSink.err((err as Error).message);
    process.exitCode = 1;
  } finally {
    await pool.end();
  }
}

const program = new Command();

program
  .name("kevbox-admin")
  .description("Admin CLI for KevBox TV member addons (local-trusted; uses SUPABASE_DB_URL).")
  .version("0.1.0");

program
  .command("list")
  .description("List all members with addon count + debrid status.")
  .action(async () => {
    await withPool((pool) => actionList(pool, consoleSink));
  });

program
  .command("show")
  .argument("<ref>", "member email or userId")
  .description("Show one member's addons.")
  .action(async (ref: string) => {
    await withPool((pool) => actionShow(pool, ref, consoleSink));
  });

program
  .command("add")
  .argument("<ref>", "member email or userId")
  .argument("<url>", "addon manifest URL")
  .option("--disabled", "add the addon disabled", false)
  .option("--sort <n>", "explicit sort_order", (v) => parseInt(v, 10))
  .description("Add an addon to a member.")
  .action(async (ref: string, url: string, opts: { disabled?: boolean; sort?: number }) => {
    await withPool((pool) => actionAdd(pool, ref, url, opts, consoleSink));
  });

program
  .command("update")
  .argument("<addonId>", "addon id", (v) => parseInt(v, 10))
  .option("--url <u>", "new URL")
  .option("--enable", "enable the addon")
  .option("--disable", "disable the addon")
  .description("Update an addon's URL and/or enabled state.")
  .action(async (addonId: number, opts: { url?: string; enable?: boolean; disable?: boolean }) => {
    await withPool((pool) => actionUpdate(pool, addonId, opts, consoleSink));
  });

program
  .command("toggle")
  .argument("<addonId>", "addon id", (v) => parseInt(v, 10))
  .description("Flip an addon's enabled state.")
  .action(async (addonId: number) => {
    await withPool((pool) => actionToggle(pool, addonId, consoleSink));
  });

program
  .command("reorder")
  .argument("<ref>", "member email or userId")
  .description("Interactively reorder a member's addons.")
  .action(async (ref: string) => {
    await withPool((pool) => actionReorder(pool, ref, ask, consoleSink));
  });

program
  .command("reset")
  .argument("<ref>", "member email or userId")
  .description("Reset a member to the universal defaults (confirm prompt).")
  .action(async (ref: string) => {
    await withPool((pool) => actionReset(pool, ref, ask, consoleSink));
  });

program
  .command("onboard-debrid")
  .argument("<ref>", "member email or userId")
  .requiredOption("--premiumize <key>", "the member's Premiumize API key")
  .requiredOption("--aiostreams <url>", "the member's full AIOStreams manifest URL")
  .description("Add the member's Torrentio (Premiumize) + AIOStreams debrid sources.")
  .action(async (ref: string, opts: { premiumize: string; aiostreams: string }) => {
    await withPool((pool) => actionOnboardDebrid(pool, ref, opts, consoleSink));
  });

program
  .command("bulk-add")
  .argument("<url>", "addon manifest URL to add to EVERY member")
  .requiredOption("--sort <n>", "sort_order for the new addon", (v) => parseInt(v, 10))
  .option("--yes", "confirm this affects every member", false)
  .description("Add one addon to every member (requires --yes).")
  .action(async (url: string, opts: { sort: number; yes?: boolean }) => {
    await withPool((pool) => actionBulkAdd(pool, url, opts, consoleSink));
  });

program
  .command("bulk-swap")
  .argument("<fromUrl>", "URL to replace")
  .argument("<toUrl>", "replacement URL")
  .option("--yes", "confirm this affects every member", false)
  .description("Swap one addon URL for another across every member (requires --yes).")
  .action(async (fromUrl: string, toUrl: string, opts: { yes?: boolean }) => {
    await withPool((pool) => actionBulkSwap(pool, fromUrl, toUrl, opts, consoleSink));
  });

program
  .command("access")
  .argument("<ref>", "member email or userId")
  .description("Show a member's access state, device cap, and bound devices.")
  .action(async (ref: string) => {
    await withPool((pool) => actionAccess(pool, ref, consoleSink));
  });

program
  .command("access-disable")
  .argument("<ref>", "member email or userId")
  .description("Disable a member's access (the kill-switch locks their TVs out).")
  .action(async (ref: string) => {
    await withPool((pool) => actionAccessDisable(pool, ref, consoleSink));
  });

program
  .command("access-enable")
  .argument("<ref>", "member email or userId")
  .description("Re-enable a member's access.")
  .action(async (ref: string) => {
    await withPool((pool) => actionAccessEnable(pool, ref, consoleSink));
  });

program
  .command("access-max-devices")
  .argument("<ref>", "member email or userId")
  .argument("<n>", "device cap (integer >= 1)", (v) => parseInt(v, 10))
  .description("Set a member's per-device cap (does NOT evict already-seated devices).")
  .action(async (ref: string, n: number) => {
    await withPool((pool) => actionAccessMaxDevices(pool, ref, n, consoleSink));
  });

program
  .command("device-remove")
  .argument("<ref>", "member email or userId")
  .argument("<deviceId>", "the opaque device id to deauthorize")
  .description("Deauthorize a single device from a member.")
  .action(async (ref: string, deviceId: string) => {
    await withPool((pool) => actionDeviceRemove(pool, ref, deviceId, consoleSink));
  });

program
  .command("device-remove-all")
  .argument("<ref>", "member email or userId")
  .description("Deauthorize all of a member's devices.")
  .action(async (ref: string) => {
    await withPool((pool) => actionDeviceRemoveAll(pool, ref, consoleSink));
  });

await program.parseAsync(process.argv);
