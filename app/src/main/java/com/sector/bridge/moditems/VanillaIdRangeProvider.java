package com.sector.bridge.moditems;

/**
 * Where SSFML gets the current highest item id the vanilla game itself uses, so modded ids never get
 * assigned inside that range.
 * <p>
 * <b>Integration seam:</b> there's no known way yet to ask Sector Space's real item database for its
 * own max id (the game jar isn't decompiled in this repo). Until {@link #setVanillaIdCeiling(int)} is
 * wired up to a real lookup (most likely via a Mixin accessor read once at startup), this falls back
 * to a conservative fixed default that's deliberately far above any plausible vanilla id count.
 */
public final class VanillaIdRangeProvider {

    private static final int DEFAULT_CEILING = 999_999;

    private static volatile int vanillaIdCeiling = DEFAULT_CEILING;

    private VanillaIdRangeProvider() {
    }

    public static int getVanillaIdCeiling() {
        return vanillaIdCeiling;
    }

    /** Call this once the real vanilla id ceiling can actually be read from the game, ideally before any mod content loads. */
    public static void setVanillaIdCeiling(int ceiling) {
        vanillaIdCeiling = ceiling;
    }
}
