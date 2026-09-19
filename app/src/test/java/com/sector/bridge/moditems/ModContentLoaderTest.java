package com.sector.bridge.moditems;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers ModContentLoader reading (or gracefully not reading) ssfml_items.json from a mod jar.
 */
class ModContentLoaderTest {

    @Test
    void readModContent_parsesItemsAndMarketListings(@TempDir Path tempDir) throws IOException {
        String json = """
                {
                  "items": [
                    { "id": "laser_rifle_mk2", "displayName": "Laser Rifle Mk2", "category": "weapon" }
                  ],
                  "marketListings": [
                    { "itemId": "laser_rifle_mk2", "basePrice": 500 }
                  ]
                }
                """;
        File modJar = writeFakeMod(tempDir, "weaponfoundry.jar", json);

        ModContentLoader.ModContent content = ModContentLoader.readModContent(modJar, "weaponfoundry");

        assertEquals(1, content.items().size());
        assertEquals("laser_rifle_mk2", content.items().get(0).modItemId());
        assertEquals(1, content.marketListings().size());
        assertEquals(500.0, content.marketListings().get(0).basePrice());
    }

    @Test
    void readModContent_missingEntry_returnsEmptyContent(@TempDir Path tempDir) throws IOException {
        File modJar = tempDir.resolve("nocontent.jar").toFile();
        try (OutputStream fos = java.nio.file.Files.newOutputStream(modJar.toPath());
             ZipOutputStream zip = new ZipOutputStream(fos)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write("{\"id\":\"nocontent\"}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        ModContentLoader.ModContent content = ModContentLoader.readModContent(modJar, "nocontent");

        assertTrue(content.items().isEmpty());
        assertTrue(content.marketListings().isEmpty());
    }

    @Test
    void readModContent_malformedJson_returnsEmptyContentInsteadOfThrowing(@TempDir Path tempDir) throws IOException {
        File modJar = writeFakeMod(tempDir, "broken.jar", "{ this is not valid json");

        ModContentLoader.ModContent content = ModContentLoader.readModContent(modJar, "broken");

        assertTrue(content.items().isEmpty());
        assertTrue(content.marketListings().isEmpty());
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
