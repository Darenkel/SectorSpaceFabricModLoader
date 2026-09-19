package com.sector.bridge.moditems;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers ItemIdRegistry: assigning fresh ids, reusing them stably across reloads, and remapping an
 * id that's fallen inside the vanilla range (simulating a game update claiming that id natively).
 */
class ItemIdRegistryTest {

    private static final int CEILING = 1000;

    @Test
    void resolveOrAssign_firstTimeSeen_assignsIdAboveCeiling(@TempDir Path tempDir) {
        ItemIdRegistry registry = ItemIdRegistry.load(tempDir.resolve("registry.json"));

        ItemIdRegistry.MappingEntry entry = registry.resolveOrAssign("weaponfoundry", "laser_rifle_mk2", CEILING, "0.5.9.6");

        assertTrue(entry.assignedItemId > CEILING);
    }

    @Test
    void resolveOrAssign_sameModAndItem_reusesSameId(@TempDir Path tempDir) {
        ItemIdRegistry registry = ItemIdRegistry.load(tempDir.resolve("registry.json"));

        int firstId = registry.resolveOrAssign("weaponfoundry", "laser_rifle_mk2", CEILING, "0.5.9.6").assignedItemId;
        int secondId = registry.resolveOrAssign("weaponfoundry", "laser_rifle_mk2", CEILING, "0.5.9.6").assignedItemId;

        assertEquals(firstId, secondId);
    }

    @Test
    void resolveOrAssign_differentItems_getDifferentIds(@TempDir Path tempDir) {
        ItemIdRegistry registry = ItemIdRegistry.load(tempDir.resolve("registry.json"));

        int idA = registry.resolveOrAssign("weaponfoundry", "laser_rifle_mk2", CEILING, "0.5.9.6").assignedItemId;
        int idB = registry.resolveOrAssign("weaponfoundry", "laser_rifle_mk3", CEILING, "0.5.9.6").assignedItemId;

        assertNotEquals(idA, idB);
    }

    @Test
    void saveThenLoad_roundTripsAssignedIds(@TempDir Path tempDir) {
        Path registryFile = tempDir.resolve("registry.json");

        ItemIdRegistry registry = ItemIdRegistry.load(registryFile);
        int assignedId = registry.resolveOrAssign("weaponfoundry", "laser_rifle_mk2", CEILING, "0.5.9.6").assignedItemId;
        registry.save();

        ItemIdRegistry reloaded = ItemIdRegistry.load(registryFile);
        int reloadedId = reloaded.resolveOrAssign("weaponfoundry", "laser_rifle_mk2", CEILING, "0.5.9.6").assignedItemId;

        assertEquals(assignedId, reloadedId);
    }

    @Test
    void resolveOrAssign_idFallsInsideGrownVanillaRange_getsRemappedAndHistoryRecorded(@TempDir Path tempDir) {
        ItemIdRegistry registry = ItemIdRegistry.load(tempDir.resolve("registry.json"));

        int originalId = registry.resolveOrAssign("weaponfoundry", "laser_rifle_mk2", CEILING, "0.5.9.6").assignedItemId;

        // Simulate a game update whose vanilla ids now extend past the id we were assigned.
        int newCeiling = originalId + 500;
        ItemIdRegistry.MappingEntry remapped = registry.resolveOrAssign("weaponfoundry", "laser_rifle_mk2", newCeiling, "0.6.0.0");

        assertTrue(remapped.assignedItemId > newCeiling);
        assertNotEquals(originalId, remapped.assignedItemId);

        List<ItemIdRegistry.RemapRecord> history = remapped.remapHistory;
        assertEquals(1, history.size());
        assertEquals(originalId, history.get(0).oldItemId());
        assertEquals(remapped.assignedItemId, history.get(0).newItemId());
    }

    @Test
    void entriesRemappedThisLaunch_onlyReturnsEntriesRemappedForGivenVersion(@TempDir Path tempDir) {
        ItemIdRegistry registry = ItemIdRegistry.load(tempDir.resolve("registry.json"));

        int originalId = registry.resolveOrAssign("weaponfoundry", "laser_rifle_mk2", CEILING, "0.5.9.6").assignedItemId;
        registry.resolveOrAssign("weaponfoundry", "laser_rifle_mk2", originalId + 500, "0.6.0.0");

        assertTrue(registry.entriesRemappedThisLaunch("0.6.0.0").size() == 1);
        assertTrue(registry.entriesRemappedThisLaunch("0.5.9.6").isEmpty());
    }
}
