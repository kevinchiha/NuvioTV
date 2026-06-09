import { afterEach, beforeEach, expect, test } from "vitest";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { resolveDbUrl } from "../src/db.js";

const ORIGINAL = process.env.SUPABASE_DB_URL;

beforeEach(() => {
  delete process.env.SUPABASE_DB_URL;
});

afterEach(() => {
  if (ORIGINAL === undefined) delete process.env.SUPABASE_DB_URL;
  else process.env.SUPABASE_DB_URL = ORIGINAL;
});

test("resolveDbUrl prefers the SUPABASE_DB_URL env var", () => {
  process.env.SUPABASE_DB_URL = "postgres://env/db";
  expect(resolveDbUrl("/nonexistent/local.properties")).toBe("postgres://env/db");
});

test("resolveDbUrl falls back to SUPABASE_DB_URL in local.properties", () => {
  const dir = mkdtempSync(join(tmpdir(), "kbx-"));
  const lp = join(dir, "local.properties");
  writeFileSync(
    lp,
    "sdk.dir=/opt/android-sdk\nSUPABASE_DB_URL=postgres://props/db\nfoo=bar\n",
  );
  try {
    expect(resolveDbUrl(lp)).toBe("postgres://props/db");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("resolveDbUrl handles surrounding whitespace and quotes in local.properties", () => {
  const dir = mkdtempSync(join(tmpdir(), "kbx-"));
  const lp = join(dir, "local.properties");
  writeFileSync(lp, '  SUPABASE_DB_URL = "postgres://quoted/db"  \n');
  try {
    expect(resolveDbUrl(lp)).toBe("postgres://quoted/db");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("resolveDbUrl throws a helpful error when nothing is found", () => {
  expect(() => resolveDbUrl("/nonexistent/local.properties")).toThrow(/SUPABASE_DB_URL/);
});
