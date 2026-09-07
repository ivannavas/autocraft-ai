package io.github.ivannavas.autocraftai;

import java.nio.file.Path;

import io.github.ivannavas.autocraftai.mob.MobEngine;
import io.github.ivannavas.autocraftai.mob.ai.QLearningBrain;
import io.github.ivannavas.autocraftai.ui.ClearLearningButton;
import io.github.ivannavas.autocraftai.web.Control;
import io.github.ivannavas.autocraftai.web.NewWorld;
import io.github.ivannavas.autocraftai.web.QTableServer;
import io.github.ivannavas.autocraftai.web.Settings;
import lombok.extern.slf4j.Slf4j;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

@Slf4j
public class AutocraftAiClient implements ClientModInitializer {

    public static final String MOD_ID = "autocraft-ai";

    @Override
    public void onInitializeClient() {
        // One file per learned dimension, so a change to one action set never invalidates the others.
        Path storage = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        QLearningBrain brain = new QLearningBrain(MobEngine.get(), storage);

        // The brain goes first: the goal it installs is meant to be the one the engine runs on this tick,
        // and the engine in turn runs before the player's own tick turns commands into movement.
        ClientTickEvents.START_CLIENT_TICK.register(brain::tick);
        ClientTickEvents.START_CLIENT_TICK.register(MobEngine.get()::tick);

        // Learning that only lives in memory is not learning, so write it out on the way out too. The
        // brain also saves as it goes: every hundred decisions, on reaching an objective, and on leaving a
        // world. Closing also lets go of the thread the objective planner asks Claude on.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> brain.close());

        ClearLearningButton.install(brain);

        Settings settings = new Settings(storage);

        // Making a world is several ticks of work — leave, delete, generate — so it rides the client
        // tick like the brain does rather than blocking whichever request handler asked for it.
        NewWorld newWorld = new NewWorld(settings.autoOpen(), storage);
        newWorld.install();
        ClientTickEvents.START_CLIENT_TICK.register(newWorld::tick);

        // The overlay reads a copy the brain hands over after each decision, never the live table. The
        // endpoints go on the same port: the panel that drives the run also embeds the page.
        QTableServer overlay =
                new QTableServer(brain.actionNames(), settings, new Control(settings, brain, newWorld));
        brain.onSnapshot(overlay::publish);
        overlay.start();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> overlay.stop());
    }
}
