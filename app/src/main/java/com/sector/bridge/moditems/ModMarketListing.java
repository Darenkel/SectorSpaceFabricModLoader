package com.sector.bridge.moditems;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single market listing declared by a mod in its {@code ssfml_items.json}, tying a
 * {@code itemId} (matching a {@code ModItemDefinition}'s id from the same mod) to a base price.
 * Anything else in the listing's JSON object is kept in {@link #rawProperties()}.
 */
record ModMarketListing(String modItemId, double basePrice, Map<String, Object> rawProperties) {

    static ModMarketListing fromJson(Map<String, Object> json) {
        Object itemIdValue = json.get("itemId");
        if (!(itemIdValue instanceof String itemId) || itemId.isBlank()) {
            throw new Json.JsonParseException("Market listing entry is missing a non-blank \"itemId\" field");
        }

        double basePrice = json.get("basePrice") instanceof Number n ? n.doubleValue() : 0.0;

        Map<String, Object> raw = new LinkedHashMap<>(json);
        return new ModMarketListing(itemId, basePrice, raw);
    }
}
