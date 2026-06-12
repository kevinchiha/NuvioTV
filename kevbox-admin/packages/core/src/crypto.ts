import { randomBytes, createCipheriv, createDecipheriv } from "node:crypto";

const VERSION = "v1";

/**
 * A typed error so the web layer never leaks a raw node:crypto exception. MALFORMED input
 * (bad structure / wrong version / wrong field lengths) is a client fault → statusCode 400.
 * Authentication failure (createDecipheriv/final rejects: wrong key, corrupted data, AAD
 * mismatch) is NOT a client-correctable input error — leave statusCode UNSET so the web layer
 * collapses it to a generic 500 "internal error" and logs it at error level (a key/data fault
 * must not masquerade as a 400 "bad request").
 */
function malformedError(message: string): Error {
  const e = new Error(message) as Error & { statusCode?: number };
  e.statusCode = 400;
  return e;
}
function authFailureError(message: string): Error {
  // No statusCode → the Fastify error handler maps it to 500 (internal), logged at error level.
  return new Error(message);
}

/**
 * Decode KEVBOX_ENC_KEY → a 32-byte key. Encoding rule (no ambiguity, §5):
 * exactly 64 hex chars → hex; else base64. Result MUST be 32 bytes.
 */
export function loadEncKey(raw: string): Buffer {
  const s = (raw ?? "").trim();
  const key = /^[0-9a-fA-F]{64}$/.test(s) ? Buffer.from(s, "hex") : Buffer.from(s, "base64");
  if (key.length !== 32) {
    throw new Error("KEVBOX_ENC_KEY must decode to 32 bytes (64 hex chars or base64)");
  }
  return key;
}

/** AES-256-GCM encrypt, AAD-bound to userId. Format: v1.b64(iv).b64(tag).b64(ct). */
export function encryptSecret(plain: string, userId: string, key: Buffer): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(userId, "utf8"));
  const ct = Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return [VERSION, iv.toString("base64"), tag.toString("base64"), ct.toString("base64")].join(".");
}

/** Reverse of encryptSecret. Validates structure + AAD; throws a typed error on any mismatch. */
export function decryptSecret(enc: string, userId: string, key: Buffer): string {
  const parts = (enc ?? "").split(".");
  if (parts.length !== 4 || parts[0] !== VERSION) throw malformedError("malformed ciphertext");
  const iv = Buffer.from(parts[1], "base64");
  const tag = Buffer.from(parts[2], "base64");
  const ct = Buffer.from(parts[3], "base64");
  if (iv.length !== 12 || tag.length !== 16) throw malformedError("malformed ciphertext");
  try {
    const decipher = createDecipheriv("aes-256-gcm", key, iv);
    decipher.setAAD(Buffer.from(userId, "utf8"));
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(ct), decipher.final()]).toString("utf8");
  } catch {
    // NOT a 400: a key/data/AAD fault collapses to a generic internal error (500), logged at error.
    throw authFailureError("ciphertext authentication failed");
  }
}
