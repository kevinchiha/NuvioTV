import { expect, test } from "vitest";
import { bearerToken } from "../../src/server/auth.js";

test("bearerToken parses a valid Authorization header", () => {
  expect(bearerToken("Bearer abc.def.ghi")).toBe("abc.def.ghi");
  expect(bearerToken("bearer xyz")).toBe("xyz");
});

test("bearerToken returns null for missing or malformed headers", () => {
  expect(bearerToken(undefined)).toBeNull();
  expect(bearerToken("")).toBeNull();
  expect(bearerToken("Basic abc")).toBeNull();
  expect(bearerToken("Bearer ")).toBeNull();
});
