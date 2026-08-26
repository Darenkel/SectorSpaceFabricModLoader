package com.sector.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * This owns the mods/mod_list.cfg and enforces its true/false state directly on the files in mods/
 *
 * Fabric's DirectoryModCandidateFinder only accepts files whose name ends in ".jar".
 * As put in net.fabricmc.loader.impl.discovery.DirectoryModCandidateFinder#isValidFile)
 * So this is my current workaround:
 *  - enabled ("true")  -> file ends in ".jar" (Fabric loads it)
 *  - disabled ("false")-> file ends in ".jar.disabled" (Fabric ignores it)
 *
 * Config format:
 *  ModJar.jar, true
 *  ModJar2.jar, false
 */

public class ModLoader {

    private static final String CONFIG_HEADER = "# Auto-generated mod list, start game to update.\n# Otherwise, add in per-line formate: ExJar.jar, true/false.\n";
    private static final String DISABLED_SUFFIX = ".disabled";

    /**
     * This syncs the mod_list.cfg against what is current in mods/, then renames each jar on disk to match the set state.
     * This is called once per launch, before Fabric/Knot starts.
     * Fabric will then scan and loads mods/ once this returns.
     */
    public void applyModState(File gameDir) {
        Path modsDir = gameDir.toPath().resolve("mods");
        Path configPath = modsDir.resolve("mod_list.cfg");

        try {
            Files.createDirectories(modsDir);

            Map<String, Boolean> existing = readConfig(configPath);

            // Canonical name on-disk and whether it's currently enabled or disabled.
            Map<String, File> currentFiles = getStringFileMap(modsDir);

            Map<String, Boolean> rebuilt = new LinkedHashMap<>();
            for (String canonicalName : currentFiles.keySet()) {
                rebuilt.put(canonicalName, existing.getOrDefault(canonicalName, false));
            }

            writeConfig(configPath, rebuilt);

            for (Map.Entry<String, Boolean> entry : rebuilt.entrySet()) {
                String canonicalName = entry.getKey();
                boolean enabled = entry.getValue();
                File current = currentFiles.get(canonicalName);
                if (current == null) {
                    continue;
                }

                File target = enabled
                        ? modsDir.resolve(canonicalName).toFile()
                        : modsDir.resolve(canonicalName + DISABLED_SUFFIX).toFile();

                if (!current.equals(target)) {
                    Files.move(current.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("SSFML: " + canonicalName + " -> " + (enabled ? "enabled" : "disabled"));
                }
            }

        } catch (IOException e) {
            System.err.println("SSFML: failed applying mod state: " + e.getMessage());
        }
    }

    private Map<String, File> getStringFileMap(Path modsDir) {
        Map<String, File> currentFiles = new LinkedHashMap<>();
        File[] all = modsDir.toFile().listFiles();
        if (all != null) {
            for (File f : all) {
                if (!f.isFile()) continue;
                String name = f.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".jar")) {
                    currentFiles.put(name, f);
                } else if (lower.endsWith(".jar" + DISABLED_SUFFIX)) {
                    String canonical = name.substring(0, name.length() - DISABLED_SUFFIX.length());
                    currentFiles.put(canonical, f);
                }
            }
        }
        return currentFiles;
    }

    private Map<String, Boolean> readConfig(Path configPath) throws IOException {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (!Files.exists(configPath)) {
            return result;
        }

        for (String raw : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            String[] parts = line.split(",", 2);
            if (parts.length < 2) {
                continue;
            }

            String jarName = parts[0].trim();
            if (jarName.isEmpty()) {
                continue;
            }

            boolean enabled = Boolean.parseBoolean(parts[1].trim());
            result.put(jarName, enabled);
        }
        return result;
    }

    private void writeConfig(Path configPath, Map<String, Boolean> entries) throws IOException {
        StringBuilder sb = new StringBuilder(CONFIG_HEADER);
        for (Map.Entry<String, Boolean> entry : entries.entrySet()) {
            sb.append(entry.getKey()).append(", ").append(entry.getValue()).append("\n");
        }
        Files.writeString(configPath, sb.toString(), StandardCharsets.UTF_8);
    }
}