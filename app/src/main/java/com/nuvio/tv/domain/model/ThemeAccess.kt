package com.nuvio.tv.domain.model

private val supporterThemes = linkedMapOf(
    AppTheme.GOLD to CosmeticEntitlement.GOLD_THEME,
    AppTheme.JADE to CosmeticEntitlement.JADE_THEME,
    AppTheme.ROSE_GOLD to CosmeticEntitlement.ROSE_GOLD_THEME,
    AppTheme.ARCTIC_BLUE to CosmeticEntitlement.ARCTIC_BLUE_THEME,
    AppTheme.GRAPHITE to CosmeticEntitlement.GRAPHITE_THEME
)

private val standardThemes = listOf(AppTheme.WHITE) + AppTheme.entries.filterNot {
    it == AppTheme.WHITE || it in supporterThemes
}

fun availableAppThemes(entitlements: CosmeticEntitlements): List<AppTheme> {
    val unlockedSupporterThemes = supporterThemes
        .filterValues(entitlements::includes)
        .keys
        .toList()
    return unlockedSupporterThemes + standardThemes
}

fun resolveAppTheme(
    selectedTheme: AppTheme?,
    entitlements: CosmeticEntitlements
): AppTheme {
    // KevBox FORK DIVERGENCE: fall back to OCEAN, not WHITE (moved here from ThemeDataStore
    // when upstream made the preference nullable in 0.8.6).
    if (selectedTheme == null) {
        return supporterThemes
            .filterValues(entitlements::includes)
            .keys
            .firstOrNull()
            ?: AppTheme.OCEAN
    }
    return if (selectedTheme in availableAppThemes(entitlements)) {
        selectedTheme
    } else {
        AppTheme.OCEAN
    }
}
