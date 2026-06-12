import { expect, test } from "vitest";
import { loadEncKey, encryptSecret, decryptSecret } from "../src/crypto.js";

const KEY_HEX = "0".repeat(64); // 32 zero bytes; fine for tests
const key = loadEncKey(KEY_HEX);
const USER = "11111111-1111-1111-1111-111111111111";

test("round-trips a secret", () => {
  const enc = encryptSecret("pm-key-abc", USER, key);
  expect(enc.startsWith("v1.")).toBe(true);
  expect(decryptSecret(enc, USER, key)).toBe("pm-key-abc");
});

test("ciphertexts are non-deterministic (random IV)", () => {
  expect(encryptSecret("x", USER, key)).not.toBe(encryptSecret("x", USER, key));
});

test("wrong AAD (user_id) fails authentication (row-swap defense) — NOT a 400", () => {
  const enc = encryptSecret("secret", USER, key);
  let thrown: (Error & { statusCode?: number }) | undefined;
  try { decryptSecret(enc, "22222222-2222-2222-2222-222222222222", key); }
  catch (e) { thrown = e as Error & { statusCode?: number }; }
  expect(thrown).toBeInstanceOf(Error);
  // auth failure is an internal (key/data) fault, not a client input error → no 400 tag
  expect(thrown!.statusCode).toBeUndefined();
});

test("tampered ciphertext is rejected (auth failure, NOT a 400)", () => {
  const enc = encryptSecret("secret", USER, key);
  const parts = enc.split(".");
  parts[3] = Buffer.from("tampered").toString("base64");
  let thrown: (Error & { statusCode?: number }) | undefined;
  try { decryptSecret(parts.join("."), USER, key); }
  catch (e) { thrown = e as Error & { statusCode?: number }; }
  expect(thrown).toBeInstanceOf(Error);
  expect(thrown!.statusCode).toBeUndefined(); // collapses to a generic 500, not a 400
});

test("malformed input throws a clean 400, not a raw crypto exception", () => {
  const cases = ["garbage", "v2.a.b.c"];
  for (const bad of cases) {
    let thrown: (Error & { statusCode?: number }) | undefined;
    try { decryptSecret(bad, USER, key); }
    catch (e) { thrown = e as Error & { statusCode?: number }; }
    expect(thrown).toBeInstanceOf(Error);
    expect(thrown!.message).toMatch(/malformed/);
    expect(thrown!.statusCode).toBe(400); // malformed input IS a client fault
  }
});

test("loadEncKey: 64 hex chars OR base64, must be 32 bytes", () => {
  expect(loadEncKey(KEY_HEX).length).toBe(32);
  expect(loadEncKey(Buffer.alloc(32, 7).toString("base64")).length).toBe(32);
  expect(() => loadEncKey("tooshort")).toThrow();
  expect(() => loadEncKey(Buffer.alloc(16).toString("base64"))).toThrow(/32 bytes/);
});
