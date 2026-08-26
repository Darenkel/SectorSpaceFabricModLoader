package menu.main;

import game.GameFileUtils;
import game.Player;
import illuminatus.core.WindowView;
import illuminatus.core.datastructures.List;
import illuminatus.core.datastructures.ListIterator;
import illuminatus.core.graphics.Alpha;
import illuminatus.core.graphics.Color;
import illuminatus.core.graphics.Draw;
import illuminatus.core.graphics.text.AdvancedText;
import illuminatus.core.graphics.text.Font;
import illuminatus.core.graphics.text.Text;
import illuminatus.core.io.Keyboard;
import illuminatus.core.io.Mouse;
import illuminatus.core.io.files.KeyValueDataFile;
import illuminatus.core.tools.util.Utils;
import illuminatus.gui.InternalWindow;
import java.io.File;
import menu.components.GenericWindow;
import menu.components.SimpleButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import com.sector.bridge.ModManagerPane;

@Mixin(value = CharacterSelectionPane.class, remap = false)
public class CharacterSelectionPaneMixin extends SelectionPane{
    
    private static int scrollOffset = 0;

    @Inject(at = @At("HEAD"), method = "draw", remap = false, cancellable = true)
    public static void init() {
    SelectionPane.closeAll();
    NewsPane.closeAll();
    CharacterSelectionPane last = null;
    List<File> files = Utils.getExternalFilesList("./saves", "chr", false);
    ListIterator<File> iterator = files.getIterator();

    int visiblePanes;
    for(visiblePanes = 0; iterator.hasNext(); ++visiblePanes) {
        last = new CharacterSelectionPane(((File)iterator.next()).getPath());
    }

    while(visiblePanes < 5) {
        last = new CharacterSelectionPane((String)null);
        ++visiblePanes;
    }

    if (last != null) {
        last.isLast = true;
    }

    NewsPane.openAll();
   }

   public void draw(){
        Draw.setScissorRegion(ModManagerPane.startX, ModManagerPane.startY, ModManagerPane.boxWidth, ModManagerPane.boxHeight);
            // 1. INPUT HANDLING
        if (illuminatus.core.io.Mouse.SCROLL_UP.press()) {
            scrollOffset -= 20;
        } else if (illuminatus.core.io.Mouse.SCROLL_DOWN.press()) {
            scrollOffset += 20;
        }
   }
}
