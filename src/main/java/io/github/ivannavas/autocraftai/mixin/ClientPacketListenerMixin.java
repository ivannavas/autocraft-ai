package io.github.ivannavas.autocraftai.mixin;

import io.github.ivannavas.autocraftai.mob.DeathNotice;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches the sentence the server sends with a death, so the run can say what killed it.
 *
 * <p>The packet is the only place the cause exists on the client: the death screen keeps it in a private
 * field and the client-side combat tracker knows nothing. The handler runs twice — once on the network
 * thread, which re-schedules it, and once on the game thread — and recording the same sentence twice
 * is harmless.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handlePlayerCombatKill", at = @At("HEAD"))
    private void autocraftAi$noteDeath(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        DeathNotice.record(packet.message().getString());
    }
}
