package com.sector.bridge.moditems;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers Json's parser and writer: round-tripping objects/arrays/primitives, string escaping,
 * and that malformed input throws rather than silently returning something wrong.
 */
class JsonTest {

    @Test
    void parse_readsNestedObjectAndArray() {
        String text = """
                {
                  "name": "laser_rifle_mk2",
                  "basePrice": 500,
                  "tags": ["weapon", "energy"],
                  "enabled": true,
                  "extra": null
                }
                """;

        Map<String, Object> parsed = Json.parseObject(text);

        assertEquals("laser_rifle_mk2", parsed.get("name"));
        assertEquals(500L, parsed.get("basePrice"));
        assertEquals(List.of("weapon", "energy"), parsed.get("tags"));
        assertEquals(Boolean.TRUE, parsed.get("enabled"));
        assertTrue(parsed.containsKey("extra"));
    }

    @Test
    void parse_readsFloatingPointNumbers() {
        Map<String, Object> parsed = Json.parseObject("{\"price\": 12.5}");
        assertEquals(12.5, parsed.get("price"));
    }

    @Test
    void parse_readsEscapedStrings() {
        Map<String, Object> parsed = Json.parseObject("{\"text\": \"line1\\nline2 \\\"quoted\\\"\"}");
        assertEquals("line1\nline2 \"quoted\"", parsed.get("text"));
    }

    @Test
    void write_thenParse_roundTrips() {
        Map<String, Object> original = new java.util.LinkedHashMap<>();
        original.put("id", "example");
        original.put("count", 3L);
        original.put("nested", Map.of("inner", "value"));
        original.put("list", List.of(1L, 2L, 3L));

        String written = Json.write(original);
        Map<String, Object> reparsed = Json.parseObject(written);

        assertEquals(original.get("id"), reparsed.get("id"));
        assertEquals(original.get("count"), reparsed.get("count"));
        assertEquals(original.get("list"), reparsed.get("list"));
    }

    @Test
    void parse_malformedInputThrows() {
        assertThrows(Json.JsonParseException.class, () -> Json.parseObject("{ not valid json"));
    }
}
