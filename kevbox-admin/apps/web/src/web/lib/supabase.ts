import { createClient, type SupabaseClient } from "@supabase/supabase-js";

// Public anon credentials baked into the SPA at build time (Vite import.meta.env).
const url = import.meta.env.VITE_SUPABASE_URL as string;
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY as string;

export const supabase: SupabaseClient = createClient(url, anonKey);

/** Sign in with email/password; returns the access token (JWT) for /api calls. */
export async function signIn(email: string, password: string): Promise<string> {
  const { data, error } = await supabase.auth.signInWithPassword({ email, password });
  if (error || !data.session) throw new Error(error?.message ?? "sign-in failed");
  return data.session.access_token;
}

/** Current access token if a session exists, else null. */
export async function currentToken(): Promise<string | null> {
  const { data } = await supabase.auth.getSession();
  return data.session?.access_token ?? null;
}

export async function signOut(): Promise<void> {
  await supabase.auth.signOut();
}
