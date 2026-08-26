
package com.sector.bridge;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import illuminatus.core.graphics.text.Text;
import menu.OverlaySettingsMenu;
import menu.components.SimpleButton;
import menu.main.SelectionPane;
import menu.main.TitleMenuButtons;

// This file handles all the Mixins required for the ModManagerPane to function.
@Mixin(value = SelectionPane.class, remap = false)
public class ModManagerMixin {

    @Unique private static SimpleButton backBtn;
    @Unique private static SimpleButton applyBtn;
    private static boolean modsFound = false;
    public TitleMenuButtons menuButtons;
    public static SimpleButton enabledButton = new menu.components.SimpleButton(" Enabled ");
    public static SimpleButton disabledButton = new menu.components.SimpleButton(" Disabled ");
    public boolean isLast = false;
    //public boolean decideDelete = false;
    //public boolean showCreate = false;
    //public CharacterBio bio;
    //public AdvancedText textInput;  
    static {
    enabledButton.width = 80;
    enabledButton.height = 24;
    
    disabledButton.width = 80;
    disabledButton.height = 24;
    }

@Inject(at = @At("HEAD"), method = "draw", remap = false, cancellable = true)
private static void onDraw(CallbackInfo ci) {
    // Inside your onDraw mixin
    if (ModManagerPane.modsMenuVisible) {

        // 1. Draw the Background Box (Empty Slot style)
        illuminatus.core.graphics.Color.BLACK.use(0.6f); // Translucent
        illuminatus.core.graphics.Draw.filledRectangle(ModManagerPane.startX, ModManagerPane.startY, ModManagerPane.boxWidth, ModManagerPane.boxHeight);
        
        illuminatus.core.graphics.Color.WHITE.use(0.3f); // Faint border
        illuminatus.core.graphics.Draw.rectangle(ModManagerPane.startX, ModManagerPane.startY, ModManagerPane.boxWidth, ModManagerPane.boxHeight);

        // 2. Handle Scrolling Input (Mouse Wheel)
        if (illuminatus.core.io.Mouse.SCROLL_UP.press()) {
            ModManagerPane.scrollOffset -= 20;
        } else if (illuminatus.core.io.Mouse.SCROLL_DOWN.press()) {
            ModManagerPane.scrollOffset += 20;
        }

        // 3. The Scissored Loop
        int currentY = ModManagerPane.startY + 5 - ModManagerPane.scrollOffset;
        
        for (ModManagerPane pane : ModManagerPane.modPanes) {
            // Only draw if it's within the box vertically
            if (currentY + 64 > ModManagerPane.startY && currentY < ModManagerPane.startY + ModManagerPane.boxHeight - 40) {
                
                // Draw the "Slot" background for the individual mod
                illuminatus.core.graphics.Color.GREY.use(0.2f);
                illuminatus.core.graphics.Draw.filledRectangle(ModManagerPane.startX + 5, currentY, ModManagerPane.boxWidth - 10, 60);

                // Draw Mod Text and Buttons here...
                Text.draw(pane.mod.getMetadata().getName(), ModManagerPane.startX + 15, currentY + 10);
            }
            currentY += 65; // Height of slot + padding
        }
    }


        // Draw Scrollbar Track (darker)
    int barX = ModManagerPane.startX + ModManagerPane.boxWidth - 15;
    illuminatus.core.graphics.Color.BLACK.use(0.5f);
    illuminatus.core.graphics.Draw.filledRectangle(barX, ModManagerPane.startY, 10, ModManagerPane.boxHeight);

    // Draw Scrollbar Thumb (lighter)
    int totalContentHeight = SectorSpaceProvider.foundMods.size() * 40;
    if (totalContentHeight > ModManagerPane.boxHeight) {
        float scrollPercent = (float)ModManagerPane.scrollOffset / (totalContentHeight - ModManagerPane.boxHeight);
        int thumbHeight = (int)((float)ModManagerPane.boxHeight * ModManagerPane.boxHeight / totalContentHeight);
        int thumbY = ModManagerPane.startY + (int)(scrollPercent * (ModManagerPane.boxHeight - thumbHeight));

        illuminatus.core.graphics.Color.WHITE.use(0.8f);
        illuminatus.core.graphics.Draw.filledRectangle(barX, thumbY, 10, thumbHeight);
    }

    // 3. Position Back Button at the bottom
    if (backBtn.update(ModManagerPane.startX, ModManagerPane.startY + ModManagerPane.boxHeight + 2, null)) {
        ModManagerPane.close();
        OverlaySettingsMenu.close();
    }
    if (applyBtn.update(ModManagerPane.startX + backBtn.width + 60, ModManagerPane.startY + ModManagerPane.boxHeight + 2, null)) {
        // Save mod list and restart the game.
    }
        backBtn.draw();
        applyBtn.draw();

        ci.cancel(); 
}}


/* 1. Initial Scan (Run once)
    if (SectorSpaceProvider.foundMods.isEmpty()) SectorSpaceProvider.scanMods();

    // 1. Draw a slightly lighter background so the box is visible
    illuminatus.core.graphics.Color.GREY.use(0.5f); // Use a semi-transparent gray
    illuminatus.core.graphics.Draw.filledRectangle(ModManagerPane.startX - 10, ModManagerPane.startY - 10, ModManagerPane.boxWidth, ModManagerPane.boxHeight);

    // 2. Draw a white outline so the edges are sharp
    illuminatus.core.graphics.Color.WHITE.use(1.0f);
    illuminatus.core.graphics.Draw.rectangle(ModManagerPane.startX - 10, ModManagerPane.startY - 10, ModManagerPane.boxWidth, ModManagerPane.boxHeight);

    File gameJar = new File(LocVerifierCFG.getProperty("game_path"));
    File folder = new File(gameJar.getParentFile(), "mods");
    System.out.println("DEBUG: Scanning in: " + folder.getAbsolutePath());

    if (folder.exists() && folder.isDirectory() && !modsFound) {
    Draw.setScissorRegion(ModManagerPane.startX, ModManagerPane.startY, ModManagerPane.boxWidth, ModManagerPane.boxHeight); 
    // 2. Loop through and draw mods
    for (int i = 0; i < SectorSpaceProvider.foundMods.size(); i++) {
        SectorSpaceProvider.ModEntry mod = SectorSpaceProvider.foundMods.get(i);
        int currentY = ModManagerPane.startY + (i * 40) - ModManagerPane.scrollOffset;

        // Draw the Toggle Button (Checkbox)
        String toggleText = mod.isEnabled ? "[X]" : "[ ]";
        SimpleButton toggle = new SimpleButton(toggleText);
         if (currentY >= ModManagerPane.startY && currentY <= ModManagerPane.startY + ModManagerPane.boxHeight - 40) {
            mod.isEnabled = !mod.isEnabled;
            // Add logic here to rename .jar to .disabled or update a config
        }
        toggle.draw();
        

        // Draw the Mod Name
        //Text.draw(mod.name, ModManagerPane.startX + 50, currentY + 10, 0.1f, 0.1f);
        //last = new ModSelectionPane(mod.name);
    }
    modsFound = true;
    //Draw.resetScissorRegion();
    } else {
        System.out.println("DEBUG: Folder does not exist!");
    }

        // Draw Scrollbar Track (darker)
    int barX = ModManagerPane.startX + ModManagerPane.boxWidth - 15;
    illuminatus.core.graphics.Color.BLACK.use(0.5f);
    illuminatus.core.graphics.Draw.filledRectangle(barX, ModManagerPane.startY, 10, ModManagerPane.boxHeight);

    // Draw Scrollbar Thumb (lighter)
    int totalContentHeight = SectorSpaceProvider.foundMods.size() * 40;
    if (totalContentHeight > ModManagerPane.boxHeight) {
        float scrollPercent = (float)ModManagerPane.scrollOffset / (totalContentHeight - ModManagerPane.boxHeight);
        int thumbHeight = (int)((float)ModManagerPane.boxHeight * ModManagerPane.boxHeight / totalContentHeight);
        int thumbY = ModManagerPane.startY + (int)(scrollPercent * (ModManagerPane.boxHeight - thumbHeight));

        illuminatus.core.graphics.Color.WHITE.use(0.8f);
        illuminatus.core.graphics.Draw.filledRectangle(barX, thumbY, 10, thumbHeight);
    }

    // --- ADD THIS FOR THE PREVIEW ---
    if (SectorSpaceProvider.foundMods.isEmpty()) {
        int previewY = ModManagerPane.startY + 20; // 20px padding from the top

        // 1. Draw Placeholder Checkbox
        SimpleButton exampleToggle = new SimpleButton("[X]");
        exampleToggle.update(ModManagerPane.startX + 10, previewY, null);
        exampleToggle.draw();

        // 2. Draw Placeholder Text
        Text.draw("Example Mod (Placeholder)", ModManagerPane.startX + 60, previewY + 10);

        // 3. Draw a helpful hint
        Text.draw("Add .jar files to /mods to see them here!", ModManagerPane.startX + 10, previewY + 60);
    }

    // 3. Position Back Button at the bottom
    if (backBtn.update(ModManagerPane.startX, ModManagerPane.startY + ModManagerPane.boxHeight + 2, null)) {
        ModManagerPane.close();
        OverlaySettingsMenu.close();
    }
    if (applyBtn.update(ModManagerPane.startX + backBtn.width + 60, ModManagerPane.startY + ModManagerPane.boxHeight + 2, null)) {
        // Save mod list and restart the game.
    }
        backBtn.draw();
        applyBtn.draw();

        // 3. STOP the game from drawing the vanilla buttons
        ci.cancel(); 
    }
}*/



