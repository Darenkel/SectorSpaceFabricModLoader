package com.sector.bridge.moditems;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a mod's optional {@code ssfml_items.json} (at the jar root, alongside {@code fabric.mod.json})
 * declaring custom items and market listings the mod wants SSFML to register. A mod with no such file
 * simply has nothing added - this is entirely opt-in.
 * <p>
 * Expected shape:
 * <pre>
 * {
 *   "items": [
 *     { "id": "laser_rifle_mk2", "displayName": "Laser Rifle Mk2", "category": "weapon" }
 *   ],
 *   "marketListings": [
 *     { "itemId": "laser_rifle_mk2", "basePrice": 500 }
 *   ]
 * }
 * </pre>
 */
final class ModContentLoader {

    static final String ITEMS_ENTRY_NAME = "ssfml_items.json";

    private ModContentLoader() {
    }

    record ModContent(String modId, List<ModItemDefinition> items, List<ModMarketListing> marketListings) {
        static ModContent empty(String modId) {
            return new ModContent(modId, List.of(), List.of());
        }
    }

    /** Never throws - a missing or malformed ssfml_items.json is logged and treated as "this mod adds nothing". */
    static ModContent readModContent(File modJar, String modId) {
        String json = readEntry(modJar);
        if (json == null) {
            return ModContent.empty(modId);
        }

        try {
            Map<String, Object> root = Json.parseObject(json);
            List<ModItemDefinition> items = new ArrayList<>();
            List<ModMarketListing> listings = new ArrayList<>();

            Object itemsValue = root.get("items");
            if (itemsValue instanceof List<?> list) {
                for (Object entry : list) {
                    items.add(ModItemDefinition.fromJson(castObject(entry)));
                }
            }

            Object listingsValue = root.get("marketListings");
            if (listingsValue instanceof List<?> list) {
                for (Object entry : list) {
                    listings.add(ModMarketListing.fromJson(castObject(entry)));
                }
            }

            return new ModContent(modId, items, listings);
        } catch (RuntimeException e) {
            System.err.println("SSFML: Ignoring malformed " + ITEMS_ENTRY_NAME + " in " + modJar.getName() + ": " + e.getMessage());
            return ModContent.empty(modId);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObject(Object value) {
        if (!(value instanceof Map)) {
            throw new Json.JsonParseException("Expected a JSON object in array, found: " + value);
        }
        return (Map<String, Object>) value;
    }

    private static String readEntry(File modJar) {
        try (ZipFile zip = new ZipFile(modJar)) {
            ZipEntry entry = zip.getEntry(ITEMS_ENTRY_NAME);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("SSFML: Could not read " + modJar.getName() + " looking for " + ITEMS_ENTRY_NAME + ": " + e.getMessage());
            return null;
        }
    }
}
