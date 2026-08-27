package com.sector.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private static final String CONFIG_HEADER = "# Auto-generated mod list, start game to update.\n# Otherwise, add in per-line format: ExJar.jar, true/false.\n";
    private static final String DISABLED_SUFFIX = ".disabled";

    /**
     * Syncs mod_list.cfg against what is current in mods/, then renames each jar on disk to match the set state.
     * This is called once per launch, before Fabric/Knot starts.
     * Fabric will then scan and loads mods/ once this returns.
     */
    public void applyModState(File gameDir) {
        Path modsDir = gameDir.toPath().resolve("mods");
        Path configPath = modsDir.resolve("mod_list.cfg");

        try {
            Files.createDirectories(modsDir);

            Map<String, Boolean> existing = readConfig(configPath);

            resolveDuplicates(modsDir, existing);

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

            logMountedMods(rebuilt);

        } catch (IOException e) {
            System.err.println("SSFML: Failed applying mod state: " + e.getMessage());
        }
    }

    /**
     * Prints the full set of currently-enabled mods every launch,
     * not just the ones that changed state this run.
     * So the startup log always shows a more complete picture of what's actually about to load.
     */
    private void logMountedMods(Map<String, Boolean> rebuilt) {
        List<String> mounted = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : rebuilt.entrySet()) {
            if (entry.getValue()) {
                mounted.add(entry.getKey());
            }
        }
        Collections.sort(mounted, String.CASE_INSENSITIVE_ORDER);

        if (mounted.isEmpty()) {
            System.out.println("SSFML: No mods enabled.");
            return;
        }

        System.out.println("SSFML: Enabled " + mounted.size() + " mod(s):");
        for (String name : mounted) {
            System.out.println("SSFML:  - " + name);
        }
    }

    /**
     * Finds any canonical mod name that currently exists as BOTH "Example.jar" and "Example.jar.disabled"
     * and deletes whichever copy does NOT match that mod's current cfg state (defaulting to disabled if the mod isn't in the cfg yet).
     * Logs every resolution so a duplicate just in case.
     */
    private void resolveDuplicates(Path modsDir, Map<String, Boolean> existing) throws IOException {
        File[] all = modsDir.toFile().listFiles();
        if (all == null) {
            return;
        }

        Map<String, File> enabledCopies = new LinkedHashMap<>();
        Map<String, File> disabledCopies = new LinkedHashMap<>();

        for (File f : all) {
            if (!f.isFile()) continue;
            String name = f.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jar")) {
                enabledCopies.put(name, f);
            } else if (lower.endsWith(".jar" + DISABLED_SUFFIX)) {
                String canonical = name.substring(0, name.length() - DISABLED_SUFFIX.length());
                disabledCopies.put(canonical, f);
            }
        }

        for (String canonicalName : disabledCopies.keySet()) {
            if (!enabledCopies.containsKey(canonicalName)) {
                continue;
            }

            boolean shouldBeEnabled = existing.getOrDefault(canonicalName, false);
            File enabledFile = enabledCopies.get(canonicalName);
            File disabledFile = disabledCopies.get(canonicalName);

            File loser = shouldBeEnabled ? disabledFile : enabledFile;
            String loserPath = loser.getAbsolutePath();

            if (Files.deleteIfExists(loser.toPath())) {
                System.out.println("SSFML: Found both an enabled and disabled copy of " + canonicalName
                        + " -- kept the " + (shouldBeEnabled ? "enabled" : "disabled")
                        + " one per mod_list.cfg and deleted " + loserPath);
            }
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