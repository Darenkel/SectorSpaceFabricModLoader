package com.sector.bridge.moditems;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSFML's own registry of every modded market listing, keyed by the item's assigned id. Populated
 * fresh on every load pass by {@link ModContentManager}, always after {@link ItemDatabase} so a
 * listing's item id has already been resolved.
 * <p>
 * <b>Integration seam:</b> same caveat as {@link ItemDatabase} - {@link #pushAllToGame()} is where
 * each {@link MarketRecord} should get fed into the game's real market/economy system once its
 * actual class/fields are known. Currently a no-op besides logging.
 */
public final class MarketDatabase {

    private static final Map<Integer, MarketRecord> BY_ASSIGNED_ITEM_ID = new LinkedHashMap<>();

    private MarketDatabase() {
    }

    public record MarketRecord(int assignedItemId, String mod, String modItemId, double basePrice,
                                Map<String, Object> rawProperties) {
    }

    static void clear() {
        BY_ASSIGNED_ITEM_ID.clear();
    }

    static MarketRecord register(String mod, ModMarketListing listing, int assignedItemId) {
        MarketRecord record = new MarketRecord(assignedItemId, mod, listing.modItemId(), listing.basePrice(), listing.rawProperties());
        BY_ASSIGNED_ITEM_ID.put(assignedItemId, record);
        return record;
    }

    public static MarketRecord getByAssignedItemId(int assignedItemId) {
        return BY_ASSIGNED_ITEM_ID.get(assignedItemId);
    }

    public static Collection<MarketRecord> all() {
        return BY_ASSIGNED_ITEM_ID.values();
    }

    /**
     * TODO integration seam: push every registered listing into Sector Space's real market database
     * once its class/fields are known. Currently a no-op besides logging what would be registered.
     */
    public static void pushAllToGame() {
        for (MarketRecord record : BY_ASSIGNED_ITEM_ID.values()) {
            System.out.println("SSFML: [MarketDatabase] would list '" + record.mod() + ":" + record.modItemId()
                    + "' (game item id " + record.assignedItemId() + ") at base price " + record.basePrice());
        }
    }
}
