package io.github.ivannavas.autocraftai.ui;

import io.github.ivannavas.autocraftai.mob.ai.QLearningBrain;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * A button in the corner of the title screen that throws away everything the brain has learned.
 *
 * <p>The title screen is the right place for it: the table is loaded once when the mod starts and written
 * back as the run goes, so between sessions is the only moment wiping it is unambiguous — no world is open,
 * nothing is mid-decision, and nothing will overwrite the wipe a second later.
 *
 * <p>It takes two clicks. Erasing hours of learning is not undoable, and the button sits close enough to
 * the vanilla ones to be hit by accident.
 */
public final class ClearLearningButton {

    private static final int WIDTH = 130;
    private static final int HEIGHT = 20;
    private static final int MARGIN = 4;
    /** The title screen prints its version line along the bottom left, which this has to clear. */
    private static final int VERSION_LINE_ROOM = 12;

    private ClearLearningButton() {
    }

    public static void install(QLearningBrain brain) {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof TitleScreen) {
                // Built fresh on every init, so leaving the screen and coming back disarms it.
                Screens.getWidgets(screen).add(build(brain, scaledHeight));
            }
        });
    }

    private static Button build(QLearningBrain brain, int scaledHeight) {
        boolean[] armed = {false};
        return Button.builder(Component.translatable("gui.autocraft-ai.clear_learning"), button -> {
            if (!armed[0]) {
                armed[0] = true;
                button.setMessage(Component.translatable("gui.autocraft-ai.clear_learning.confirm"));
                return;
            }
            brain.clearLearning();
            armed[0] = false;
            button.setMessage(Component.translatable("gui.autocraft-ai.clear_learning.done"));
            button.active = false;
        }).bounds(MARGIN, scaledHeight - HEIGHT - MARGIN - VERSION_LINE_ROOM, WIDTH, HEIGHT).build();
    }
}
