import { afterEach, beforeEach, expect, test } from "vitest";
import { loadConfig } from "../../src/server/config.js";

// Required vars: the removal loop asserts loadConfig() throws when each is missing.
const KEYS = ["SUPABASE_DB_URL", "SUPABASE_URL", "SUPABASE_ANON_KEY", "ADMIN_EMAILS", "PORT"] as const;
// kevbox vars are OPTIONAL — managed here only so they're saved/restored and a stray real-env value
// can't leak into the required-var tests. NOT added to the removal loop.
const KEVBOX_KEYS = ["KEVBOX_ENC_KEY", "KEVBOX_MEMBERS_FILE", "KEVBOX_ADDON_SORT", "KEVBOX_STREAMS_BASE_URL"] as const;
const ALL_KEYS = [...KEYS, ...KEVBOX_KEYS];
let saved: Record<string, string | undefined>;

beforeEach(() => {
  saved = Object.fromEntries(ALL_KEYS.map((k) => [k, process.env[k]]));
  for (const k of ALL_KEYS) delete process.env[k];
});
afterEach(() => {
  for (const k of ALL_KEYS) {
    if (saved[k] === undefined) delete process.env[k];
    else process.env[k] = saved[k];
  }
});

test("loadConfig reads env, splits ADMIN_EMAILS (lowercased/trimmed), defaults PORT", () => {
  process.env.SUPABASE_DB_URL = "postgres://x";
  process.env.SUPABASE_URL = "https://p.supabase.co";
  process.env.SUPABASE_ANON_KEY = "anon";
  process.env.ADMIN_EMAILS = " Kevin.Chiha@gmail.com , Two@Example.com ";
  // Optional kevbox block: present in the known-good fixture so the block builds.
  process.env.KEVBOX_ENC_KEY = "0".repeat(64);
  process.env.KEVBOX_MEMBERS_FILE = "/tmp/members.json";
  const cfg = loadConfig();
  expect(cfg.databaseUrl).toBe("postgres://x");
  expect(cfg.supabaseUrl).toBe("https://p.supabase.co");
  expect(cfg.supabaseAnonKey).toBe("anon");
  expect(cfg.adminEmails).toEqual(["kevin.chiha@gmail.com", "two@example.com"]);
  expect(cfg.port).toBe(8787);
  expect(cfg.kevbox?.membersFile).toBe("/tmp/members.json");
  expect(cfg.kevbox?.streamsBaseUrl).toBe("https://streams.kevbox.dev");
  expect(cfg.kevbox?.addonSort).toBe(4);
  expect(Buffer.isBuffer(cfg.kevbox?.encKey)).toBe(true);
});

test("loadConfig omits the kevbox block when neither var is set", () => {
  process.env.SUPABASE_DB_URL = "postgres://x";
  process.env.SUPABASE_URL = "https://p.supabase.co";
  process.env.SUPABASE_ANON_KEY = "anon";
  process.env.ADMIN_EMAILS = "a@b.com";
  expect(loadConfig().kevbox).toBeUndefined();
});

test("kevbox config: setting only one of the two vars throws", () => {
  process.env.SUPABASE_DB_URL = "postgres://x";
  process.env.SUPABASE_URL = "https://p.supabase.co";
  process.env.SUPABASE_ANON_KEY = "anon";
  process.env.ADMIN_EMAILS = "a@b.com";
  process.env.KEVBOX_ENC_KEY = "0".repeat(64);
  // KEVBOX_MEMBERS_FILE deliberately unset
  expect(() => loadConfig()).toThrow(/together/i);
});

test("loadConfig throws when a required var is missing", () => {
  process.env.SUPABASE_URL = "https://p.supabase.co";
  process.env.SUPABASE_ANON_KEY = "anon";
  process.env.ADMIN_EMAILS = "a@b.com";
  // SUPABASE_DB_URL missing
  expect(() => loadConfig()).toThrow(/SUPABASE_DB_URL/);
});

test("loadConfig honours a custom PORT", () => {
  process.env.SUPABASE_DB_URL = "postgres://x";
  process.env.SUPABASE_URL = "https://p.supabase.co";
  process.env.SUPABASE_ANON_KEY = "anon";
  process.env.ADMIN_EMAILS = "a@b.com";
  process.env.PORT = "9000";
  expect(loadConfig().port).toBe(9000);
});
