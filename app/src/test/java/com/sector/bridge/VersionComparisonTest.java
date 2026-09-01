package com.sector.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers ModLoader.compareVersions()/evaluateSingleClause()/versionSatisfies()
 */
class VersionComparisonTest {

    @Test
    void compareVersions_equalVersionsReturnZero() {
        assertEquals(0, ModLoader.compareVersions("0.5.9.6", "0.5.9.6"));
    }

    @Test
    void compareVersions_shorterVersionTreatedAsZeroPadded() {
        assertTrue(ModLoader.compareVersions("0.5.9", "0.5.9.6") < 0);
        assertTrue(ModLoader.compareVersions("0.5.9.6", "0.5.9") > 0);
        assertEquals(0, ModLoader.compareVersions("0.5.9.0", "0.5.9"));
    }

    @Test
    void compareVersions_comparesLeftToRight() {
        assertTrue(ModLoader.compareVersions("1.0.0", "0.9.9") > 0);
        assertTrue(ModLoader.compareVersions("0.18.4", "0.18.10") < 0);
    }

    @Test
    void compareVersions_ignoresPrereleaseAndBuildSuffixes() {
        assertEquals(0, ModLoader.compareVersions("1.2.3-beta", "1.2.3+build.7"));
    }

    @Test
    void compareVersions_nonNumericComponentThrows() {
        assertTrueThrowsNumberFormat(() -> ModLoader.compareVersions("abc", "1.0.0"));
    }

    @Test
    void evaluateSingleClause_wildcardAlwaysTrue() {
        assertEquals(Boolean.TRUE, ModLoader.evaluateSingleClause("0.0.0", "*"));
    }

    @Test
    void evaluateSingleClause_operators() {
        assertEquals(Boolean.TRUE, ModLoader.evaluateSingleClause("0.18.4", ">=0.18.4"));
        assertNotEquals(Boolean.TRUE, ModLoader.evaluateSingleClause("0.18.3", ">=0.18.4"));
        assertEquals(Boolean.TRUE, ModLoader.evaluateSingleClause("0.18.4", "<=0.18.4"));
        assertEquals(Boolean.TRUE, ModLoader.evaluateSingleClause("0.18.5", ">0.18.4"));
        assertNotEquals(Boolean.TRUE, ModLoader.evaluateSingleClause("0.18.4", ">0.18.4"));
        assertEquals(Boolean.TRUE, ModLoader.evaluateSingleClause("0.18.4", "<0.19.0"));
        assertEquals(Boolean.TRUE, ModLoader.evaluateSingleClause("0.18.4", "=0.18.4"));
        assertEquals(Boolean.TRUE, ModLoader.evaluateSingleClause("0.18.4", "0.18.4"));
    }

    @Test
    void evaluateSingleClause_unparseableVersionReturnsNull() {
        assertNull(ModLoader.evaluateSingleClause("not-a-version", ">=1.0.0"));
    }

    @Test
    void versionSatisfies_singleClause() {
        assertTrue(ModLoader.versionSatisfies("0.5.9.6", ">=0.5.9.6"));
        assertFalse(ModLoader.versionSatisfies("0.5.9.5", ">=0.5.9.6"));
    }

    @Test
    void versionSatisfies_multipleAndClauses() {
        assertTrue(ModLoader.versionSatisfies("0.5.5.0", ">=0.5.0 <0.7.0"));
        assertFalse(ModLoader.versionSatisfies("0.8.0.0", ">=0.5.0 <0.7.0"));
    }

    @Test
    void versionSatisfies_shortCircuitsOnFirstUnparseableClause() {
        assertNull(ModLoader.versionSatisfies("1.0.0", "~1.0 >=1.0.0"));
    }

    private static void assertTrueThrowsNumberFormat(Runnable action) {
        try {
            action.run();
        } catch (NumberFormatException expected) {
            return;
        }
        throw new AssertionError("Expected NumberFormatException");
    }
}
