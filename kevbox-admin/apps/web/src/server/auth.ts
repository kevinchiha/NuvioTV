import { createClient } from "@supabase/supabase-js";
import type { FastifyReply, FastifyRequest, preHandlerHookHandler } from "fastify";

/** The minimal identity we need out of a verified token. */
export interface VerifiedUser {
  id: string;
  email: string | null;
}

/**
 * Verifies a raw bearer token and returns the user, or null if the token is
 * invalid/expired. Injected into the pre-handler so tests can supply a fake.
 */
export type Verifier = (jwt: string) => Promise<VerifiedUser | null>;

/** Production verifier: validate the JWT with Supabase (anon key) via auth.getUser(jwt). */
export function supabaseVerifier(supabaseUrl: string, supabaseAnonKey: string): Verifier {
  const client = createClient(supabaseUrl, supabaseAnonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  return async (jwt: string): Promise<VerifiedUser | null> => {
    const { data, error } = await client.auth.getUser(jwt);
    if (error || !data.user) return null;
    return { id: data.user.id, email: data.user.email ?? null };
  };
}

/** Extract the bearer token from an Authorization header, or null. */
export function bearerToken(header: string | undefined): string | null {
  if (!header) return null;
  const m = /^Bearer\s+(.+)$/i.exec(header.trim());
  return m ? m[1].trim() : null;
}

/** Augment the request with the authenticated admin (set by the pre-handler). */
declare module "fastify" {
  interface FastifyRequest {
    adminUser?: VerifiedUser;
  }
}

/**
 * Build a pre-handler that: (1) extracts the bearer JWT, (2) verifies it,
 * (3) requires user.email ∈ adminEmails (case-insensitive). On any failure it
 * replies 401/403 and the route handler never runs.
 */
export function requireAdmin(verifier: Verifier, adminEmails: string[]): preHandlerHookHandler {
  const allow = new Set(adminEmails.map((e) => e.toLowerCase()));
  return async (req: FastifyRequest, reply: FastifyReply): Promise<void> => {
    const token = bearerToken(req.headers.authorization);
    if (!token) {
      await reply.code(401).send({ error: "missing bearer token" });
      return;
    }
    const user = await verifier(token);
    if (!user) {
      await reply.code(401).send({ error: "invalid or expired token" });
      return;
    }
    if (!user.email || !allow.has(user.email.toLowerCase())) {
      await reply.code(403).send({ error: "not an admin" });
      return;
    }
    req.adminUser = user;
  };
}
