package com.sector.bridge.moditems;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the mapping between a mod's own item id and the numeric id SSFML actually assigned it,
 * at {@code config/ssfml/item_id_registry.json}. Uses "MOD" / "MOD_ITEM_ID" / "ASSIGNED_ITEM_ID" as
 * the on-disk field names so the registry is self-describing.
 * <p>
 * Once assigned, an id is never reused for a different (mod, modItemId) pair and is never changed
 * for that pair either - unless it collides with the vanilla game's own id range (e.g. after a game
 * update adds native items into space a mod's id used to safely occupy). In that case a fresh id is
 * assigned and the change is appended to that entry's remap history, so save files referencing the
 * old id can later be corrected (see {@link SaveIdMigrator}).
 * <p>
 * Entries are never deleted, even if a mod is no longer installed - keeping a retired mapping around
 * (marked inactive) means a save made while that mod was installed can still be resolved later,
 * mirroring the "retire, don't delete" behavior {@code SSFMLConfig} uses for config keys.
 */
final class ItemIdRegistry {

    private final Path registryFile;
    private final Map<String, MappingEntry> entriesByKey = new LinkedHashMap<>();

    private ItemIdRegistry(Path registryFile) {
        this.registryFile = registryFile;
    }

    record RemapRecord(int oldItemId, int newItemId, String reason, String gameVersionAtRemap) {
        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("oldItemId", (long) oldItemId);
            json.put("newItemId", (long) newItemId);
            json.put("reason", reason);
            json.put("gameVersionAtRemap", gameVersionAtRemap);
            return json;
        }

        static RemapRecord fromJson(Map<String, Object> json) {
            return new RemapRecord(
                    intValue(json.get("oldItemId")),
                    intValue(json.get("newItemId")),
                    (String) json.get("reason"),
                    (String) json.get("gameVersionAtRemap")
            );
        }
    }

    static final class MappingEntry {
        final String mod;
        final String modItemId;
        int assignedItemId;
        boolean active;
        final List<RemapRecord> remapHistory = new ArrayList<>();

        MappingEntry(String mod, String modItemId, int assignedItemId) {
            this.mod = mod;
            this.modItemId = modItemId;
            this.assignedItemId = assignedItemId;
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("MOD", mod);
            json.put("MOD_ITEM_ID", modItemId);
            json.put("ASSIGNED_ITEM_ID", (long) assignedItemId);
            if (!remapHistory.isEmpty()) {
                List<Object> history = new ArrayList<>();
                for (RemapRecord record : remapHistory) {
                    history.add(record.toJson());
                }
                json.put("remapHistory", history);
            }
            return json;
        }

        @SuppressWarnings("unchecked")
        static MappingEntry fromJson(Map<String, Object> json) {
            MappingEntry entry = new MappingEntry(
                    (String) json.get("MOD"),
                    (String) json.get("MOD_ITEM_ID"),
                    intValue(json.get("ASSIGNED_ITEM_ID"))
            );
            Object history = json.get("remapHistory");
            if (history instanceof List<?> list) {
                for (Object item : list) {
                    entry.remapHistory.add(RemapRecord.fromJson((Map<String, Object>) item));
                }
            }
            return entry;
        }
    }

    static ItemIdRegistry load(Path registryFile) {
        ItemIdRegistry registry = new ItemIdRegistry(registryFile);
        if (!Files.exists(registryFile)) {
            return registry;
        }
        try {
            String text = Files.readString(registryFile, StandardCharsets.UTF_8);
            Map<String, Object> root = Json.parseObject(text);
            Object mappings = root.get("mappings");
            if (mappings instanceof List<?> list) {
                for (Object item : list) {
                    @SuppressWarnings("unchecked")
                    MappingEntry entry = MappingEntry.fromJson((Map<String, Object>) item);
                    registry.entriesByKey.put(key(entry.mod, entry.modItemId), entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("SSFML: Could not read " + registryFile + ", starting with an empty item id registry: " + e.getMessage());
        }
        return registry;
    }

    void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> mappings = new ArrayList<>();
        for (MappingEntry entry : entriesByKey.values()) {
            mappings.add(entry.toJson());
        }
        root.put("mappings", mappings);

        try {
            Files.createDirectories(registryFile.getParent());
            Files.writeString(registryFile, Json.write(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("SSFML: Failed to save item id registry to " + registryFile + ": " + e.getMessage());
        }
    }

    /** Called once at the start of a load pass, so mappings no longer declared by any enabled mod can be told apart from ones still in use. */
    void markAllInactive() {
        for (MappingEntry entry : entriesByKey.values()) {
            entry.active = false;
        }
    }

    /**
     * Returns the existing assigned id for (mod, modItemId), or assigns a fresh one if this is the
     * first time this pair has been seen. If the existing assigned id now falls within the vanilla
     * game's own id range, it is reassigned instead and the change is recorded in the entry's remap
     * history, since that id has now been claimed by native game content.
     */
    MappingEntry resolveOrAssign(String mod, String modItemId, int vanillaIdCeiling, String currentGameVersion) {
        String key = key(mod, modItemId);
        MappingEntry entry = entriesByKey.get(key);

        if (entry == null) {
            int freshId = nextFreeId(vanillaIdCeiling);
            entry = new MappingEntry(mod, modItemId, freshId);
            entriesByKey.put(key, entry);
        } else if (entry.assignedItemId <= vanillaIdCeiling) {
            int oldId = entry.assignedItemId;
            int newId = nextFreeId(vanillaIdCeiling);
            entry.assignedItemId = newId;
            entry.remapHistory.add(new RemapRecord(oldId, newId, "vanilla id range grew past previously assigned id", currentGameVersion));
            System.out.println("SSFML: Item id collision avoided - " + mod + ":" + modItemId + " remapped from " + oldId + " to " + newId);
        }

        entry.active = true;
        return entry;
    }

    List<MappingEntry> allEntries() {
        return new ArrayList<>(entriesByKey.values());
    }

    /** Every mapping that had at least one id change recorded this launch (compares against currentGameVersion). */
    List<MappingEntry> entriesRemappedThisLaunch(String currentGameVersion) {
        List<MappingEntry> result = new ArrayList<>();
        for (MappingEntry entry : entriesByKey.values()) {
            for (RemapRecord record : entry.remapHistory) {
                if (currentGameVersion.equals(record.gameVersionAtRemap())) {
                    result.add(entry);
                    break;
                }
            }
        }
        return result;
    }

    private int nextFreeId(int vanillaIdCeiling) {
        int candidate = vanillaIdCeiling + 1;
        while (isTaken(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private boolean isTaken(int id) {
        for (MappingEntry entry : entriesByKey.values()) {
            if (entry.assignedItemId == id) {
                return true;
            }
        }
        return false;
    }

    private static String key(String mod, String modItemId) {
        return mod + "::" + modItemId;
    }

    private static int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        throw new Json.JsonParseException("Expected a numeric value, found: " + value);
    }
}
