import { expect, test } from "vitest";
import { buildTorrentioUrl, DEBRID_TORRENTIO_SORT, DEBRID_AIOSTREAMS_SORT } from "../src/defaults.js";

test("buildTorrentioUrl injects the premiumize key and keeps the manifest suffix", () => {
  const url = buildTorrentioUrl("ABC123key");
  expect(url).toContain("premiumize=ABC123key");
  expect(url.startsWith("https://torrentio.strem.fun/")).toBe(true);
  expect(url.endsWith("/manifest.json")).toBe(true);
});

// DRIFT GUARD: this exact string MUST match the Torrentio template in
// MEMBER-DEBRID-ONBOARDING.md (runbook line ~52). The spec (§4.3) names "keep in sync with the
// runbook" as a hazard with no enforcement; this pinned assertion IS the enforcement — if either
// side changes, this test fails and forces both to be updated together.
test("buildTorrentioUrl matches the MEMBER-DEBRID-ONBOARDING.md template byte-for-byte", () => {
  expect(buildTorrentioUrl("KEY")).toBe(
    "https://torrentio.strem.fun/qualityfilter=unknown,cam,4k,scr|limit=5|sizefilter=4GB|" +
      "debridoptions=nodownloadlinks,nocatalog|premiumize=KEY/manifest.json",
  );
});

test("debrid sort positions are 4 (torrentio) and 5 (aiostreams), after the 4 universal defaults (0-3)", () => {
  expect(DEBRID_TORRENTIO_SORT).toBe(4);
  expect(DEBRID_AIOSTREAMS_SORT).toBe(5);
});
