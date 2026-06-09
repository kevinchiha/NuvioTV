import type { FastifyInstance } from "fastify";
import type { Db } from "@kevbox-admin/core";
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
