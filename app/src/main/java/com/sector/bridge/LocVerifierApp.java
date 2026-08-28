package com.sector.bridge;

import java.awt.Font;
import java.io.File;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;

/**
 * Small Swing setup dialog used to select the game folder and verify the installed game.
 * <p>
 * It reads:
 * - Sector Space.jar
 * - settings.ini
 * <p>
 * and stores the validated game path and version in LocVerifierCFG.
 */
public class LocVerifierApp {
        public static void main(String[] args) {
            
            // Creates a hidden frame to act as the owner
            JFrame dummy = new JFrame("SSFML Bridge Setup");
            dummy.setUndecorated(true);
            dummy.setVisible(true);
            dummy.setLocationRelativeTo(null);
            
            // Ensure UI runs on the Event Dispatch Thread (standard for Swing)
            JDialog frame = new JDialog(dummy, "Bridge Setup: Verify Game JAR", true);

            // When the dialog closes, make sure to get rid of the dummy too
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                dummy.dispose();
                }
            });
            
            frame.setLayout(new java.awt.FlowLayout());

            JLabel versionLabel = new JLabel("Game Version: Not Detected");
            versionLabel.setFont(new Font("SansSerif", Font.BOLD, 14));

            frame.setSize(400, 150);
            frame.setLocationRelativeTo(null);

            frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                System.exit(0); // This kills the whole process including Fabric
            }
            });

            JButton selectButton = new JButton("Select & Verify Game Folder");

            selectButton.addActionListener(e -> {

                // 1. Check if we are already verified
                if (selectButton.getText().equals("Continue to Game")) {
                    frame.dispose(); // Just close the window and let Fabric continue
                    return;          // EXIT the method so the code below never runs
                }
                JFileChooser chooser = new JFileChooser(); // Create the chooser
                frame.setAlwaysOnTop(false);
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); // Select folder, not file
                int result = chooser.showOpenDialog(frame); // This creates the 'result' variable

                if (result == JFileChooser.APPROVE_OPTION) {
                    File selectedFolder = chooser.getSelectedFile(); // This is the root folder

                    // Define the two files we need to find inside that folder
                    File jarFile = new File(selectedFolder, "Sector Space.jar");
                    File iniFile = new File(selectedFolder, "settings.ini");

                    // 1. Verify BOTH files exist
                    if (jarFile.exists() && iniFile.exists()) {

                        // 2. Call your version check on the INI file
                        String version = getGameVersion(iniFile);

                        if (version != null) {
                            // 3. Save the data: Use the JAR path and the parsed version
                            LocVerifierCFG.saveSettings(jarFile.getAbsolutePath(), version);
                            System.out.println("LocVApp: Verified game at " + selectedFolder.getAbsolutePath()
                                    + " (version " + version + ").");

                            // 4. Update the UI Labels
                            versionLabel.setText("Client Version: " + version + " (Verified)");
                            versionLabel.setForeground(new java.awt.Color(0, 150, 0));

                            // 5. Change the Button to "Continue"
                            selectButton.setText("Continue to Game");
                        } else {
                            System.err.println("LocVApp: Found settings.ini in " + selectedFolder.getAbsolutePath()
                                    + " but could not read a client version from it.");
                            javax.swing.JOptionPane.showMessageDialog(frame,
                                    "Found settings.ini but couldn't read a client version from it!",
                                    "Verification Failed", javax.swing.JOptionPane.ERROR_MESSAGE);
                        }
                    } else {
                        // Error handling for missing files
                        System.err.println("LocVApp: Missing 'Sector Space.jar' or 'settings.ini' in "
                                + selectedFolder.getAbsolutePath());
                        javax.swing.JOptionPane.showMessageDialog(frame,
                                "Missing 'Sector Space.jar' or 'settings.ini' in this folder!",
                                "Verification Failed", javax.swing.JOptionPane.ERROR_MESSAGE);
                    }
                }
            });
            frame.add(versionLabel);
            frame.add(selectButton);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setAlwaysOnTop(true); // Forces it above the terminal/IDE
            frame.toFront();
            frame.requestFocus();
            frame.setVisible(true);
            System.out.println("LocVApp: Setup UI displayed.");
        };

    /**
     * Parses the client version from settings.ini.
     * <p>
     * Expected format:
     *   client version=0.5.9.4
     */
    private static String getGameVersion(File iniFile) {
        try (java.util.Scanner scanner = new java.util.Scanner(iniFile)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                
                // This matches the exact line from your previous screenshot
                if (line.startsWith("client version=")) {
                    // Splits "client version=0.5.9.4" into ["client version", "0.5.9.4"]
                    String[] parts = line.split("=");
                    if (parts.length > 1) {
                        return parts[1].trim(); // Returns "0.5.9.4"
                    }
                }
            }
        } catch (java.io.IOException e) {
            System.err.println("LocVApp: Error reading settings.ini: " + e.getMessage());
        }
        return null; // Returns null if the line is never found
    }

    /**
     * Attempts to verify Sector Space.jar and settings.ini directly in the given folder
     * without showing the setup UI. Returns true and saves the config if both files are
     * found and a version can be parsed, returns false otherwise.
     */
    public static boolean tryAutoDetect(File folder) {
        File jarFile = new File(folder, "Sector Space.jar");
        File iniFile = new File(folder, "settings.ini");

        if (!jarFile.exists() || !iniFile.exists()) {
            return false;
        }

        String version = getGameVersion(iniFile);
        if (version == null) {
            System.err.println("LocVApp: Found Sector Space.jar and settings.ini in "
                    + folder.getAbsolutePath() + " but could not read a client version from it.");
            return false;
        }

        LocVerifierCFG.saveSettings(jarFile.getAbsolutePath(), version);
        System.out.println("LocVApp: Auto-detected game at " + folder.getAbsolutePath()
                + " (version " + version + ").");
        return true;
    }
}
