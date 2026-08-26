package com.sector.bridge;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

// Manages the actual layout of the Mod Manager's Pane
public class ModManagerPane {
    // Original code -----------------------------------
    /*public void draw(Object backButton, int x, int y, int w, int h) {
        // Use reflection or a cast inside the Mixin to handle this
        illuminatus.core.graphics.Draw.rectangle(x, y, w, h);
    }*/
    // -------------------------------------------------

    public ModContainer mod;
    private boolean isEmptySlot;
    public boolean isEnabled;
    public String jarName; // This fixes the jarName error in saveState too!

    public static void open() { modsMenuVisible = true; }
    public static void close() { modsMenuVisible = false; }
    public static boolean modsMenuVisible = false;
    public static int mods_menu;
    public static int lastXPosition;
    public static int lastYPosition;
    public static boolean mouseHeld;
    public static int scrollOffset = 0;
    public static int boxY = 100; // Starting Y of your box
    public static int entryHeight = 40;

    // Get the current window dimensions
    private static GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();

    public static int screenWidth = gd.getDisplayMode().getWidth();
    public static int screenHeight = gd.getDisplayMode().getHeight();
        
    // Define the dimensions of your "Selection Box"
    public static int boxWidth = (int)(screenWidth * 0.3f);
    public static int boxHeight = (int)(screenHeight * 0.4f);

    //center the box
    public static int startX = (screenWidth - boxWidth) / 2;
    public static int startY = (screenHeight / 2) + 150;

    public static List<ModManagerPane> modPanes = new ArrayList<>();

    public int width;
    public int height;
    public String modText;

    public ModManagerPane(ModContainer mod, List<String> enabledJars) {
        this.mod = mod;
        this.width = 450; // Match CharacterSelectionPane width
        this.height = 64;  // Match CharacterSelectionPane height

    if (mod != null) {
        // Get the actual filename of this mod's jar
        // Note: Fabric's path API might vary slightly by version, 
        // but this is the standard way to get the jar name.
        this.jarName = mod.getOrigin().getPaths().get(0).getFileName().toString();

        if (jarName != null && mod.getMetadata() != null) {
            this.modText = mod.getMetadata().getName();
        }
        // Check if our list contains this jar name
        this.isEnabled = enabledJars.contains(jarName);
    }
    }

    public static void init() {
        modPanes.clear();
        
        // Load your list from the file we discussed earlier
        List<String> enabledJars = new ArrayList<>();
        try {
            Path filePath = FabricLoader.getInstance().getGameDir().resolve("mods/mod_list.cfg");
            if (Files.exists(filePath)) {
                enabledJars = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            if (mod.getMetadata().getType().equals("builtin")) continue;
            
            // Pass the list so the pane can configure its button
            modPanes.add(new ModManagerPane(mod, enabledJars));
        }
    }

    public void update(boolean disable) {
        if (mod == null) return;
    }


/*public void saveState(boolean active) {
    try {
        Path path = FabricLoader.getInstance().getGameDir().resolve("mods/mod_list.cfg");
        List<String> lines = Files.exists(path) ? new ArrayList<>(Files.readAllLines(path)) : new ArrayList<>();

        if (active && !lines.contains(jarName)) {
            lines.add(jarName);
        } else {
            lines.remove(jarName);
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    } catch (IOException e) {
        e.printStackTrace();
    }
}*/

// Logic for Bottom-Left positioning
public static void updateDimensions(int screenWidth, int screenHeight) {
    boxWidth = (int)(screenWidth * 0.4f);  // 40% of screen width
    boxHeight = (int)(screenHeight * 0.4f); // 40% of screen height
    startX = 20; // 20px padding from left
    startY = screenHeight - boxHeight - 20; // 20px padding from bottom
}
}

    /*public void draw() {
        if (!modsMenuVisible) return;
        int yy = this.getY() + 3;
        int xx = this.getX() + 65;

        if (!isEmptySlot) {
            // Use the same Color/Text logic from CharacterSelectionPane.java
            Color.WHITE.use();
            String modName = mod.getMetadata().getName();
            String version = mod.getMetadata().getVersion().getFriendlyString();
            
            Text.draw(modName, xx, yy);
            yy += 17;
            Color.LT_GRAY.use();
            Text.draw("Version: " + version, xx, yy);

            // Draw the action button
            if (this.enabledButton != null) {
                if (this.isEnabled == true){
                this.enabledButton.draw();
                }
                if (this.isEnabled == false){
                this.disabledButton.draw();
                }
            }
    }
    }*/

 /*    public static void draw(Object backButton, Object applyButton) {
        if (!modsMenuVisible) return;

        screenWidth = gd.getDisplayMode().getWidth();
        screenHeight = gd.getDisplayMode().getHeight();

        ModManagerPane.boxWidth = (int)(screenWidth * 0.7f);
        ModManagerPane.boxHeight = (int)(screenHeight * 0.7f);

        //center the box
        ModManagerPane.startX = (screenWidth - boxWidth) / 2;
        ModManagerPane.startY = (screenHeight - boxHeight) / 2;

        // 1. INPUT HANDLING
        if (illuminatus.core.io.Mouse.SCROLL_UP.press()) {
            scrollOffset -= 20;
        } else if (illuminatus.core.io.Mouse.SCROLL_DOWN.press()) {
            scrollOffset += 20;
        }

        // Clamp scroll
        int maxScroll = Math.max(0, (SectorSpaceProvider.foundMods.size() * entryHeight) - boxHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // 2. DRAW BACKGROUND
        illuminatus.core.graphics.Color.BLACK.use(0.9f);
        illuminatus.core.graphics.Draw.filledRectangle(0, 0, 2000, 2000);

        // 3. DRAW MOD LIST
        for (int i = 0; i < SectorSpaceProvider.foundMods.size(); i++) {
            int currentY = boxY + (i * entryHeight) - scrollOffset;

            // Clipping: only draw if inside the box area
            if (currentY >= boxY && currentY <= boxY + boxHeight - entryHeight) {
                SectorSpaceProvider.ModEntry mod = SectorSpaceProvider.foundMods.get(i);
                illuminatus.core.graphics.text.Text.draw(mod.name, startX + 50, currentY + 10);
            }
        }

        // 4. DRAW BUTTONS (at the bottom)
        if (backButton != null) {
            // Position the existing back button
            menu.components.SimpleButton backBtn = (menu.components.SimpleButton)backButton;
            backBtn.x = startX; 
            backBtn.y = boxY + boxHeight + 10;
            backBtn.draw();

            // 5. ADD APPLY & RESTART BUTTON
            // You'll need to create a new SimpleButton instance somewhere (like in open())
            // For now, here is the logic:
            if (applyButton != null) {
                menu.components.SimpleButton applyBtn = (menu.components.SimpleButton)applyButton;
                applyBtn.x = backBtn.x + backBtn.width + 120;
                applyBtn.y = backBtn.y;
                applyBtn.draw();
            }
        }
    }*/


/*       
        public static void open() { modsMenuVisible = true; }
        public static void close() { modsMenuVisible = false; }
        public static boolean modsMenuVisible = false;
        public static int mods_menu;
        public static int lastXPosition;
        public static int lastYPosition;
        public static boolean mouseHeld;
        public static int scrollOffset = 0;
        public static int boxY = 100; // Starting Y of your box
        public static int entryHeight = 40;

        // Get the current window dimensions
        private static GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();

        public static int screenWidth = gd.getDisplayMode().getWidth();
        public static int screenHeight = gd.getDisplayMode().getHeight();
        
        // Define the dimensions of your "Selection Box"
        public static int boxWidth = (int)(screenWidth * 0.7f);
        public static int boxHeight = (int)(screenHeight * 0.7f);

        //center the box
        public static int startX = (screenWidth - boxWidth) / 2;
        public static int startY = (screenHeight - boxHeight) / 2;
*/
/*

    
       public void ModManagerMenu() {
       }
    
       public static void reset() {
          mods_menu = 0;    
       }
    
       public static void update() {
          if (Keyboard.WINDOW_CLOSE.press()) {
             reset();
             modsMenuVisible = false;
          }
       }
    
       public static void updateSettings(InternalWindow attachedTo) {
          int xPos = (int)WindowView.halfWidth() - 240;
          int yPos = (int)WindowView.halfHeight() - 190;
          if (Keyboard.ESCAPE.press() || backButton.update(xPos - 96, yPos - 45, attachedTo)) {
             if (Main.inTitleScreen) {
                reset();
                close();
                return;
             }
          }
    
          int left = backButton.x;

          boolean skip = false;
          yPos += 36;
          skip = false;

    
          if (Mouse.LEFT.press()) {
             timer = new SimpleTimer(3, false, true);
          }
    
          if (timer.update()) {
             mouseHeld = true;
          }
    
          if (Mouse.LEFT.release()) {
             mouseHeld = false;
          }
    
          yPos += 28;
          KeyMap.updateSettingButtons(xPos + 14, yPos);
          KeyMap.disableKeys();
       }
    
       public static void open() {
          reset();
          modsMenuVisible = true;
       }
    
       public static void close() {
          modsMenuVisible = false;
       }
    
       public static boolean isOpen() {
          return modsMenuVisible;
       }
    
       public static void draw() {
        if (modsMenuVisible) {
            Color.push();
            Alpha.push();
            Color.BLACK.use(0.9f);
            // Draw the solid background to hide character slots
            Draw.filledRectangle(0.0, 0.0, WindowView.width(), WindowView.height());
            Color.WHITE.use();

            if (backButton != null) {
                backButton.draw();
            }
            
            // Optional: Draw your "Mod Manager" title here
            // Draw.drawString("Mod Manager", 20, 20, 0xFFFFFFFF);
            
            Color.pop();
            Alpha.pop();
        }
    }*/
    
    
    /*public SimpleButton backButton;

    public ModManagerPane() {
        this.backButton = new SimpleButton(" Back ");
    }

    public void draw(int x, int y, int w, int h) {
        // Use the game's static draw methods directly
        illuminatus.core.graphics.Draw.rectangle(x, y, w, h);
        this.backButton.draw();
    }

    public void update(int x, int y, int w, int h) {
        if (this.backButton.update(x + 20, y + h - 60, null)) {
            SectorSpaceProvider.mods_menu = 0;
        }
    }*/
