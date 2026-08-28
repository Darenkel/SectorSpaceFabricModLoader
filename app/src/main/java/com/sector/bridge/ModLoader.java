package com.sector.bridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * This owns the mods/mod_list.cfg and enforces its true/false state directly on the files in mods/
 * <p>
 * Fabric's DirectoryModCandidateFinder only accepts files whose name ends in ".jar".
 * As put in net.fabricmc.loader.impl.discovery.DirectoryModCandidateFinder#isValidFile
 * So this is my current workaround:
 *  - enabled ("true")  -> file ends in ".jar" (Fabric loads it)
 *  - disabled ("false")-> file ends in ".jar.disabled" (Fabric ignores it)
 * <p>
 * Config format:
 *  ModJar.jar, true
 *  ModJar2.jar, false
 */

public class ModLoader {

    private static final String CONFIG_HEADER = "# Auto-generated mod list, start game to update.\n# Otherwise, add in per-line format: ExJar.jar, true/false.\n";
    private static final String DISABLED_SUFFIX = ".disabled";

    private static final Pattern DEPENDS_BLOCK_PATTERN = Pattern.compile("\"depends\"\\s*:\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern DEPENDS_ENTRY_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

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

            logGameVersionCompatibility(currentFiles, rebuilt);

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
     * Information scan for each currently enabled mod, check if it declares any "depends" entries against
     * sector-space, fabricloader, java, and any other enabled mod's own id/version then log whether its requirement is satisfied or not.
     * Does not disable a mod or block launch, up to user to figure out the problem.
     * This runs before Fabric itself starts, so it cannot actually see Fabric's own dependency thing.
     */
    private void logGameVersionCompatibility(Map<String, File> currentFiles, Map<String, Boolean> rebuilt) {
        String currentGameVersion = LocVerifierCFG.getNormalizedGameVersion();
        String fabricLoaderVersion = net.fabricmc.loader.impl.FabricLoaderImpl.VERSION;
        String javaVersion = System.getProperty("java.version");

        // Build modId -> version for every currently-enabled mod, so mod-to-mod
        // dependencies can be checked the same way the special-cased ones are.
        Map<String, String> enabledModVersions = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : rebuilt.entrySet()) {
            if (!entry.getValue()) {
                continue;
            }
            File modFile = currentFiles.get(entry.getKey());
            if (modFile == null) {
                continue;
            }
            String[] idAndVersion = readModIdAndVersion(modFile);
            if (idAndVersion != null) {
                enabledModVersions.put(idAndVersion[0], idAndVersion[1]);
            }
        }

        // Same idea, but for disabled mods, lets the fallback case below tell the difference
        // between "not installed at all" and "installed but currently disabled."
        Map<String, String[]> disabledModsById = new LinkedHashMap<>(); // id -> {canonicalName, version}
        for (Map.Entry<String, Boolean> entry : rebuilt.entrySet()) {
            if (entry.getValue()) {
                continue;
            }
            File modFile = currentFiles.get(entry.getKey());
            if (modFile == null) {
                continue;
            }
            String[] idAndVersion = readModIdAndVersion(modFile);
            if (idAndVersion != null) {
                disabledModsById.put(idAndVersion[0], new String[] { entry.getKey(), idAndVersion[1] });
            }
        }

        for (Map.Entry<String, Boolean> entry : rebuilt.entrySet()) {
            if (!entry.getValue()) {
                continue;
            }

            String canonicalName = entry.getKey();
            File modFile = currentFiles.get(canonicalName);
            if (modFile == null) {
                continue;
            }

            Map<String, String> depends = readDependsBlock(modFile);
            if (depends.isEmpty()) {
                System.out.println("SSFML: " + canonicalName + " declares no dependencies to check.");
                continue;
            }

            for (Map.Entry<String, String> dep : depends.entrySet()) {
                String depId = dep.getKey();
                String requiredRange = dep.getValue();
                String actualVersion;

                switch (depId) {
                    case "sector-space":
                        actualVersion = currentGameVersion;
                        break;
                    case "fabricloader":
                        actualVersion = fabricLoaderVersion;
                        break;
                    case "java":
                        actualVersion = javaVersion;
                        break;
                    default:
                        actualVersion = enabledModVersions.get(depId);
                        if (actualVersion == null) {
                            String[] disabled = disabledModsById.get(depId);
                            if (disabled != null) {
                                String disabledCanonicalName = disabled[0];
                                String disabledVersion = disabled[1];
                                Boolean wouldSatisfy = versionSatisfies(disabledVersion, requiredRange);

                                String suffix = Boolean.TRUE.equals(wouldSatisfy)
                                        ? " Its version (" + disabledVersion + ") would satisfy this requirement."
                                        : Boolean.FALSE.equals(wouldSatisfy)
                                        ? " Its version (" + disabledVersion + ") would NOT satisfy this requirement even if re-enabled."
                                        : "";

                                System.out.println("SSFML: " + canonicalName + " requires " + depId + " " + requiredRange
                                        + " - found as " + disabledCanonicalName + " but it is currently disabled."
                                        + " Consider re-enabling it." + suffix);
                            } else {
                                System.out.println("SSFML: " + canonicalName + " requires " + depId + " " + requiredRange
                                        + " but no enabled or disabled mod with that id was found.");
                            }
                            continue;
                        }
                }

                Boolean satisfied = versionSatisfies(actualVersion, requiredRange);
                if (satisfied == null) {
                    System.out.println("SSFML: " + canonicalName + " requires " + depId + " " + requiredRange
                            + " - could not evaluate against version " + actualVersion + ".");
                } else if (satisfied) {
                    System.out.println("SSFML: " + canonicalName + " requires " + depId + " " + requiredRange
                            + " - satisfied by " + actualVersion + ".");
                } else {
                    System.out.println("SSFML: " + canonicalName + " requires " + depId + " " + requiredRange
                            + " but found " + actualVersion + " - may not be compatible. This mod will not be disabled automatically.");
                }
            }
        }
    }

    /**
     * Reads a mod jar's own "id" and "version" out of its fabric.mod.json, if present.
     * Returns null if either field is missing or the jar has no fabric.mod.json.
     */
    private static String[] readModIdAndVersion(File modFile) {
        String json = readFabricModJson(modFile);
        if (json == null) {
            return null;
        }

        Matcher idMatcher = ID_PATTERN.matcher(json);
        Matcher versionMatcher = VERSION_PATTERN.matcher(json);
        if (!idMatcher.find() || !versionMatcher.find()) {
            return null;
        }

        return new String[] { idMatcher.group(1), versionMatcher.group(1) };
    }

    /**
     * Extracts every key/value pair inside a mod jar's "depends" block. Regex-scoped to just
     * the "depends" block's braces first, so entries under "recommends"/"conflicts"/etc. aren't picked up by mistake.
     */
    private static Map<String, String> readDependsBlock(File modFile) {
        Map<String, String> result = new LinkedHashMap<>();
        String json = readFabricModJson(modFile);
        if (json == null) {
            return result;
        }

        Matcher blockMatcher = DEPENDS_BLOCK_PATTERN.matcher(json);
        if (!blockMatcher.find()) {
            return result;
        }

        Matcher entryMatcher = DEPENDS_ENTRY_PATTERN.matcher(blockMatcher.group(1));
        while (entryMatcher.find()) {
            result.put(entryMatcher.group(1), entryMatcher.group(2));
        }
        return result;
    }

    /**
     * Reads the raw text of fabric.mod.json out of a mod jar, or null if it's missing or unreadable.
     */
    private static String readFabricModJson(File modFile) {
        try (ZipFile zip = new ZipFile(modFile)) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("SSFML: Could not read fabric.mod.json from " + modFile.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Evaluation attempt of a fabric-style version against version string.
     * Returns null if any clause can't be parsed, so caller logs remain unknown instead of a false true/false.
     * This is sort of a standalone reimplementation of Fabric's semver-superset comparison rule.
     * This exits because Fabric's own VersionPredicate class isn't reliably on the class path at this point.
     */
    private static Boolean versionSatisfies(String actualVersion, String predicateExpr) {
        for (String clause : predicateExpr.trim().split("\\s+")) {
            Boolean result = evaluateSingleClause(actualVersion, clause);
            if (result == null || !result) {
                return result;
            }
        }
        return true;
    }

    private static Boolean evaluateSingleClause(String actualVersion, String clause) {
        if (clause.equals("*")) {
            return true;
        }

        String operator;
        String versionPart;

        if (clause.startsWith(">=") || clause.startsWith("<=")) {
            operator = clause.substring(0, 2);
            versionPart = clause.substring(2);
        } else if (clause.startsWith(">") || clause.startsWith("<") || clause.startsWith("=")) {
            operator = clause.substring(0, 1);
            versionPart = clause.substring(1);
        } else {
            operator = "=";
            versionPart = clause;
        }

        int cmp;
        try {
            cmp = compareVersions(actualVersion, versionPart);
        } catch (NumberFormatException e) {
            return null;
        }

        return switch (operator) {
            case ">=" -> cmp >= 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case "=" -> cmp == 0;
            default -> null;
        };
    }

    /**
     * Compares two dot-separated numeric version strings component by component,
     * left to right, treating missing components as 0, matching Fabric's documented semver-superset comparison rule.
     * Ignores any "-prerelease" or "+build" suffix on either side, if present.
     */
    private static int compareVersions(String a, String b) {
        String coreA = a.split("[-+]", 2)[0];
        String coreB = b.split("[-+]", 2)[0];

        String[] partsA = coreA.split("\\.");
        String[] partsB = coreB.split("\\.");
        int length = Math.max(partsA.length, partsB.length);

        for (int i = 0; i < length; i++) {
            int valueA = i < partsA.length ? Integer.parseInt(partsA[i]) : 0;
            int valueB = i < partsB.length ? Integer.parseInt(partsB[i]) : 0;
            if (valueA != valueB) {
                return Integer.compare(valueA, valueB);
            }
        }
        return 0;
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
        mounted.sort(String.CASE_INSENSITIVE_ORDER);

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