#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import pg from "pg";

const { Pool } = pg;
const BASE_URL = "https://streams.kevbox.dev";
const KEVBOX_URL_PREFIX = `${BASE_URL}/stremio/k/`;
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const API_KEY_REGEX = /^[A-Za-z0-9_-]{8,128}$/;
const MEMBER_NAME_REGEX = /^[a-z0-9._+-]{1,64}$/;
const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const DEFAULT_LOCAL_PROPERTIES = resolve(SCRIPT_DIR, "../../local.properties");

function decodeXmlText(value) {
  return value
    .replace(/<text:line-break\s*\/>/g, "\n")
    .replace(/<text:s(?:\s+text:c="(\d+)")?\s*\/>/g, (_match, count) =>
      " ".repeat(Number(count ?? 1)),
    )
    .replace(/<[^>]+>/g, "")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/&#(\d+);/g, (_match, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_match, code) =>
      String.fromCodePoint(Number.parseInt(code, 16)),
    )
    .trim();
}

export function parseSpreadsheetRows(contentXml) {
  const rows = [];
  const rowRegex = /<table:table-row\b[^>]*>([\s\S]*?)<\/table:table-row>/g;
  const cellRegex =
    /<table:(?:table-cell|covered-table-cell)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/table:(?:table-cell|covered-table-cell)>)/g;

  for (const rowMatch of contentXml.matchAll(rowRegex)) {
    const cells = [];
    for (const cellMatch of rowMatch[1].matchAll(cellRegex)) {
      const repeat = Math.min(
        Number(/table:number-columns-repeated="(\d+)"/.exec(cellMatch[1])?.[1] ?? 1),
        2,
      );
      const value = decodeXmlText(cellMatch[2] ?? "");
      for (let i = 0; i < repeat && cells.length < 2; i += 1) cells.push(value);
      if (cells.length === 2) break;
    }
    rows.push({ email: (cells[0] ?? "").trim(), apiKey: (cells[1] ?? "").trim() });
  }
  return rows;
}

function readOdsRows(path) {
  let contentXml;
  try {
    contentXml = execFileSync("unzip", ["-p", path, "content.xml"], {
      encoding: "utf8",
      maxBuffer: 10_000_000,
    });
  } catch (error) {
    throw new Error(`Could not read content.xml from ${path}: ${error.message}`);
  }
  return parseSpreadsheetRows(contentXml);
}

function prepareEntries(rows) {
  const byEmail = new Map();
  let incomplete = 0;
  let invalid = 0;
  let duplicateRows = 0;
  let conflictingDuplicates = 0;

  for (const row of rows) {
    const email = row.email.toLowerCase();
    const apiKey = row.apiKey;
    if (!email || !apiKey) {
      if (email || apiKey) incomplete += 1;
      continue;
    }
    if (!EMAIL_REGEX.test(email) || !API_KEY_REGEX.test(apiKey)) {
      invalid += 1;
      continue;
    }
    const name = email.slice(0, email.indexOf("@"));
    if (!MEMBER_NAME_REGEX.test(name)) {
      invalid += 1;
      continue;
    }
    const previous = byEmail.get(email);
    if (previous) {
      duplicateRows += 1;
      if (previous.apiKey !== apiKey) conflictingDuplicates += 1;
    }
    byEmail.set(email, {
      email,
      apiKey,
      name,
      url: `${KEVBOX_URL_PREFIX}${name}/${apiKey}/manifest.json`,
    });
  }

  return {
    entries: [...byEmail.values()],
    incomplete,
    invalid,
    duplicateRows,
    conflictingDuplicates,
  };
}

function resolveDbUrl() {
  const fromEnv = process.env.SUPABASE_DB_URL?.trim();
  if (fromEnv) return fromEnv;
  const line = readFileSync(DEFAULT_LOCAL_PROPERTIES, "utf8")
    .split(/\r?\n/)
    .find((candidate) => candidate.trim().startsWith("SUPABASE_DB_URL="));
  if (!line) throw new Error(`SUPABASE_DB_URL not found in env or ${DEFAULT_LOCAL_PROPERTIES}`);
  return line
    .slice(line.indexOf("=") + 1)
    .trim()
    .replace(/^(["'])|(["'])$/g, "");
}

function createPool() {
  const url = new URL(resolveDbUrl());
  const wantsSsl = url.searchParams.has("sslmode") || /supabase\.(co|com)$/.test(url.hostname);
  url.searchParams.delete("sslmode");
  return new Pool({
    connectionString: url.toString(),
    ssl: wantsSsl ? { rejectUnauthorized: false } : undefined,
  });
}

async function buildPlan(db, entries) {
  const { rows: users } = await db.query(
    "select id, lower(email) as email from public.kevbox_auth_users where email is not null",
  );
  const { rows: existingRows } = await db.query(
    `select user_id, url, enabled
       from public.member_addon
      where url like $1`,
    [`${KEVBOX_URL_PREFIX}%`],
  );
  const byEmail = new Map(entries.map((entry) => [entry.email, entry]));
  const matched = users
    .map((user) => ({ ...user, entry: byEmail.get(user.email) }))
    .filter((user) => user.entry);
  const databaseEmails = new Set(users.map((user) => user.email));
  const existingByUser = new Map();
  for (const row of existingRows) {
    const rows = existingByUser.get(row.user_id) ?? [];
    rows.push(row);
    existingByUser.set(row.user_id, rows);
  }
  let exactEnabledMatches = 0;
  let missingOrStaleMatches = 0;
  for (const user of matched) {
    const existing = existingByUser.get(user.id) ?? [];
    const exact = existing.filter((row) => row.url === user.entry.url && row.enabled);
    const stale = existing.filter((row) => row.url !== user.entry.url);
    if (exact.length === 1 && stale.length === 0) exactEnabledMatches += 1;
    else missingOrStaleMatches += 1;
  }
  return {
    matched,
    databaseUsers: users.length,
    databaseUsersWithoutPair: users.length - matched.length,
    spreadsheetEmailsWithoutUser: entries.filter((entry) => !databaseEmails.has(entry.email)).length,
    existingKevboxRows: existingRows.length,
    exactEnabledMatches,
    missingOrStaleMatches,
  };
}

async function applyPlan(pool, matched) {
  const client = await pool.connect();
  let inserted = 0;
  let refreshed = 0;
  let staleDeleted = 0;
  try {
    await client.query("begin");
    for (const { id: userId, entry } of matched) {
      const { rows: currentRows } = await client.query(
        `select id, url, sort_order
           from public.member_addon
          where user_id = $1 and url like $2
          order by sort_order, id`,
        [userId, `${KEVBOX_URL_PREFIX}%`],
      );
      const target = currentRows.find((row) => row.url === entry.url);
      const preferredSortOrder = target?.sort_order ?? currentRows[0]?.sort_order ?? null;
      const { rowCount } = await client.query(
        `insert into public.member_addon (user_id, url, enabled, sort_order)
         values (
           $1, $2, true,
           coalesce($3, (
             select coalesce(max(sort_order) + 1, 0)
               from public.member_addon
              where user_id = $1
           ))
         )
         on conflict (user_id, url)
           do update set enabled = true, updated_at = now()`,
        [userId, entry.url, preferredSortOrder],
      );
      if (target) refreshed += rowCount ?? 0;
      else inserted += rowCount ?? 0;

      const deleted = await client.query(
        `delete from public.member_addon
          where user_id = $1 and url like $2 and url <> $3`,
        [userId, `${KEVBOX_URL_PREFIX}%`, entry.url],
      );
      staleDeleted += deleted.rowCount ?? 0;
    }
    await client.query("commit");
    return { inserted, refreshed, staleDeleted };
  } catch (error) {
    await client.query("rollback");
    throw error;
  } finally {
    client.release();
  }
}

function parseArgs(argv) {
  const positional = argv.filter((arg) => !arg.startsWith("--"));
  const outputFlag = argv.indexOf("--members-output");
  return {
    odsPath: positional[0],
    apply: argv.includes("--apply"),
    membersOutput: outputFlag === -1 ? null : argv[outputFlag + 1],
  };
}

async function main() {
  const { odsPath, apply, membersOutput } = parseArgs(process.argv.slice(2));
  if (!odsPath) {
    throw new Error(
      "Usage: npm run import-global-vision -- <file.ods> [--apply] [--members-output <path>]",
    );
  }

  const prepared = prepareEntries(readOdsRows(resolve(odsPath)));
  const pool = createPool();
  try {
    const plan = await buildPlan(pool, prepared.entries);
    if (membersOutput) {
      writeFileSync(
        resolve(membersOutput),
        `${plan.matched.map(({ entry }) => entry.name).sort().join(",")}\n`,
        { mode: 0o600 },
      );
    }
    const changes = apply ? await applyPlan(pool, plan.matched) : null;
    console.log(
      JSON.stringify(
        {
          mode: apply ? "applied" : "dry-run",
          spreadsheet: {
            uniqueUsableEmails: prepared.entries.length,
            incompleteRowsIgnored: prepared.incomplete,
            invalidRowsIgnored: prepared.invalid,
            duplicateRowsUsingLastOccurrence: prepared.duplicateRows,
            conflictingDuplicateRowsUsingLastOccurrence: prepared.conflictingDuplicates,
          },
          database: {
            users: plan.databaseUsers,
            matched: plan.matched.length,
            usersWithoutUsablePair: plan.databaseUsersWithoutPair,
            spreadsheetEmailsWithoutUser: plan.spreadsheetEmailsWithoutUser,
            existingKevboxRows: plan.existingKevboxRows,
            exactEnabledMatches: plan.exactEnabledMatches,
            missingOrStaleMatches: plan.missingOrStaleMatches,
          },
          changes,
          membersOutput: membersOutput ? resolve(membersOutput) : null,
        },
        null,
        2,
      ),
    );
  } finally {
    await pool.end();
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
