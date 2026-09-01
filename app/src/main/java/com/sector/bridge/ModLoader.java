package com.sector.bridge;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

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

    private static final Pattern BLOCK_ENTRY_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Thrown when the user chooses "Exit" (or closes the window) on the dependency-issue dialog.
     * Not a failure/crash.
     */
    public static final class LaunchAbortedException extends Exception {
        LaunchAbortedException(String message) {
            super(message);
        }
    }

    /**
     * Result of a "Continue to Game" vs "Continue to Game and Disable Problem Mods" choice on the dependency-issue dialog.
     * "Exit" doesn't get a value here as it throws LaunchAbortedException instead, since there's nothing left for applyModState() to do.
     */
    private enum DependencyDialogChoice {
        CONTINUE,
        CONTINUE_AND_DISABLE
    }

    /**
     * A single flagged dependency or conflict, in the form the fabric_loader_dependencies.json override file actually needs:
     * the mod's own id (not its jar filename), the id it couldn't satisfy, and which fabric.mod.json category ("depends" or "breaks") the entry came from.
     * The override file needs a different "-depends"/"-breaks" key depending on which one it was.
     */
    private record ProblemDependency(String modId, String depId, String category) {
    }

    /**
     * Holds what logGameVersionCompatibility() found: every check still gets logged as it happens,
     * but this is what applyModState() actually needs afterward to decide whether to show the dependency dialog and,
     * if so, which mods it would offer to disable, and what it would offer to write into fabric_loader_dependencies.json instead.
     */
    private static final class DependencyCheckResult {
        final List<String> problemDescriptions = new ArrayList<>();
        final Set<String> problemMods = new LinkedHashSet<>();
        final List<ProblemDependency> problemDependencies = new ArrayList<>();
    }

    /**
     * Syncs mod_list.cfg against what is current in mods/, then renames each jar on disk to match the set state.
     * This is called once per launch, before Fabric/Knot starts.
     * Fabric will then scan and loads mods/ once this returns.
     */
    public void applyModState(File gameDir) throws LaunchAbortedException {
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

            DependencyCheckResult depResult = logGameVersionCompatibility(currentFiles, rebuilt);

            List<ProblemDependency> overridesToWrite = new ArrayList<>();

            // If any enabled mod has a dependency issue that would likely crash Fabric, ask the user how to proceed instead of letting it try and fail.
            // writeConfig() is deliberately called after this, not before, so a "disable" choice here persists to mod_list.cfg instead of only applying for this one launch.
            if (!depResult.problemMods.isEmpty()) {
                DependencyDialogChoice choice = showDependencyDialog(gameDir, depResult.problemDescriptions);
                if (choice == DependencyDialogChoice.CONTINUE_AND_DISABLE) {
                    for (String canonicalName : depResult.problemMods) {
                        rebuilt.put(canonicalName, false);
                        System.out.println("SSFML: Disabling " + canonicalName + " per user choice due to dependency issues.");
                    }
                } else {
                    overridesToWrite = depResult.problemDependencies;
                    System.out.println("SSFML: Continuing with problem mods still enabled per user choice - launch may crash if genuinely incompatible.");
                }
            }

            writeDependencyOverrides(gameDir, overridesToWrite);

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
     * Information scan for each currently enabled mod: checks its "depends" entries against sector-space, fabricloader, java,
     * and any other enabled mod's own id/version, checks its "breaks" entries for actual conflicts, and logs its "suggests" entries informationally.
     * Every check is logged as it happens. What actually gets returned: only "depends" entries that are definitely unmet, and "breaks" entries that definitely do conflict
     * As those are what applyModState() can offer the user a choice about via the dependency dialog.
     * "suggests" never ends up in the returned result, and neither does an ambiguous/unparseable requirement in "depends"/"breaks"
     * This runs before Fabric itself starts, so it cannot actually see Fabric's own dependency thing.
     */
    private DependencyCheckResult logGameVersionCompatibility(Map<String, File> currentFiles, Map<String, Boolean> rebuilt) {
        DependencyCheckResult result = new DependencyCheckResult();

        String currentGameVersion = LocVerifierCFG.getNormalizedGameVersion();
        String fabricLoaderVersion = net.fabricmc.loader.impl.FabricLoaderImpl.VERSION;
        String javaVersion = System.getProperty("java.version");

        // Build modId -> version for every currently-enabled mod, so mod-to-mod dependencies can be checked the same way the special-cased ones are.
        // Also track canonicalName -> modId, since the override file needs a mod's own id, not its jar filename.
        Map<String, String> enabledModVersions = new LinkedHashMap<>();
        Map<String, String> canonicalNameToId = new LinkedHashMap<>();
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
                canonicalNameToId.put(entry.getKey(), idAndVersion[0]);
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

            String json = readFabricModJson(modFile);
            Map<String, String> depends = readNamedBlock(json, "depends");
            Map<String, String> breaks = readNamedBlock(json, "breaks");
            Map<String, String> suggests = readNamedBlock(json, "suggests");

            if (depends.isEmpty() && breaks.isEmpty() && suggests.isEmpty()) {
                System.out.println("SSFML: " + canonicalName + " declares no dependencies to check.");
                continue;
            }

            for (Map.Entry<String, String> dep : depends.entrySet()) {
                String depId = dep.getKey();
                String requiredRange = dep.getValue();
                String actualVersion = switch (depId) {
                    case "sector-space" -> currentGameVersion;
                    case "fabricloader" -> fabricLoaderVersion;
                    case "java" -> javaVersion;
                    default -> enabledModVersions.get(depId);
                };

                if (actualVersion == null) {
                    String[] disabled = disabledModsById.get(depId);
                    String reason;
                    if (disabled != null) {
                        String disabledCanonicalName = disabled[0];
                        String disabledVersion = disabled[1];
                        Boolean wouldSatisfy = versionSatisfies(disabledVersion, requiredRange);

                        String suffix = Boolean.TRUE.equals(wouldSatisfy)
                                ? " Its version (" + disabledVersion + ") would satisfy this requirement."
                                : Boolean.FALSE.equals(wouldSatisfy)
                                ? " Its version (" + disabledVersion + ") would NOT satisfy this requirement even if re-enabled."
                                : "";

                        reason = "requires " + depId + " " + requiredRange + " - found as " + disabledCanonicalName
                                + " but it is currently disabled. Consider re-enabling it." + suffix;
                    } else {
                        reason = "requires " + depId + " " + requiredRange + " but no enabled or disabled mod with that id was found.";
                    }

                    System.out.println("SSFML: " + canonicalName + " " + reason);
                    result.problemMods.add(canonicalName);
                    result.problemDescriptions.add(canonicalName + " " + reason);
                    String modId = canonicalNameToId.get(canonicalName);
                    if (modId != null) {
                        result.problemDependencies.add(new ProblemDependency(modId, depId, "depends"));
                    }
                    continue;
                }

                Boolean satisfied = versionSatisfies(actualVersion, requiredRange);
                if (satisfied == null) {
                    System.out.println("SSFML: " + canonicalName + " requires " + depId + " " + requiredRange
                            + " - could not evaluate against version " + actualVersion + ".");
                } else if (satisfied) {
                    System.out.println("SSFML: " + canonicalName + " requires " + depId + " " + requiredRange
                            + " - satisfied by " + actualVersion + ".");
                } else {
                    String reason = "requires " + depId + " " + requiredRange + " but found " + actualVersion + ".";
                    System.out.println("SSFML: " + canonicalName + " " + reason);
                    result.problemMods.add(canonicalName);
                    result.problemDescriptions.add(canonicalName + " " + reason);
                    String modId = canonicalNameToId.get(canonicalName);
                    if (modId != null) {
                        result.problemDependencies.add(new ProblemDependency(modId, depId, "depends"));
                    }
                }
            }

            for (Map.Entry<String, String> br : breaks.entrySet()) {
                String depId = br.getKey();
                String conflictRange = br.getValue();
                String actualVersion = switch (depId) {
                    case "sector-space" -> currentGameVersion;
                    case "fabricloader" -> fabricLoaderVersion;
                    case "java" -> javaVersion;
                    default -> enabledModVersions.get(depId);
                };

                if (actualVersion == null) {
                    System.out.println("SSFML: " + canonicalName + " breaks " + depId + " " + conflictRange
                            + " - not present, no conflict.");
                    continue;
                }

                Boolean conflicts = versionSatisfies(actualVersion, conflictRange);
                if (conflicts == null) {
                    System.out.println("SSFML: " + canonicalName + " breaks " + depId + " " + conflictRange
                            + " - could not evaluate against version " + actualVersion + ".");
                } else if (conflicts) {
                    String reason = "breaks " + depId + " " + conflictRange + " but found " + actualVersion
                            + " - these mods conflict.";
                    System.out.println("SSFML: " + canonicalName + " " + reason);
                    result.problemMods.add(canonicalName);
                    result.problemDescriptions.add(canonicalName + " " + reason);
                    String modId = canonicalNameToId.get(canonicalName);
                    if (modId != null) {
                        result.problemDependencies.add(new ProblemDependency(modId, depId, "breaks"));
                    }
                } else {
                    System.out.println("SSFML: " + canonicalName + " breaks " + depId + " " + conflictRange
                            + " - present as " + actualVersion + ", outside the conflicting range, no conflict.");
                }
            }

            for (Map.Entry<String, String> sug : suggests.entrySet()) {
                String depId = sug.getKey();
                String suggestedRange = sug.getValue();
                String actualVersion = switch (depId) {
                    case "sector-space" -> currentGameVersion;
                    case "fabricloader" -> fabricLoaderVersion;
                    case "java" -> javaVersion;
                    default -> enabledModVersions.get(depId);
                };

                if (actualVersion == null) {
                    System.out.println("SSFML: " + canonicalName + " suggests " + depId + " " + suggestedRange
                            + " - not currently present.");
                } else {
                    Boolean satisfied = versionSatisfies(actualVersion, suggestedRange);
                    String satisfiedText = Boolean.TRUE.equals(satisfied)
                            ? "present and satisfies this"
                            : Boolean.FALSE.equals(satisfied)
                            ? "present but does not satisfy this (" + actualVersion + ")"
                            : "present (" + actualVersion + ", could not evaluate range)";
                    System.out.println("SSFML: " + canonicalName + " suggests " + depId + " " + suggestedRange
                            + " - " + satisfiedText + ".");
                }
            }
        }

        return result;
    }

    /**
     * Shows a Forge-style modal dialog listing every unmet dependency/conflict found by logGameVersionCompatibility(),
     * with four choices: continue as-is, continue and disable the problem mods, open the startup log, or exit without launching.
     * "Open Logs" doesn't close the dialog. Closing the window via the OS close button is treated the same as "Exit".
     */
    private DependencyDialogChoice showDependencyDialog(File gameDir, List<String> problemDescriptions) throws LaunchAbortedException {
        StringBuilder text = new StringBuilder();
        text.append("The following mod dependency issues were found:\n\n");
        for (String line : problemDescriptions) {
            text.append("- ").append(line).append("\n");
        }
        text.append("\nContinuing without addressing these may cause the game to crash on launch.");

        JTextArea textArea = new JTextArea(text.toString());
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setCaretPosition(0);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(520, 300));

        JFrame dummy = new JFrame("SSFML");
        dummy.setUndecorated(true);
        dummy.setVisible(true);
        dummy.setLocationRelativeTo(null);

        JDialog dialog = new JDialog(dummy, "SSFML - Mod Dependency Issues", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        final DependencyDialogChoice[] choice = { null };
        final boolean[] exitRequested = { false };

        JButton continueButton = new JButton("Continue to Game");
        JButton continueDisableButton = new JButton("Continue to Game and Disable Problem Mods");
        JButton openLogsButton = new JButton("Open Logs");
        JButton exitButton = new JButton("Exit");

        continueButton.addActionListener(e -> {
            choice[0] = DependencyDialogChoice.CONTINUE;
            dialog.dispose();
        });
        continueDisableButton.addActionListener(e -> {
            choice[0] = DependencyDialogChoice.CONTINUE_AND_DISABLE;
            dialog.dispose();
        });
        openLogsButton.addActionListener(e -> {
            File logFile = new File(gameDir, StartupLogger.LOG_FILE_NAME);
            try {
                if (Desktop.isDesktopSupported() && logFile.exists()) {
                    Desktop.getDesktop().open(logFile);
                } else {
                    System.err.println("SSFML: Could not open log file (unsupported or missing): " + logFile.getAbsolutePath());
                }
            } catch (IOException ex) {
                System.err.println("SSFML: Failed to open log file: " + ex.getMessage());
            }
            // Dialog stays open - Open Logs isn't a final choice, just a side action.
        });
        exitButton.addActionListener(e -> {
            exitRequested[0] = true;
            dialog.dispose();
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        buttonPanel.add(continueButton);
        buttonPanel.add(continueDisableButton);
        buttonPanel.add(openLogsButton);
        buttonPanel.add(exitButton);

        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(scrollPane, BorderLayout.CENTER);
        dialog.getContentPane().add(buttonPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true); // Blocks here until one of the four buttons disposes it.

        dummy.dispose();

        if (exitRequested[0] || choice[0] == null) {
            throw new LaunchAbortedException("User chose to exit due to mod dependency issues.");
        }

        return choice[0];
    }

    /**
     * Writes config/fabric_loader_dependencies.json using Fabric Loader's own documented dependency-override mechanism
     * (see docs.fabricmc.net/players/troubleshooting/dependency-overrides)
     * So that Fabric's resolver stops treating each flagged "depends"/"breaks" entry as a hard blocker for that mod.
     * This does NOT make the requirement actually satisfied, it could obviously still crash when the mod tries to grab something that doesn't exist.
     * <p>
     * Fully rewritten every launch, unconditionally.
     */
    private void writeDependencyOverrides(File gameDir, List<ProblemDependency> problems) {
        File configDir = new File(gameDir, "config");
        File overrideFile = new File(configDir, "fabric_loader_dependencies.json");

        if (problems.isEmpty()) {
            try {
                Files.deleteIfExists(overrideFile.toPath());
            } catch (IOException e) {
                System.err.println("SSFML: Could not clear " + overrideFile.getAbsolutePath() + ": " + e.getMessage());
            }
            return;
        }

        Map<String, Map<String, Set<String>>> overridesByModAndCategory = new LinkedHashMap<>();
        for (ProblemDependency problem : problems) {
            overridesByModAndCategory
                    .computeIfAbsent(problem.modId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(problem.category, k -> new LinkedHashSet<>())
                    .add(problem.depId);
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n  \"version\": 1,\n  \"overrides\": {\n");
        int modIndex = 0;
        int modCount = overridesByModAndCategory.size();
        for (Map.Entry<String, Map<String, Set<String>>> modEntry : overridesByModAndCategory.entrySet()) {
            json.append("    \"").append(modEntry.getKey()).append("\": {\n");
            int catIndex = 0;
            int catCount = modEntry.getValue().size();
            for (Map.Entry<String, Set<String>> catEntry : modEntry.getValue().entrySet()) {
                json.append("      \"-").append(catEntry.getKey()).append("\": {\n");
                int depIndex = 0;
                int depCount = catEntry.getValue().size();
                for (String depId : catEntry.getValue()) {
                    json.append("        \"").append(depId).append("\": \"IGNORED\"");
                    json.append(++depIndex < depCount ? ",\n" : "\n");
                }
                json.append("      }");
                json.append(++catIndex < catCount ? ",\n" : "\n");
            }
            json.append("    }");
            json.append(++modIndex < modCount ? ",\n" : "\n");
        }
        json.append("  }\n}\n");

        try {
            Files.createDirectories(configDir.toPath());
            Files.writeString(overrideFile.toPath(), json.toString(), StandardCharsets.UTF_8);
            System.out.println("SSFML: Wrote " + overrideFile.getAbsolutePath()
                    + " to skip Fabric's dependency check for " + problems.size() + " flagged requirement(s) this launch.");
        } catch (IOException e) {
            System.err.println("SSFML: Failed to write fabric_loader_dependencies.json: " + e.getMessage());
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
     * Extracts every key/value pair inside a named block ("depends", "breaks", or "suggests")of a mod's fabric.mod.json text.
     * Regex-scoped to just that block's braces first, so entries under a different category aren't picked up by mistake.
     */
    private static Map<String, String> readNamedBlock(String json, String blockName) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null) {
            return result;
        }

        Pattern blockPattern = Pattern.compile("\"" + blockName + "\"\\s*:\\s*\\{([^}]*)}", Pattern.DOTALL);
        Matcher blockMatcher = blockPattern.matcher(json);
        if (!blockMatcher.find()) {
            return result;
        }

        Matcher entryMatcher = BLOCK_ENTRY_PATTERN.matcher(blockMatcher.group(1));
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
            String line = raw.trim();
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