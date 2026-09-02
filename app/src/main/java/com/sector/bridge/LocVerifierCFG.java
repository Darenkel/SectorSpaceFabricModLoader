package com.sector.bridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

public class LocVerifierCFG {
    private static final String CONFIG_FILE = "locVerifier_settings.properties";
    private static final Properties props = new Properties();

    static {
        loadSettings();
    }

    // Call this in your UI when verification is successful
    public static void saveSettings(String gamePath, String gameVersion) {
        props.setProperty("game_path", gamePath);
        props.setProperty("game_version", gameVersion);
        try (OutputStream out = new FileOutputStream(CONFIG_FILE)) {
            props.store(out, "Fabric Bridge Configuration");
            System.out.println("LocVCFG: Saved configuration to " + CONFIG_FILE + ".");
        } catch (IOException e) {
            System.err.println("LocVCFG: Failed to save configuration to " + CONFIG_FILE + ": " + e);
            e.printStackTrace();
        }
    }

    // Call this in SectorSpaceProvider.java to retrieve data
    public static String getProperty(String key) {
        if (props.isEmpty()) loadSettings();

        return props.getProperty(key);
    }

    /**
     * Returns the stored game_version, cleaned up into a form Fabric can eat as a
     * version string (leading "v" stripped if there, invalid characters removed). Falls back to
     * "0.0.0" if nothing usable is stored. Shared by SectorSpaceProvider's normalized
     * version and ModLoader's best-effort compatibility logging so both use identical logic.
     */
    public static String getNormalizedGameVersion() {
        String raw = getProperty("game_version");
        if (raw == null) {
            return "0.0.0";
        }

        String trimmed = raw.trim();
        if (trimmed.regionMatches(true, 0, "v", 0, 1)) {
            trimmed = trimmed.substring(1);
        }
        String cleaned = trimmed.replaceAll("[^0-9A-Za-z.\\-+]", "");

        if (cleaned.isEmpty()) {
            System.err.println("LocVCFG: game_version \"" + raw + "\" had no usable characters after cleanup, defaulting to 0.0.0.");
            return "0.0.0";
        }

        // Note: Sector Space's current version scheme has 4 numeric components (e.g. "0.5.9.6")
        // Fabric Loader will still accept this as a valid Version string,
        // it just won't support semver-range dependency comparisons against it.
        // Revisit this later if the version format ever changes shape.
        return cleaned;
    }

    private static void loadSettings() {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("LocVCFG: Failed to load configuration from " + CONFIG_FILE + ": " + e);
                e.printStackTrace();
            }
        }
    }

}
