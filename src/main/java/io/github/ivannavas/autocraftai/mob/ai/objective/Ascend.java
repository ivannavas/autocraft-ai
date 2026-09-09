package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Get up to a height.
 *
 * <p>The mirror of {@link Descend}, and the objective a body at the bottom of a hole needs. Everything else
 * it could be told to want is unreachable from down there — the trees are up on the surface, the stone
 * around it is already mined out, and going deeper is the one direction that makes things worse. What it
 * has to do is stack the blocks it is carrying under its own feet and climb out, and
 * {@link io.github.ivannavas.autocraftai.mob.goal.PlaceBlockGoal} is exactly that move. It was always
 * available; what was missing was a reason to make it.
 *
 * <p>Paid by the block gained, which pays for pillaring one block at a time rather than only at the top.
 * That gradient is the difference between a move the table might stumble on and one it can follow.
 */
public record Ascend(int level, String reason) implements Phase {

    /** Per block of height gained. Pillaring up costs a block and a jump, so it has to be worth more. */
    private static final double PER_BLOCK = 0.8;
    /** Below this it is not a climb, it is a step. */
    public static final int FLOOR = -55;
    /** Above this there is nothing but sky. */
    public static final int CEILING = 200;

    public Ascend {
        level = Math.max(FLOOR, Math.min(CEILING, level));
        reason = reason == null ? "" : reason.strip();
    }

    @Override
    public java.util.OptionalInt height() {
        return java.util.OptionalInt.of(level);
    }

    @Override
    public String shape() {
        return "UP";
    }

    @Override
    public String name() {
        return "CLIMB_TO_" + level;
    }

    /** How far under the named height counts as there, once the body is out under the sky on the ground. */
    private static final int NEAR_ENOUGH = 10;

    /**
     * At the height, or out under the open sky on natural ground within a few blocks of it. The planner
     * names a height to mean "get back up to the surface", and it guesses the surface: asked for 68
     * where the savanna stood at 64, the body built a four-block tower in the open to satisfy the
     * number, came down for the next objective, and built it again.
     */
    @Override
    public boolean isComplete(StepContext context) {
        if (context.player() == null) {
            return false;
        }
        int y = context.player().getBlockY();
        if (y >= level) {
            return true;
        }
        if (y < level - NEAR_ENOUGH || !context.player().onGround()) {
            return false;
        }
        net.minecraft.core.BlockPos feet = context.player().blockPosition();
        return context.player().level().canSeeSky(feet.above())
                && !io.github.ivannavas.autocraftai.mob.ai.Placed.get().isOurs(context.player().level(), feet.below());
    }

    /** Height: the higher the body, the further along. */
    @Override
    public double progress(StepContext context) {
        return context.player() == null ? 0.0 : context.player().getBlockY();
    }

    /**
     * Paid for going up and not charged for coming down, for the same reason {@link Descend} is not
     * charged for climbing: a body that has to drop off a ledge to find a way round should not be worse
     * off for taking it, and the standing cost of time already charges for the detour.
     */
    @Override
    public double score(StepContext context) {
        double climbed = context.positionAfter().y - context.positionBefore().y;
        return Math.max(0.0, climbed) * PER_BLOCK;
    }

    /** On foot and by climbing, and never by digging: the way out of a hole is not a deeper hole. */
    @Override
    public Pursuit pursuit(BlockState seen, int y, Bounds plan) {
        return new Pursuit(shape(), Pursuit.NO_SOURCE,
                new Whereabouts(plan, List.of(), EnumSet.of(Way.WALK, Way.CLIMB)));
    }

    @Override
    public String toString() {
        return "climb to y=" + level;
    }
}
