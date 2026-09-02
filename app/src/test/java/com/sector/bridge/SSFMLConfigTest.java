package com.sector.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers SSFMLConfig's reconciliation logic via the package-private load(Path, String, List) overload,
 * so none of this needs an active Fabric runtime, only the public load(String, List) mods actually call depends on FabricLoader.getInstance().getGameDir().
 */
class SSFMLConfigTest {

    @Test
    void freshLoad_createsFileWithSchemaDefaults(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("testmod.cfg");
        List<SSFMLConfig.ConfigEntry> schema = List.of(
                new SSFMLConfig.ConfigEntry("maxDroneCount", "5", "Max drones a station can spawn"),
                new SSFMLConfig.ConfigEntry("dronesEnabled", "true")
        );

        SSFMLConfig.Config config = SSFMLConfig.load(configFile, "testmod", schema);

        assertEquals(5, config.getInt("maxDroneCount"));
        assertTrue(config.getBoolean("dronesEnabled"));
        assertTrue(Files.exists(configFile));

        String written = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(written.contains("maxDroneCount=5"));
        assertTrue(written.contains("dronesEnabled=true"));
        assertTrue(written.contains("# Max drones a station can spawn"));
    }

    @Test
    void existingValue_isPreservedNotOverwrittenByDefault(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("testmod.cfg");
        Files.writeString(configFile, "maxDroneCount=12\n", StandardCharsets.UTF_8);

        List<SSFMLConfig.ConfigEntry> schema = List.of(
                new SSFMLConfig.ConfigEntry("maxDroneCount", "5")
        );

        SSFMLConfig.Config config = SSFMLConfig.load(configFile, "testmod", schema);

        assertEquals(12, config.getInt("maxDroneCount"));
    }

    @Test
    void keyMissingFromNewSchema_getsCommentedOutOnce(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("testmod.cfg");
        // Simulates an existing config from before "oldFeatureEnabled" was removed from the schema.
        Files.writeString(configFile, "oldFeatureEnabled=true\nmaxDroneCount=5\n", StandardCharsets.UTF_8);

        List<SSFMLConfig.ConfigEntry> schema = List.of(
                new SSFMLConfig.ConfigEntry("maxDroneCount", "5")
        );

        SSFMLConfig.load(configFile, "testmod", schema);

        String written = Files.readString(configFile, StandardCharsets.UTF_8);
        assertFalse(written.contains("\noldFeatureEnabled=true\n"), "the old key should no longer be active");
        assertTrue(written.contains("#oldFeatureEnabled=true"), "the old key's value should be preserved, commented out");
    }

    @Test
    void alreadyRetiredLine_isNotReprocessedOnSubsequentLoads(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("testmod.cfg");
        Files.writeString(configFile,
                "maxDroneCount=5\n#oldFeatureEnabled=true  # no longer used by this mod\n",
                StandardCharsets.UTF_8);

        List<SSFMLConfig.ConfigEntry> schema = List.of(
                new SSFMLConfig.ConfigEntry("maxDroneCount", "5")
        );

        // Load twice in a row, same as two separate game launches.
        SSFMLConfig.load(configFile, "testmod", schema);
        SSFMLConfig.load(configFile, "testmod", schema);

        String written = Files.readString(configFile, StandardCharsets.UTF_8);
        long occurrences = written.lines().filter(line -> line.contains("oldFeatureEnabled")).count();
        assertEquals(1, occurrences, "the retired line should appear exactly once, not be double-commented");
    }

    @Test
    void setThenSave_persistsNewValue(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("testmod.cfg");
        List<SSFMLConfig.ConfigEntry> schema = List.of(
                new SSFMLConfig.ConfigEntry("maxDroneCount", "5")
        );

        SSFMLConfig.Config config = SSFMLConfig.load(configFile, "testmod", schema);
        config.set("maxDroneCount", 8);
        config.save();

        SSFMLConfig.Config reloaded = SSFMLConfig.load(configFile, "testmod", schema);
        assertEquals(8, reloaded.getInt("maxDroneCount"));
    }

    @Test
    void malformedValueOnDisk_fallsBackToDefaultAndWarns(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("testmod.cfg");
        Files.writeString(configFile, "maxDroneCount=not-a-number\n", StandardCharsets.UTF_8);

        List<SSFMLConfig.ConfigEntry> schema = List.of(
                new SSFMLConfig.ConfigEntry("maxDroneCount", "5")
        );

        SSFMLConfig.Config config = SSFMLConfig.load(configFile, "testmod", schema);

        assertEquals(5, config.getInt("maxDroneCount"));
    }

    @Test
    void reloadingWithUnchangedSchema_doesNotTreatDescriptiveCommentsAsRetired(@TempDir Path tempDir) throws IOException {
        // Regression test for a real bug: header/per-entry comments were being mistaken for
        // retired options on every reload, growing the file a little more on every launch.
        List<SSFMLConfig.ConfigEntry> schema = List.of(
                new SSFMLConfig.ConfigEntry("maxDroneCount", "5", "Max drones a station can spawn"),
                new SSFMLConfig.ConfigEntry("dronesEnabled", "true")
        );
        Path configFile = tempDir.resolve("testmod.cfg");

        SSFMLConfig.load(configFile, "testmod", schema); // first launch, creates the file
        SSFMLConfig.load(configFile, "testmod", schema); // second launch, same schema, no changes

        String written = Files.readString(configFile, StandardCharsets.UTF_8);
        assertFalse(written.contains("Retired options"),
                "nothing was actually removed from the schema, so nothing should be retired");
    }
}