package com.sector.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Config API for mods, now that the game's own tooling has no equivalent. A mod declares its
 * config as a list of ConfigEntry (key + default value + optional comment) and calls load() once
 * - SSFMLConfig resolves the file location, creates it if missing, and reconciles it against
 * whatever's already on disk if it exists.
 * <p>
 * Config lives at {@code <gameDir>/config/<modId>/<modId>.cfg>}, resolved via
 * FabricLoader.getInstance().getGameDir() - a mod never needs to pass a path itself.
 * <p>
 * Reconciliation behavior on every load():
 * - A schema key missing from the file gets added with its default value.
 * - An active (uncommented) key in the file that's no longer in the schema gets commented out,
 *   once, with its last known value preserved rather than deleted.
 * - A key that's already commented out from a previous reconciliation is left exactly as-is -
 *   it's never re-processed, so it can't get double-commented or silently come back to life.
 * <p>
 * Typical usage:
 * <pre>
 *   List&lt;SSFMLConfig.ConfigEntry&gt; schema = List.of(
 *           new SSFMLConfig.ConfigEntry("maxDroneCount", "5", "Max drones a station can spawn"),
 *           new SSFMLConfig.ConfigEntry("dronesEnabled", "true")
 *   );
 *   SSFMLConfig.Config config = SSFMLConfig.load("weaponfoundry", schema);
 *
 *   int maxDrones = config.getInt("maxDroneCount");
 *   boolean enabled = config.getBoolean("dronesEnabled");
 *
 *   config.set("maxDroneCount", 8);
 *   config.save();
 * </pre>
 */
public final class SSFMLConfig {

    private SSFMLConfig() {
    }

    public static final class ConfigEntry {
        final String key;
        final String defaultValue;
        final String comment;

        public ConfigEntry(String key, String defaultValue) {
            this(key, defaultValue, null);
        }

        public ConfigEntry(String key, String defaultValue, String comment) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.comment = comment;
        }
    }

    /**
     * Loads (creating if necessary) the config for a given mod id against the given schema,
     * reconciling it against whatever's already on disk. Any I/O failure logs via SSFMLLogger
     * and falls back to an in-memory config built purely from the schema's defaults, so a broken
     * config file never prevents a mod from starting - it just won't persist until fixed.
     */
    public static Config load(String modId, List<ConfigEntry> schema) {
        return load(resolveConfigFile(modId), modId, schema);
    }

    /**
     * Same as load(String, List), but takes the config file path directly instead of resolving it via FabricLoader.getInstance().getGameDir().
     * For Unit testing stuff.
     */
    static Config load(Path configFile, String modId, List<ConfigEntry> schema) {
        Logger log = new Logger(modId);

        Map<String, String> activeValues = new LinkedHashMap<>();
        List<String> retiredLines = new ArrayList<>();

        if (Files.exists(configFile)) {
            try {
                readExisting(configFile, activeValues, retiredLines);
            } catch (IOException e) {
                log.warn("Could not read " + configFile + ", using schema defaults instead: " + e.getMessage());
            }
        }

        Map<String, String> reconciled = new LinkedHashMap<>();
        for (ConfigEntry entry : schema) {
            reconciled.put(entry.key, activeValues.getOrDefault(entry.key, entry.defaultValue));
        }

        // Anything active in the file but not in the current schema gets retired (commented out),
        // preserving its last value instead of silently dropping it.
        for (Map.Entry<String, String> entry : activeValues.entrySet()) {
            boolean stillInSchema = schema.stream().anyMatch(e -> e.key.equals(entry.getKey()));
            if (!stillInSchema) {
                retiredLines.add("#" + entry.getKey() + "=" + entry.getValue() + "  # no longer used by this mod");
                log.info(entry.getKey() + " is no longer part of this mod's config, commenting it out.");
            }
        }

        Config config = new Config(modId, configFile, schema, reconciled, retiredLines, log);
        config.save();
        return config;
    }

    private static Path resolveConfigFile(String modId) {
        Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
        return gameDir.resolve("config").resolve(modId).resolve(modId + ".cfg");
    }

    /**
     * Parses an existing config file into active (uncommented key=value) entries and retired lines.
     */
    private static void readExisting(Path configFile, Map<String, String> activeValues, List<String> retiredLines) throws IOException {
        for (String rawLine : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();

            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("#")) {
                if (isRetiredLine(line)) {
                    retiredLines.add(rawLine);
                }
                continue;
            }

            int eq = line.indexOf('=');
            if (eq <= 0) {
                retiredLines.add(rawLine);
                continue;
            }

            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                activeValues.put(key, value);
            }
        }
    }

    /**
     * A retired line is machine-written with no space after the "#" (e.g. "#oldKey=value...").
     * Every human-readable comment save() writes always has a space after it ("# text").
     */
    private static boolean isRetiredLine(String line) {
        return line.length() > 1 && line.charAt(1) != ' ';
    }

    public static final class Config {
        private final String modId;
        private final Path configFile;
        private final List<ConfigEntry> schema;
        private final Map<String, String> values;
        private final List<String> retiredLines;
        private final Logger log;

        private Config(String modId, Path configFile, List<ConfigEntry> schema,
                       Map<String, String> values, List<String> retiredLines, Logger log) {
            this.modId = modId;
            this.configFile = configFile;
            this.schema = schema;
            this.values = values;
            this.retiredLines = retiredLines;
            this.log = log;
        }

        public String getString(String key) {
            return values.getOrDefault(key, defaultOf(key));
        }

        public int getInt(String key) {
            String raw = getString(key);
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                log.warn(key + " value \"" + raw + "\" is not a valid integer, using default.");
                return Integer.parseInt(defaultOf(key));
            }
        }

        public double getDouble(String key) {
            String raw = getString(key);
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                log.warn(key + " value \"" + raw + "\" is not a valid number, using default.");
                return Double.parseDouble(defaultOf(key));
            }
        }

        public boolean getBoolean(String key) {
            return Boolean.parseBoolean(getString(key));
        }

        public void set(String key, Object value) {
            values.put(key, String.valueOf(value));
        }

        private String defaultOf(String key) {
            for (ConfigEntry entry : schema) {
                if (entry.key.equals(key)) {
                    return entry.defaultValue;
                }
            }
            return "";
        }

        /**
         * Writes the current values back to disk - schema entries first (in schema order, with their comments),
         * then any retired/commented lines preserved from the existing file.
         * Failures are logged via SSFMLLogger.
         */
        public void save() {
            StringBuilder sb = new StringBuilder();
            sb.append("# Config for ").append(modId).append("\n");
            sb.append("# Generated by SSFMLConfig, please edit values.\n\n");

            for (ConfigEntry entry : schema) {
                if (entry.comment != null && !entry.comment.isEmpty()) {
                    sb.append("# ").append(entry.comment).append("\n");
                }
                sb.append(entry.key).append("=").append(values.get(entry.key)).append("\n");
            }

            if (!retiredLines.isEmpty()) {
                sb.append("\n# Retired options (no longer used, kept for reference):\n");
                for (String retiredLine : retiredLines) {
                    sb.append(retiredLine).append("\n");
                }
            }

            try {
                Files.createDirectories(configFile.getParent());
                Files.writeString(configFile, sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Could not save config to " + configFile + ": " + e.getMessage());
            }
        }
    }

    /**
     * Tiny internal wrapper so SSFMLConfig's own messages are attributed to the mod whose config triggered them.
     */
    private static final class Logger {
        private final String modId;

        private Logger(String modId) {
            this.modId = modId;
        }

        void info(String message) {
            SSFMLLogger.info(modId, message);
        }

        void warn(String message) {
            SSFMLLogger.warn(modId, message);
        }
    }
}