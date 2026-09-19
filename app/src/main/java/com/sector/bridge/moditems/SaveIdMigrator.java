package com.sector.bridge.moditems;

import java.io.File;
import java.util.List;

/**
 * Handles what should happen to existing save files after {@link ModContentManager} detects that one
 * or more modded item ids had to be reassigned (see {@link ItemIdRegistry}'s remap history) - most
 * commonly because a game update's new vanilla items grew into id space a mod's item used to safely
 * occupy.
 * <p>
 * <b>Integration seam:</b> Sector Space's save file format/location isn't known yet, so this can't
 * actually rewrite anything yet. {@link #notifyPendingRemaps(File, List)} is the seam: once the save
 * format is known, this is where save files should be located, scanned for item id references matching
 * {@link PendingRemap#oldItemId()}, and rewritten to {@link PendingRemap#newItemId()} instead - ideally
 * only for saves whose recorded game version predates the remap. For now it only logs what would need
 * to change, so remaps are never silently lost even though nothing is corrected on disk yet.
 */
public final class SaveIdMigrator {

    private SaveIdMigrator() {
    }

    public record PendingRemap(String mod, String modItemId, int oldItemId, int newItemId, String reason) {
    }

    public static void notifyPendingRemaps(File gameDir, List<PendingRemap> remaps) {
        if (remaps.isEmpty()) {
            return;
        }

        for (PendingRemap remap : remaps) {
            System.out.println("SSFML: [SaveIdMigrator] " + remap.mod() + ":" + remap.modItemId()
                    + " changed from item id " + remap.oldItemId() + " to " + remap.newItemId()
                    + " (" + remap.reason() + "). Save files referencing the old id need updating, "
                    + "but automatic save migration isn't implemented yet - the save format is unknown.");
        }

        // TODO: once the save format/location is known, locate save files under gameDir, replace
        // references to each PendingRemap.oldItemId() with newItemId() for saves predating this remap,
        // and write them back (ideally after backing up the original file).
    }
}
