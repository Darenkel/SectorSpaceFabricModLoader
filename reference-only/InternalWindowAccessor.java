package com.sector.bridge;

import illuminatus.gui.InternalWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = InternalWindow.class, remap = false)
public interface InternalWindowAccessor {
    @Accessor("x")
    int getX();

    @Accessor("y")
    int getY();

    @Accessor("flagDeleted")
    void setFlagDeleted(boolean value);
}
