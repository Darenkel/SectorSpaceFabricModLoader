package com.sector.bridge.moditems;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSFML's own registry of every modded item that's been assigned an id, keyed both by that assigned
 * id and by (mod, modItemId). This is populated fresh on every load pass by {@link ModContentManager}.
 * <p>
 * <b>Integration seam:</b> Sector Space's actual item database class/fields aren't known yet (the game
 * jar isn't decompiled in this repo). {@link #pushAllToGame()} is where each {@link ItemRecord} here
 * should get fed into the game's real item system - most likely via a Mixin injecting at wherever the
 * game itself registers its own built-in items. Until that's wired up, it only logs what it would do.
 */
public final class ItemDatabase {

    private static final Map<Integer, ItemRecord> BY_ASSIGNED_ID = new LinkedHashMap<>();
    private static final Map<String, ItemRecord> BY_MOD_KEY = new LinkedHashMap<>();

    private ItemDatabase() {
    }

    public record ItemRecord(int assignedItemId, String mod, String modItemId, String displayName, String category,
                              Map<String, Object> rawProperties) {
    }

    static void clear() {
        BY_ASSIGNED_ID.clear();
        BY_MOD_KEY.clear();
    }

    static ItemRecord register(String mod, ModItemDefinition definition, int assignedItemId) {
        ItemRecord record = new ItemRecord(assignedItemId, mod, definition.modItemId(), definition.displayName(),
                definition.category(), definition.rawProperties());
        BY_ASSIGNED_ID.put(assignedItemId, record);
        BY_MOD_KEY.put(mod + "::" + definition.modItemId(), record);
        return record;
    }

    public static ItemRecord getByAssignedId(int assignedItemId) {
        return BY_ASSIGNED_ID.get(assignedItemId);
    }

    public static ItemRecord getByModItemId(String mod, String modItemId) {
        return BY_MOD_KEY.get(mod + "::" + modItemId);
    }

    public static Collection<ItemRecord> all() {
        return BY_ASSIGNED_ID.values();
    }

    /**
     * TODO integration seam: push every registered item into Sector Space's real item database once
     * its class/fields are known. Currently a no-op besides logging what would be registered.
     */
    public static void pushAllToGame() {
        for (ItemRecord record : BY_ASSIGNED_ID.values()) {
            System.out.println("SSFML: [ItemDatabase] would register '" + record.mod() + ":" + record.modItemId()
                    + "' as game item id " + record.assignedItemId() + " (" + record.displayName() + ")");
        }
    }
}
