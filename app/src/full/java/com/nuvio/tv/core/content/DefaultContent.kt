package com.nuvio.tv.core.content

/**
 * KevBox TV — baked-in default content for a fresh install (full flavor only).
 *
 * This file is the single source of truth the family can edit later:
 *  - [DEFAULT_ADDON_URLS]  : the Stremio addons auto-installed on first launch.
 *  - [DEFAULT_PLUGIN_REPOS]: extra Nuvio/CloudStream scraper repos to seed on first launch.
 *
 * Order matters for addons — it drives catalog row order + stream/source priority.
 *
 * NOTE: [DEFAULT_ADDON_URLS] is mirrored verbatim in
 * `AddonPreferences.getDefaultAddons()` (which lives in `app/src/main` and therefore
 * cannot reference this full-flavor file). If you change the addon list, change it in
 * BOTH places. The plugin list below has no `main` mirror and is the sole definition.
 */
object DefaultContent {

    /**
     * The 4 universal family addons, in install order (Cinemeta first … Netflix catalogs last).
     * Identical for every member — no per-member credentials. Kept here as documentation + for the
     * seed hook to apply order explicitly.
     *
     * 1. Cinemeta — metadata + catalogs
     * 2. OpenSubtitles v3 Pro (EN/FR, AI-translated, auto-adjust)
     * 3. OpenSubtitles v3
     * 4. Streaming-service catalogs (Netflix, etc.)
     *
     * Stream sources (Torrentio + AIOStreams) are PER-MEMBER (each member's own debrid key/URL) and so
     * are NOT baked here — they're added to each member's `member_addon` rows at onboarding
     * (sort_order 4–5). See MEMBER-CONFIG-PLAN.md.
     */
    val DEFAULT_ADDON_URLS: List<String> = listOf(
        "https://v3-cinemeta.strem.io",
        "https://opensubtitlesv3-pro.dexter21767.com/eyJsYW5ncyI6WyJlbmdsaXNoIiwiZnJlbmNoIl0sInNvdXJjZSI6ImFsbCIsImFpVHJhbnNsYXRlZCI6dHJ1ZSwiYXV0b0FkanVzdG1lbnQiOnRydWV9/manifest.json",
        "https://opensubtitles-v3.strem.io",
        "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/bmZ4LGRucCxhbXAsYXRwLGhibSxwY3AsaGx1LHBtcCxuZmssY3RzLG1nbCxjcnUsaGF5LGNsdixnb3AsamhzLHNzdCx2aWwsbmx6LHplZSxjcGQsc3R6LGRwZSxtYmksc29ueWxpdixzZ28sdmlrLHNoZCxiYm8sYWN0LG1wOSxpdHYsaXFpLGNyYyxhbDQsc2hhLGJiYzo6OjE3ODA5MjA3NDkwOTc6MDowOkxC/manifest.json"
    )

    /** How a default plugin repo should be registered (the family can fill this in later). */
    enum class DefaultPluginRepoType {
        /** Auto-detect (JS manifest or external). Resolves cutt.ly short-codes first. */
        AUTO,
        /** Force the Nuvio JS-manifest path. */
        NUVIO_JS,
        /** Force the external DEX/CloudStream path. */
        EXTERNAL_DEX,
    }

    /**
     * A baked-in plugin repository to seed on first launch.
     *
     * @param url      a manifest URL, an external-repo `.json` URL, or a `cutt.ly` short-code.
     * @param type     how to register it (see [DefaultPluginRepoType]).
     */
    data class DefaultPluginRepo(
        val url: String,
        val type: DefaultPluginRepoType = DefaultPluginRepoType.AUTO,
    )

    /**
     * Extra scraper plugin repos to seed on first launch.
     *
     * INTENTIONALLY EMPTY for now: stream sources come from the per-member debrid addons (Torrentio +
     * AIOStreams) added via `member_addon`, so no plugin repos are required. The seeding mechanism is fully wired
     * (see [com.nuvio.tv.core.plugin.PluginManager.seedDefaultPluginsIfFirstLaunch]) — the
     * family can add entries here later (e.g. `DefaultPluginRepo("cspr")` for a cutt.ly
     * short-code, or `DefaultPluginRepo("https://example.com/repo.json", EXTERNAL_DEX)`)
     * and they will be registered on the next fresh install with NO further code changes.
     */
    val DEFAULT_PLUGIN_REPOS: List<DefaultPluginRepo> = emptyList()
}
