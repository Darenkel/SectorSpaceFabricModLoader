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
            System.out.println("LocV: Saved configuration to " + CONFIG_FILE + ".");
        } catch (IOException e) {
            System.err.println("LocV: Failed to save configuration to " + CONFIG_FILE + ": " + e);
            e.printStackTrace();
        }
    }

    // Call this in SectorSpaceProvider.java to retrieve data
    public static String getProperty(String key) {
        if (props.isEmpty()) loadSettings();

        return props.getProperty(key);
    }

    private static void loadSettings() {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("LocV: Failed to load configuration from " + CONFIG_FILE + ": " + e);
                e.printStackTrace();
            }
        }
    }

}
