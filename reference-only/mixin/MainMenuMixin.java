package com.sector.bridge.mixin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sector.bridge.ModManagerPane;
import com.sector.bridge.SectorSpaceProvider;

import illuminatus.core.graphics.text.Text;
import illuminatus.gui.InternalWindow;
import menu.components.GenericWindow;
import menu.components.SimpleButton;
import menu.main.TitleMenuButtons;
import net.fabricmc.loader.api.FabricLoader;


// 1. Add 'remap = false' to the top Mixin annotation
@Mixin(value = TitleMenuButtons.class, remap = false)
public class MainMenuMixin {
    @Unique private SimpleButton modsButton;
    @Unique private static SimpleButton applyBtn = new SimpleButton(" Apply ");

    @Unique private static SimpleButton enabledButton = new menu.components.SimpleButton(" Enabled ");
    @Unique private static SimpleButton disabledButton = new menu.components.SimpleButton(" Disabled ");

    static {
    enabledButton.width = 80;
    enabledButton.height = 24;
    
    disabledButton.width = 80;
    disabledButton.height = 24;
    }

    public String jarName; // This fixes the jarName error in saveState too!
    public boolean isEnabled;

    ModManagerPane modPane = new ModManagerPane(null, null) ;
    // 2. Add 'remap = false' to the constructor injection
    @Inject(at = @At("RETURN"), method = "<init>", remap = false)
    private void onInit(boolean useBackButton, CallbackInfo ci) {
        this.modsButton = new SimpleButton(" Mods ");
        this.modsButton.height = 40;
    }

    // 3. Add 'remap = false' and the Debug Print here
    @Inject(at = @At("RETURN"), method = "update", remap = false)
    private void onUpdate(int x, int y, int height, GenericWindow ref, CallbackInfo ci) {

        if (this.modsButton != null && this.modsButton.update(x + 300, y + height + 20, (InternalWindow)null)) {
            // If it's already visible, hide it. Otherwise, open it.
            if (ModManagerPane.modsMenuVisible) {
                ModManagerPane.modsMenuVisible = false;
                // Optional: If you need to tell the game the "overlay" is closed:
                // OverlaySettingsMenu.close(); 
            } else {
                ModManagerPane.open(); // This sets modsMenuVisible to true
            }
        }
    }

    public void saveState(boolean active) {
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
}

    @Inject(at = @At("TAIL"), method = "draw", remap = false)
    private void onDraw(CallbackInfo ci) {
        if (this.modsButton != null) {
            this.modsButton.draw();
        }
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
        int currentY = ModManagerPane.startY + 10 - ModManagerPane.scrollOffset;
        
        for (ModManagerPane pane : ModManagerPane.modPanes) {
            // Only draw if it's within the box vertically
            if (currentY + 64 > ModManagerPane.startY && currentY < ModManagerPane.startY + ModManagerPane.boxHeight - 40) {
                
                // Draw the "Slot" background for the individual mod
                illuminatus.core.graphics.Color.GREY.use(0.3f);
                illuminatus.core.graphics.Draw.filledRectangle(ModManagerPane.startX + 5, currentY, ModManagerPane.boxWidth - 10, 60);

                // Draw Mod Text and Buttons here...
                Text.draw(pane.mod.getMetadata().getName(), ModManagerPane.startX + 15, currentY + 10);
                            // Only update the "Enabled" button
                if (this.isEnabled == true) { // Or whatever the 'clicked' method is
                    this.isEnabled = false;
                    this.disabledButton.hide = false;
                    this.enabledButton.hide = true;
                    saveState(false);
                } else {
                // Only update the "Disabled" button
                if (this.isEnabled == false) {
                    this.isEnabled = true;
                    saveState(true);
                }
            }
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

    // 3. Position apply Button at the bottom
    if (applyBtn.update(ModManagerPane.startX + 60, ModManagerPane.startY + ModManagerPane.boxHeight + 2, null)) {
        // Save mod list and restart the game.
    }
        applyBtn.draw();
    }
}


