package com.sector.bridge;

/**
 * Logging API for mods to use, now that the game's own mods.ModLogger is deprecated.
 * Routes through the exact same StartupLogger tee everything else in SSFML_startup_log.txt uses.
 */
public final class SSFMLLogger {

    private SSFMLLogger() {
    }

    public static void info(String modId, String message) {
        StartupLogger.log("INFO", message);
    }

    public static void warn(String modId, String message) {
        StartupLogger.log("WARN", message);
    }

    public static void error(String modId, String message) {
        StartupLogger.log("ERROR", message);
    }

    /**
     * Convenience for replacing old ModLogger.log(String)
     */
    public static void log(String message) {
        StartupLogger.log("INFO", message);
    }
}