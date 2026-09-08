package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.List;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Get down to a depth.
 *
 * <p>This is the objective the run has been missing since stone was first asked for. Stone is underground,
 * the eyes only scan a few blocks up and down, and "have eight cobblestone" says nothing about how to be
 * somewhere there is any — so the body stood on the grass looking for it. Being able to ask for the depth
 * separately is what makes the digging a thing to be rewarded rather than a thing to be hoped for.
 *
 * <p>Paid by the block dropped, which is a real gradient: unlike a biome, you can always tell whether you
 * are closer than you were.
 */
public record Descend(int level, String reason) implements Phase {

    /** Per block of altitude given up. Digging straight down is slow work and has to be worth doing. */
    private static final double PER_BLOCK = 0.6;
    /** The lowest worth asking for: below this is bedrock, and there is nothing down there but lava. */
    public static final int FLOOR = -55;
    /** Above this it is not a descent, it is a walk. */
    public static final int CEILING = 120;

    public Descend {
        level = Math.max(FLOOR, Math.min(CEILING, level));
        reason = reason == null ? "" : reason.strip();
    }

    @Override
    public java.util.OptionalInt height() {
        return java.util.OptionalInt.of(level);
    }

    @Override
    public String shape() {
        return "DOWN";
    }

    @Override
    public String name() {
        return "DIG_TO_" + level;
    }

    @Override
    public boolean isComplete(StepContext context) {
        return context.player() != null && context.player().getBlockY() <= level;
    }

    /** Depth: the lower the body, the further along. */
    @Override
    public double progress(StepContext context) {
        return context.player() == null ? 0.0 : -context.player().getBlockY();
    }

    /**
     * Paid for going down and not charged for coming back up: the general objectives already charge for
     * wasted time, and charging here as well would make a body that has to climb over a ledge to find a
     * way down worse off for taking it.
     */
    @Override
    public double score(StepContext context) {
        double dropped = context.positionBefore().y - context.positionAfter().y;
        return Math.max(0.0, dropped) * PER_BLOCK;
    }

    /** Every way there is: a descent is the one objective a shaft is always a route to. */
    @Override
    public Pursuit pursuit(BlockState seen, int y, Bounds plan) {
        return new Pursuit(shape(), Pursuit.NO_SOURCE, new Whereabouts(plan, List.of(), Way.all()));
    }

    @Override
    public String toString() {
        return "dig down to y=" + level;
    }
}
