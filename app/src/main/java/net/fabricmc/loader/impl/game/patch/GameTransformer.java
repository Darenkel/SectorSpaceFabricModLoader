package net.fabricmc.loader.impl.game.patch;

import java.util.HashMap;
import java.util.Map;

public class GameTransformer {
    // This is the field that was causing the NPE
    private final Map<String, byte[]> patchedClasses = new HashMap<>();

    // 1. ADD THIS CONSTRUCTOR: Fabric 0.18.4 requires it
    public GameTransformer(GamePatch... patches) {
        // You can ignore the patches for now or store them if you plan to use them.
        // The important part is that this constructor EXISTS.
    }

    public GameTransformer() {
        // Constructor must be public and empty for Knot to instantiate it
    }

    // MANDATORY: Knot calls this to perform the actual transformation
    public byte[] transform(String name) {
        if (patchedClasses.containsKey(name)) {
            return patchedClasses.get(name);
        }
        return null; // Returning null tells Fabric "no patch needed for this class"
    }

    // MANDATORY: Fabric calls this to register patches during initialize()
    public void addPatch(String name, byte[] data) {
        this.patchedClasses.put(name, data);
    }
    
    // MANDATORY: Returns the map for internal auditing
    public Map<String, byte[]> getPatchedClasses() {
        return this.patchedClasses;
    }
}
