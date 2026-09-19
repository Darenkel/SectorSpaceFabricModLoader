package com.sector.bridge.moditems;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Entry point tying the rest of this package together. Called once per launch, after {@code ModLoader}
 * has settled which mods are enabled: reads each enabled mod's optional {@code ssfml_items.json},
 * resolves/assigns stable ids through {@link ItemIdRegistry}, populates {@link ItemDatabase} and
 * {@link MarketDatabase}, persists the registry, and hands off any ids that had to change this launch
 * to {@link SaveIdMigrator}.
 */
public final class ModContentManager {

    private static final String REGISTRY_FOLDER = "ssfml";
    private static final String REGISTRY_FILE_NAME = "item_id_registry.json";

    private ModContentManager() {
    }

    /**
     * @param gameDir           the game's install directory (same one ModLoader/SectorSpaceProvider use)
     * @param enabledModJars    modId -> its jar file, for every currently-enabled mod with a resolvable fabric.mod.json id
     * @param currentGameVersion the normalized game version this launch is running against
     */
    public static void loadAndApply(File gameDir, Map<String, File> enabledModJars, String currentGameVersion) {
        Path registryPath = gameDir.toPath().resolve("config").resolve(REGISTRY_FOLDER).resolve(REGISTRY_FILE_NAME);
        ItemIdRegistry registry = ItemIdRegistry.load(registryPath);
        registry.markAllInactive();

        ItemDatabase.clear();
        MarketDatabase.clear();

        int vanillaIdCeiling = VanillaIdRangeProvider.getVanillaIdCeiling();

        for (Map.Entry<String, File> entry : enabledModJars.entrySet()) {
            String modId = entry.getKey();
            File modJar = entry.getValue();

            ModContentLoader.ModContent content = ModContentLoader.readModContent(modJar, modId);

            for (ModItemDefinition item : content.items()) {
                ItemIdRegistry.MappingEntry mapping = registry.resolveOrAssign(modId, item.modItemId(), vanillaIdCeiling, currentGameVersion);
                ItemDatabase.register(modId, item, mapping.assignedItemId);
            }

            for (ModMarketListing listing : content.marketListings()) {
                ItemIdRegistry.MappingEntry mapping = registry.resolveOrAssign(modId, listing.modItemId(), vanillaIdCeiling, currentGameVersion);
                MarketDatabase.register(modId, listing, mapping.assignedItemId);
            }

            if (!content.items().isEmpty() || !content.marketListings().isEmpty()) {
                System.out.println("SSFML: " + modId + " registered " + content.items().size() + " item(s) and "
                        + content.marketListings().size() + " market listing(s) via " + ModContentLoader.ITEMS_ENTRY_NAME);
            }
        }

        registry.save();
        logOrphanedMappings(registry);

        List<SaveIdMigrator.PendingRemap> pendingRemaps = new ArrayList<>();
        for (ItemIdRegistry.MappingEntry entry : registry.entriesRemappedThisLaunch(currentGameVersion)) {
            ItemIdRegistry.RemapRecord latest = entry.remapHistory.get(entry.remapHistory.size() - 1);
            pendingRemaps.add(new SaveIdMigrator.PendingRemap(entry.mod, entry.modItemId, latest.oldItemId(), latest.newItemId(), latest.reason()));
        }
        SaveIdMigrator.notifyPendingRemaps(gameDir, pendingRemaps);

        ItemDatabase.pushAllToGame();
        MarketDatabase.pushAllToGame();
    }

    /** Mappings that exist in the registry but weren't declared by any enabled mod this launch - kept around, not deleted, in case the mod comes back. */
    private static void logOrphanedMappings(ItemIdRegistry registry) {
        int orphanCount = 0;
        for (ItemIdRegistry.MappingEntry entry : registry.allEntries()) {
            if (!entry.active) {
                orphanCount++;
            }
        }
        if (orphanCount > 0) {
            System.out.println("SSFML: " + orphanCount + " previously-registered modded item id(s) are not declared by any "
                    + "currently-enabled mod; keeping their id mappings in case that mod is re-enabled later.");
        }
    }
}
