package me.aleksilassila.litematica.printer.integration.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;

/** Explicit access to methods declared on PrintHandler's exact build.365 parent. */
@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler",
        remap = false)
public interface ClientPlayerTickHandlerAccessor {
    @Invoker(value = "updateVariables", remap = false)
    void litematicaPrinter$updateVariables();

    @Invoker(value = "isOnCooldown", remap = false)
    boolean litematicaPrinter$isOnCooldown(BlockPos pos);
}
