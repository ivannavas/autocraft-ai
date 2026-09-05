package io.github.ivannavas.autocraftai.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops the mouse from aiming the body. Where it faces is the engine's to decide, always.
 *
 * <p>Only the turning is dropped. The accumulated mouse deltas are zeroed by the caller either way, so
 * nothing builds up to be applied in one lurch.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void autocraftAi$ignoreMouseLook(double partialTick, CallbackInfo ci) {
        ci.cancel();
    }
}
