package io.github.ivannavas.autocraftai.mixin;

import io.github.ivannavas.autocraftai.mob.MobEngine;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the game from letting go of an item a goal is still using.
 *
 * <p>Vanilla treats the use key as a hold: every tick {@code Minecraft.handleKeybinds} checks it, and if
 * the player is using an item while the key is up it calls {@code releaseUsingItem}. Nobody here is at the
 * keyboard, so the key is always up, and a mouthful started by {@code useItem} was released one tick later
 * — which is why the body could hold food and starve. A goal that wants the button held says so on the
 * body each tick (see {@code MobBody#holdUse}), and while it does this drops the release.
 *
 * <p>Only the automatic release is affected. A goal that wants to stop — because it was interrupted, or
 * because it is done — simply stops holding, and the next release goes through as normal.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true)
    private void autocraftAi$keepHolding(Player player, CallbackInfo ci) {
        if (MobEngine.get().body().isHoldingUse()) {
            ci.cancel();
        }
    }
}
