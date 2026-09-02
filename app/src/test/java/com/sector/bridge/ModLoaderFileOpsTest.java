package com.sector.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers ModLoader's file-based operations.
 * - reading/writing mod_list.cfg,
 * - resolving a mod that exists as both an enabled and disabled copy
 * - detecting two enabled mods sharing the same fabric.mod.json "id".
 * This all runs against a temp directory built per test, with minimal fake mod jars.
 */
class ModLoaderFileOpsTest {

    private final ModLoader modLoader = new ModLoader();

    @Test
    void readConfig_missingFileReturnsEmptyMap(@TempDir Path tempDir) throws IOException {
        Map<String, Boolean> result = modLoader.readConfig(tempDir.resolve("mod_list.cfg"));
        assertTrue(result.isEmpty());
    }

    @Test
    void writeConfigThenReadConfig_roundTrips(@TempDir Path tempDir) throws IOException {
        Path cfg = tempDir.resolve("mod_list.cfg");
        Map<String, Boolean> written = Map.of("ExampleA.jar", true, "ExampleB.jar", false);

        modLoader.writeConfig(cfg, written);
        Map<String, Boolean> read = modLoader.readConfig(cfg);

        assertEquals(written, read);
    }

    @Test
    void readConfig_ignoresCommentsBlankLinesAndMalformedEntries() throws IOException {
        Path tempFile = Files.createTempFile("mod_list", ".cfg");
        try {
            Files.write(tempFile, List.of(
                    "# a comment",
                    "",
                    "NoCommaHere.jar",
                    "ExampleA.jar, true"
            ), StandardCharsets.UTF_8);

            Map<String, Boolean> result = modLoader.readConfig(tempFile);

            assertEquals(1, result.size());
            assertTrue(result.get("ExampleA.jar"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void resolveDuplicates_keepsCopyMatchingConfiguredState(@TempDir Path modsDir) throws IOException {
        // Both an enabled and disabled copy of the same file exist.
        // The config says it should be enabled, so the disabled copy is the "loser" and should be removed.
        File enabled = modsDir.resolve("Example.jar").toFile();
        File disabled = modsDir.resolve("Example.jar.disabled").toFile();
        Files.writeString(enabled.toPath(), "enabled copy");
        Files.writeString(disabled.toPath(), "disabled copy");

        modLoader.resolveDuplicates(modsDir, Map.of("Example.jar", true));

        assertTrue(enabled.exists());
        assertFalse(disabled.exists());
    }

    @Test
    void detectDuplicateModIds_flagsAllButTheHighestVersion(@TempDir Path modsDir) throws IOException {
        File older = writeFakeMod(modsDir, "mymod-1.0.0.jar", "mymod", "1.0.0");
        File newer = writeFakeMod(modsDir, "mymod-2.0.0.jar", "mymod", "2.0.0");

        Map<String, File> currentFiles = Map.of(
                "mymod-1.0.0.jar", older,
                "mymod-2.0.0.jar", newer
        );
        Map<String, String> canonicalNameToId = Map.of(
                "mymod-1.0.0.jar", "mymod",
                "mymod-2.0.0.jar", "mymod"
        );

        ModLoader.DependencyCheckResult result = new ModLoader.DependencyCheckResult();
        modLoader.detectDuplicateModIds(currentFiles, canonicalNameToId, result);

        assertEquals(1, result.problemMods.size());
        assertTrue(result.problemMods.contains("mymod-1.0.0.jar"), "the older copy should be flagged");
        assertFalse(result.problemMods.contains("mymod-2.0.0.jar"), "the newer copy should be kept");
        assertTrue(result.problemDependencies.isEmpty(),
                "duplicate ids have no override file fix, so nothing should be queued for fabric_loader_dependencies.json");
    }

    @Test
    void detectDuplicateModIds_noProblemWhenIdsAreUnique(@TempDir Path modsDir) throws IOException {
        File modA = writeFakeMod(modsDir, "ModA.jar", "mod_a", "1.0.0");
        File modB = writeFakeMod(modsDir, "ModB.jar", "mod_b", "1.0.0");

        Map<String, File> currentFiles = Map.of("ModA.jar", modA, "ModB.jar", modB);
        Map<String, String> canonicalNameToId = Map.of("ModA.jar", "mod_a", "ModB.jar", "mod_b");

        ModLoader.DependencyCheckResult result = new ModLoader.DependencyCheckResult();
        modLoader.detectDuplicateModIds(currentFiles, canonicalNameToId, result);

        assertTrue(result.problemMods.isEmpty());
    }

    /**
     * Writes a minimal jar containing just enough fabric.mod.json for ModLoader's regex-based reader to pick up an id and version.
     */
    private static File writeFakeMod(Path modsDir, String fileName, String id, String version) throws IOException {
        Files.createDirectories(modsDir);
        File file = modsDir.resolve(fileName).toFile();
        String json = "{\n  \"id\": \"" + id + "\",\n  \"version\": \"" + version + "\"\n}\n";

        try (OutputStream fos = Files.newOutputStream(file.toPath());
             ZipOutputStream zip = new ZipOutputStream(fos)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }
}
