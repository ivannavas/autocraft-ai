package io.github.ivannavas.autocraftai.mob.ai;

import java.util.List;
import java.util.OptionalDouble;

import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import io.github.ivannavas.autocraftai.mob.goal.BreakGoal;
import io.github.ivannavas.autocraftai.mob.goal.PlaceBlockGoal;
import io.github.ivannavas.autocraftai.mob.goal.RandomStrollGoal;

/**
 * What to do about terrain that is in the way: the columns of the passage table.
 *
 * <p>Every one of these is a short, finite piece of work — break the two blocks in front, put one block
 * down, dig one out, walk a few steps sideways — and not a policy. The table is asked again a second later
 * with whatever the terrain looks like then, which is what lets a staircase be learned as a sequence of
 * one-block answers rather than needing a staircase move. {@link #CARRY_ON} is the column that lets the
 * table say the terrain is not the problem, and it is what keeps a table that is asked every stuck second
 * from turning every stuck second into a hole in the ground.
 *
 * <h2>Why this is not the interrupt table back again</h2>
 * That one replaced the decision: it pulled the committed goal and installed a rescue in its place, in a
 * state that knew only which goal had stalled. This one sits on top of the commitment the way the water
 * layer does — the primary goal is still there, still committed, and gets the body straight back when the
 * fix is done — and it is keyed on the terrain, which is the thing that actually decides whether breaking
 * or building or going round is the answer. It also learns from its own second, not from the whole move
 * it happened to interrupt.
 */
public enum Passage {

    /** The terrain is not the problem, or not one worth a block. Leave the goal to it. */
    CARRY_ON {
        @Override
        public MobGoal create(Obstruction here, Reserve reserve) {
            return null;
        }

        @Override
        public boolean isApplicable(Obstruction here) {
            return true;
        }
    },

    /**
     * Break through what is in the way: the block at head height first, then the one at the feet, for a
     * body trying to move; the blocks on the line to it, nearest first, for a body trying to get at one.
     * The second is how leaves get cleared off a log.
     */
    BREAK_AHEAD {
        @Override
        public MobGoal create(Obstruction here, Reserve reserve) {
            return new BreakGoal(here.aheadBlocks());
        }

        @Override
        public boolean isApplicable(Obstruction here) {
            return !here.aheadBlocks().isEmpty() && here.aheadBreakable();
        }
    },

    /** Break the ceiling, which is what stands between a body under a ledge and stacking its way up. */
    BREAK_ABOVE {
        @Override
        public MobGoal create(Obstruction here, Reserve reserve) {
            return new BreakGoal(here.ceilingBlocks());
        }

        @Override
        public boolean isApplicable(Obstruction here) {
            // Not on the way down: a canopy over a body digging for cobblestone is not in its way, and
            // the mentor's "break the ceiling" lessons for the way up had it punching leaves instead.
            return here.above() == Obstruction.Above.CEILING && here.ceilingBreakable()
                    && here.wanted() != Obstruction.Wanted.DOWN;
        }
    },

    /**
     * Put a block under the feet and stand on it: one block of height, with room above to do it.
     *
     * <p>A way up, or a way over a wall. Not a way across a drop or across nothing: on the flat with a
     * drop ahead every block laid makes the drop a block deeper and the ground a block further, and
     * from the top of a pillar every direction reads as a drop — so a table that had been taught PILLAR
     * once on that row laid forty-one blocks in four minutes and stood in the sky over a forest with
     * no move left that made way. The precondition is what the move can do, as with the others.
     */
    PILLAR {
        @Override
        public MobGoal create(Obstruction here, Reserve reserve) {
            return new PlaceBlockGoal(null, reserve);
        }

        @Override
        public boolean isApplicable(Obstruction here) {
            boolean overSomething = here.wanted() != Obstruction.Wanted.FLAT
                    || here.ahead() == Obstruction.Ahead.WALL || here.ahead() == Obstruction.Ahead.STEP;
            return here.hasBlocks() && here.above() == Obstruction.Above.OPEN && here.canStack()
                    && here.wanted() != Obstruction.Wanted.DOWN && overSomething;
        }
    },

    /** Take out the block under the feet: one block of depth, where there is ground under it to land on. */
    DIG {
        @Override
        public MobGoal create(Obstruction here, Reserve reserve) {
            return new BreakGoal(List.of(here.under()));
        }

        @Override
        public boolean isApplicable(Obstruction here) {
            return here.canDig() && here.wanted() != Obstruction.Wanted.UP
                    && here.wanted() != Obstruction.Wanted.TOWARD;
        }
    },

    /** Sidestep: a short walk a quarter turn from the way it was facing, to find the end of the wall. */
    AROUND {
        @Override
        public MobGoal create(Obstruction here, Reserve reserve) {
            return new RandomStrollGoal(1.0F, OptionalDouble.of(here.facing() + here.side() * QUARTER));
        }

        @Override
        public boolean isApplicable(Obstruction here) {
            return true;
        }
    },

    /** Turn round: a short walk back the way it came. Sometimes the way out is the way in. */
    BACK {
        @Override
        public MobGoal create(Obstruction here, Reserve reserve) {
            return new RandomStrollGoal(1.0F, OptionalDouble.of(here.facing() + Math.PI));
        }

        @Override
        public boolean isApplicable(Obstruction here) {
            return true;
        }
    };

    private static final double QUARTER = Math.PI / 2.0;

    /**
     * The goal that carries this out, or null for the one choice that is the absence of a goal.
     *
     * @param reserve what the plan is holding back, which is never what gets stacked underfoot
     */
    public abstract MobGoal create(Obstruction here, Reserve reserve);

    /** Whether there is anything for this choice to act on. */
    public abstract boolean isApplicable(Obstruction here);
}
