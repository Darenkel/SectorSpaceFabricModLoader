package com.sector.bridge.moditems;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single item declared by a mod in its {@code ssfml_items.json}. Only the fields SSFML itself
 * needs (id, display name, category) are pulled out explicitly; everything else in the mod's JSON
 * object is kept in {@link #rawProperties()} so it can be handed off as-is once SSFML actually wires
 * this into the game's real item system.
 */
record ModItemDefinition(String modItemId, String displayName, String category, Map<String, Object> rawProperties) {

    static ModItemDefinition fromJson(Map<String, Object> json) {
        Object idValue = json.get("id");
        if (!(idValue instanceof String id) || id.isBlank()) {
            throw new Json.JsonParseException("Item entry is missing a non-blank \"id\" field");
        }

        String displayName = json.get("displayName") instanceof String s ? s : id;
        String category = json.get("category") instanceof String s ? s : "uncategorized";

        Map<String, Object> raw = new LinkedHashMap<>(json);
        return new ModItemDefinition(id, displayName, category, raw);
    }
}
