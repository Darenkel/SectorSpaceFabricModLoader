package com.sector.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Resolves the active classpath for Fabric/Knot by reading the managed mod list.
 *
 * Behavior:
 * - reads mods/mod_list.cfg
 * - keeps only entries marked enabled (1)
 * - falls back safely if the config is empty or missing
 * - does not mutate the UI or launch process directly
 */
public class ModLoader {

    /**
     * Returns the mod JAR files that are currently enabled.
     *
     * Each config line is expected to follow:
     *   SomeMod.jar,1
     *
     * Disabled entries use:
     *   SomeMod.jar,0
     *
     * Blank lines and comment lines are ignored.
     */
    public List<File> getEnabledModFiles(File gameDir) {
        Path modsDir = gameDir.toPath().resolve("mods");
        Path configPath = modsDir.resolve("mod_list.cfg");

        List<File> enabled = new ArrayList<>();

        try {
            Files.createDirectories(modsDir);

            if (Files.notExists(configPath)) {
                Files.writeString(configPath, "# Auto-generated mod list\n", StandardCharsets.UTF_8);
            }

            Set<String> seen = new LinkedHashSet<>();
            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);

            for (String raw : lines) {
                String line = raw == null ? "" : raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split(",", 2);
                String jarName = parts[0].trim();
                if (jarName.isEmpty()) {
                    continue;
                }

                String enabledValue = parts.length > 1 ? parts[1].trim() : "0";
                if (!enabledValue.equals("0") && !enabledValue.equals("1")) {
                    enabledValue = "0";
                }

                if (enabledValue.equals("1")) {
                    File f = modsDir.resolve(jarName).toFile();
                    if (f.exists() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        enabled.add(f);
                        seen.add(f.getName());
                    }
                }
            }

            // fallback for stale config or config with only comments
            if (enabled.isEmpty()) {
                try (var stream = Files.list(modsDir)) {
                    stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                            .forEach(path -> {
                                if (!seen.contains(path.getFileName().toString())) {
                                    enabled.add(path.toFile());
                                }
                            });
                }
            }

        } catch (IOException ignored) {
        }

        return enabled;
    }

    /**
     * Converts the enabled mod list into a classpath-friendly string
     * separated by the OS path separator.
     */
    public String getEnabledMods(File gameDir) {
        List<File> files = getEnabledModFiles(gameDir);
        if (files.isEmpty()) {
            return "";
        }

        StringJoiner joiner = new StringJoiner(File.pathSeparator);
        for (File file : files) {
            joiner.add(file.getAbsolutePath());
        }

        return joiner.toString();
    }
}