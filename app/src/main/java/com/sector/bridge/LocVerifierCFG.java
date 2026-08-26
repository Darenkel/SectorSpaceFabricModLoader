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
    public static void saveSettings(String game_path, String game_version) {
        // 1. Force initialization if props is somehow null
        if (props == null) {
            // Since props is 'final', you'll need to remove 'final' from 
            // its declaration at the top for this specific check to work, 
            // OR just ensure it's not null here.
            System.err.println("Props was null! Re-initializing...");
        }
        props.setProperty("game_path", game_path);
        props.setProperty("game_version", game_version);
        try (OutputStream out = new FileOutputStream(CONFIG_FILE)) {
            props.store(out, "Fabric Bridge Configuration");
        } catch (IOException e) {
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
                e.printStackTrace();
            }
        }
    }

}
