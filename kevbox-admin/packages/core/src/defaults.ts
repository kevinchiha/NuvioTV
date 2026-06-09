// Debrid URL templates — MIRROR of MEMBER-DEBRID-ONBOARDING.md (KevBox repo). Keep in sync.
// Torrentio qualityfilter is an EXCLUDE list (filters OUT unknown/cam/4k/scr).

export const DEBRID_TORRENTIO_SORT = 4;
export const DEBRID_AIOSTREAMS_SORT = 5;

/** Build a member's Torrentio (Premiumize) manifest URL from their own key. */
export function buildTorrentioUrl(premiumizeKey: string): string {
  const key = premiumizeKey.trim();
  if (!key) throw new Error("premiumizeKey is required");
  return (
    "https://torrentio.strem.fun/" +
    "qualityfilter=unknown,cam,4k,scr|limit=5|sizefilter=4GB|" +
    "debridoptions=nodownloadlinks,nocatalog|" +
    `premiumize=${key}/manifest.json`
  );
}
