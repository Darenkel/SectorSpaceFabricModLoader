package com.sector.bridge;

/**
 * Logging API for mods to use, now that the game's own mods.ModLogger is deprecated.
 * Routes through the exact same StartupLogger tee everything else in SSFML_startup_log.txt uses.
 * Unlike the old ModLogger.log(String), every call here is attributed to a mod id, so a shared log with many mods writing to it stays readable.
 */
public final class SSFMLLogger {

    private SSFMLLogger() {
    }

    public static void info(String modId, String message) {
        StartupLogger.log("INFO", "[" + modId + "] " + message);
    }

    public static void warn(String modId, String message) {
        StartupLogger.log("WARN", "[" + modId + "] " + message);
    }

    public static void error(String modId, String message) {
        StartupLogger.log("ERROR", "[" + modId + "] " + message);
    }

    /**
     * Convenience for replacing old ModLogger.log(String)
     */
    public static void log(String modId, String message) {
        StartupLogger.log("LOG", "[" + modId + "] " + message);
    }
}