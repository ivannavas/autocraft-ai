package io.github.ivannavas.autocraftai;

import io.github.ivannavas.autocraftai.mob.MobEngine;
import lombok.extern.slf4j.Slf4j;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

@Slf4j
public class AutocraftAiClient implements ClientModInitializer {

    public static final String MOD_ID = "autocraft-ai";

    @Override
    public void onInitializeClient() {
        // Shakedown behaviour for the engine: with nothing else registered, the body wanders.
        //MobEngine.get().addGoal(1, new RandomStrollGoal());

        // The engine runs at the start of the tick so the commands it produces are the ones the player's
        // own tick, later in the same tick, turns into movement.
        ClientTickEvents.START_CLIENT_TICK.register(MobEngine.get()::tick);
    }
}
