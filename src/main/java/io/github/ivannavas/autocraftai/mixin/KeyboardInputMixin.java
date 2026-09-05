package io.github.ivannavas.autocraftai.mixin;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobEngine;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the keys the human is holding with the commands the goals produced.
 *
 * <p>This runs after vanilla has read the keyboard, so whatever was pressed is simply overwritten. The
 * hook sits here rather than earlier because {@code LocalPlayer.aiStep} calls {@code input.tick()} just
 * before it applies the movement, so the commands the engine built at the start of this tick are the ones
 * that move the body on this tick.
 *
 * <p>There is no on switch: the buffer the engine hands over is empty until a goal writes to it, so a body
 * with nothing to do stands still rather than answering the keyboard.
 *
 * <p>Extends {@link ClientInput} only so the inherited {@code moveVector} is in scope: it is protected, and
 * a mixin reaches a protected member of the target's superclass by declaring that superclass.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("RETURN"))
    private void autocraftAi$driveFromGoals(CallbackInfo ci) {
        MobBody body = MobEngine.get().body();
        this.keyPresses = body.keyPresses();
        this.moveVector = body.moveVector();
    }
}
