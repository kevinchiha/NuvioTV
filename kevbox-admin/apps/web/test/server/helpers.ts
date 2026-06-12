import type { FastifyInstance } from "fastify";
import type { Db } from "@kevbox-admin/core";
import { loadEncKey } from "@kevbox-admin/core";
import { buildApp } from "../../src/server/app.js";
import type { Verifier, VerifiedUser } from "../../src/server/auth.js";

export const ADMIN_EMAILS = ["admin@test.dev"];

/**
 * Map of token string -> the user it represents. Lets a test mint "tokens" with
 * arbitrary emails to exercise admin/non-admin/invalid paths without real Supabase.
 */
export function fakeVerifier(tokens: Record<string, VerifiedUser>): Verifier {
  return async (jwt: string) => tokens[jwt] ?? null;
}

/** A verifier where the token IS the email, and "admin@test.dev" is the admin. */
export const tokenIsEmailVerifier: Verifier = async (jwt: string) => {
  if (!jwt) return null;
  return { id: "00000000-0000-0000-0000-000000000000", email: jwt };
};

/**
 * Build a Fastify app bound to a rollback-scoped db and a verifier. The caller is
 * responsible for app.close(); the db is the txn client from withRollback.
 */
export function buildTestApp(db: Db, verifier: Verifier): FastifyInstance {
  return buildApp({ db, verifier, adminEmails: ADMIN_EMAILS });
}

/**
 * Build a test app wired with a kevbox config that writes to `membersFile`. The optional
 * `opts.logStream` is forwarded into the Fastify logger so the redaction test (Step 5b) can capture
 * log lines and assert no key/URL is logged; omit it for the normal route tests.
 */
export function buildKevboxTestApp(
  db: Db,
  membersFile: string,
  opts: { logStream?: { write: (s: string) => void } } = {},
): FastifyInstance {
  return buildApp({
    db, verifier: tokenIsEmailVerifier, adminEmails: ADMIN_EMAILS,
    // Thread the capture stream into buildApp's logger options so the redaction test can read lines.
    ...(opts.logStream ? { loggerStream: opts.logStream } : {}),
    kevbox: {
      encKey: loadEncKey("0".repeat(64)),
      membersFile,
      streamsBaseUrl: "https://streams.kevbox.dev",
      addonSort: 4,
    },
  });
}
