import { afterEach, beforeEach, expect, test } from "vitest";
import { loadConfig } from "../../src/server/config.js";

const KEYS = ["SUPABASE_DB_URL", "SUPABASE_URL", "SUPABASE_ANON_KEY", "ADMIN_EMAILS", "PORT"] as const;
let saved: Record<string, string | undefined>;

beforeEach(() => {
  saved = Object.fromEntries(KEYS.map((k) => [k, process.env[k]]));
  for (const k of KEYS) delete process.env[k];
});
afterEach(() => {
  for (const k of KEYS) {
    if (saved[k] === undefined) delete process.env[k];
    else process.env[k] = saved[k];
  }
});

test("loadConfig reads env, splits ADMIN_EMAILS (lowercased/trimmed), defaults PORT", () => {
  process.env.SUPABASE_DB_URL = "postgres://x";
  process.env.SUPABASE_URL = "https://p.supabase.co";
  process.env.SUPABASE_ANON_KEY = "anon";
  process.env.ADMIN_EMAILS = " Kevin.Chiha@gmail.com , Two@Example.com ";
  const cfg = loadConfig();
  expect(cfg.databaseUrl).toBe("postgres://x");
  expect(cfg.supabaseUrl).toBe("https://p.supabase.co");
  expect(cfg.supabaseAnonKey).toBe("anon");
  expect(cfg.adminEmails).toEqual(["kevin.chiha@gmail.com", "two@example.com"]);
  expect(cfg.port).toBe(8787);
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
