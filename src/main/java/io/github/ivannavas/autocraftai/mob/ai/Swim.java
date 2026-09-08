package io.github.ivannavas.autocraftai.mob.ai;

import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.goal.PlaceBlockGoal;
import io.github.ivannavas.autocraftai.mob.goal.SwimGoal;

/**
 * What to do about being in water, which is the one situation the run kept dying in without ever having a
 * decision about it.
 *
 * <p>The columns of the seventh table. It is asked only while the body is actually in water — see
 * {@link Water} — and what it chooses outranks whatever the goal table picked, because the goal table
 * cannot see the water and so cannot be choosing about it. A body chopping a tree it swam up to is
 * choosing well right up to the moment its air runs out.
 *
 * <h2>Why the water is not simply a flag on the main state</h2>
 * It could have been, and one more letter in {@link ActionContext#flags()} would have doubled the number of
 * states every other table has to learn — to represent something that is false in almost every one of them.
 * Worse, it would have said only <em>that</em> there is water, when what decides the answer is how deep,
 * how much breath is left, and whether there is a way out and where. That is a state of its own, so it gets
 * a table of its own, and the split is the same one the rest of the brain is built on.
 *
 * <h2>Doing nothing is a column</h2>
 * {@link #CARRY_ON} is the whole reason this can be asked every second without ruining the run. Wading
 * across a stream is not an emergency, and a table with no way to say "the water is fine" would have to
 * interrupt every crossing.
 */
public enum Swim {

    /** Leave it alone: the water is not the problem, and whatever the goal table chose stands. */
    CARRY_ON {
        @Override
        public MobGoal create(ActionContext context) {
            return null;
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return true;
        }
    },

    /**
     * Get the head into air. The answer to the clock, and not always straight up: under a ledge or a
     * frozen roof the nearest air is sideways, and {@link Water} is what found it.
     */
    SURFACE {
        @Override
        public MobGoal create(ActionContext context) {
            return new SwimGoal(name(), context.water().breathAt());
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.water().breathAt() != null;
        }
    },

    /**
     * Get out of the water altogether, onto ground the body could stand on.
     *
     * <p>Slower than surfacing and worth more when it works: breathing solves the next fifteen seconds,
     * and standing on land solves the situation.
     */
    SHORE {
        @Override
        public MobGoal create(ActionContext context) {
            return new SwimGoal(name(), context.water().shoreAt());
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.water().shoreAt() != null;
        }
    },

    /**
     * Make the ground. Put a block under the body and stand on it, which is the way out of a flooded hole
     * with no shore in it — the death this run kept dying.
     */
    PILLAR {
        @Override
        public MobGoal create(ActionContext context) {
            return new PlaceBlockGoal(null, context.reserve());
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.hasBlocks();
        }
    };

    /** The goal that carries this out, or null for the one choice that is the absence of a goal. */
    public abstract MobGoal create(ActionContext context);

    /** Whether there is anything for this choice to act on. */
    public abstract boolean isApplicable(ActionContext context);
}
