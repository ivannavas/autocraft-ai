package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Locale;

import net.minecraft.client.player.LocalPlayer;

/**
 * Go somewhere else — somewhere with trees, somewhere flat, somewhere underground.
 *
 * <p>The objective a body in a desert needs and could not be given. Every other shape is about having
 * something or making something, and neither can express "the problem is where you are standing". This one
 * completes on arrival: the biome under the feet is the biome asked for.
 *
 * <h2>Paid by the mile</h2>
 * There is no way to be nearer to a biome you have not found, so unlike the other shapes this one cannot
 * pay for progress towards the thing itself. What it pays for is ground covered, which is the only way to
 * find a biome and the only thing worth encouraging while looking. The rate is small on purpose: paying
 * well for walking taught the body to walk instead of to work, once already.
 */
public record Travel(Terrain terrain, String reason) implements Phase {

    /** Per block covered on the flat. Small: this is a hint to keep moving, not a reason to live. */
    private static final double PER_BLOCK = 0.05;
    /** Ceiling per step, so a long fall or a boat ride cannot pay out a fortune in one go. */
    private static final double MOST_PER_STEP = 3.0;

    public Travel {
        reason = reason == null ? "" : reason.strip();
    }

    @Override
    public String shape() {
        return "GO";
    }

    @Override
    public String name() {
        return "GO_" + terrain.name();
    }

    @Override
    public boolean isComplete(StepContext context) {
        return terrain.matches(biomeAt(context.player()));
    }

    @Override
    public double score(StepContext context) {
        return Math.min(MOST_PER_STEP, context.distanceCovered() * PER_BLOCK);
    }

    /** The biome the body is standing in, namespace and all, or empty when there is no body to ask. */
    public static String biomeAt(LocalPlayer player) {
        return player == null ? "" : player.level().getBiome(player.blockPosition()).getRegisteredName();
    }

    @Override
    public String toString() {
        return "go to " + terrain.name().toLowerCase(Locale.ROOT);
    }
}
