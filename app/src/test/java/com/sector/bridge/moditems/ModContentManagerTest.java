package com.sector.bridge.moditems;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of ModContentManager: a mod's ssfml_items.json ends up registered in
 * ItemDatabase/MarketDatabase with an id that stays stable across a second load pass (simulating
 * the next game launch), reading the same on-disk registry back.
 */
class ModContentManagerTest {

    @Test
    void loadAndApply_registersItemsAndListings_andKeepsIdStableAcrossReload(@TempDir Path tempDir) throws IOException {
        File gameDir = tempDir.toFile();
        File modJar = writeFakeMod(tempDir, "weaponfoundry.jar", """
                {
                  "items": [
                    { "id": "laser_rifle_mk2", "displayName": "Laser Rifle Mk2", "category": "weapon" }
                  ],
                  "marketListings": [
                    { "itemId": "laser_rifle_mk2", "basePrice": 500 }
                  ]
                }
                """);

        ModContentManager.loadAndApply(gameDir, Map.of("weaponfoundry", modJar), "0.5.9.6");

        ItemDatabase.ItemRecord itemRecord = ItemDatabase.getByModItemId("weaponfoundry", "laser_rifle_mk2");
        assertTrue(itemRecord != null);
        int assignedId = itemRecord.assignedItemId();

        MarketDatabase.MarketRecord marketRecord = MarketDatabase.getByAssignedItemId(assignedId);
        assertTrue(marketRecord != null);
        assertEquals(500.0, marketRecord.basePrice());

        // Second launch: same mod, same declared item, id must not change.
        ModContentManager.loadAndApply(gameDir, Map.of("weaponfoundry", modJar), "0.5.9.6");
        assertEquals(assignedId, ItemDatabase.getByModItemId("weaponfoundry", "laser_rifle_mk2").assignedItemId());
    }

    private static File writeFakeMod(Path dir, String fileName, String itemsJson) throws IOException {
        File file = dir.resolve(fileName).toFile();
        try (OutputStream fos = java.nio.file.Files.newOutputStream(file.toPath());
             ZipOutputStream zip = new ZipOutputStream(fos)) {
            zip.putNextEntry(new ZipEntry(ModContentLoader.ITEMS_ENTRY_NAME));
            zip.write(itemsJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }
}
