package io.github.ivannavas.autocraftai.mob.ai;

import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import io.github.ivannavas.autocraftai.mob.goal.PlaceBlockGoal;
import io.github.ivannavas.autocraftai.mob.goal.SwimGoal;

/**
 * What to do about being in water, which is the one situation the run kept dying in without ever having a
 * decision about it.
 *
 * <p>The columns of the water table. It is asked once a second for as long as the body is actually in
 * water — see {@link Water} — on its own clock rather than the commitment's, and what it chooses outranks
 * whatever the goal table picked, because the goal table cannot see the water and so cannot be choosing
 * about it. A body chopping a tree it swam up to is choosing well right up to the moment its air runs out.
 *
 * <p>It used to be asked only when the rest of the brain was deciding, which is to say once per
 * commitment. A body that walked into a lake three seconds into a ten-second move got no answer about the
 * lake for seven seconds, and spent them floating with a goal that had given up. The water does not wait
 * for the commitment and now neither does this.
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
        public MobGoal create(Water water, Reserve reserve) {
            return null;
        }

        @Override
        public boolean isApplicable(Water water, boolean hasBlocks) {
            return true;
        }
    },

    /**
     * Get the head into air. The answer to the clock, and not always straight up: under a ledge or a
     * frozen roof the nearest air is sideways, and {@link Water} is what found it.
     */
    SURFACE {
        @Override
        public MobGoal create(Water water, Reserve reserve) {
            return new SwimGoal(name(), water.breathAt());
        }

        @Override
        public boolean isApplicable(Water water, boolean hasBlocks) {
            return water.breathAt() != null;
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
        public MobGoal create(Water water, Reserve reserve) {
            return new SwimGoal(name(), water.shoreAt());
        }

        @Override
        public boolean isApplicable(Water water, boolean hasBlocks) {
            return water.shoreAt() != null;
        }
    },

    /**
     * Make the ground. Put a block under the body and stand on it, which is the way out of a flooded hole
     * with no shore in it — the death this run kept dying.
     */
    PILLAR {
        @Override
        public MobGoal create(Water water, Reserve reserve) {
            return new PlaceBlockGoal(null, reserve);
        }

        @Override
        public boolean isApplicable(Water water, boolean hasBlocks) {
            return hasBlocks;
        }
    };

    /**
     * The goal that carries this out, or null for the one choice that is the absence of a goal.
     *
     * <p>Handed the water and the reserve and nothing else, on purpose. This is asked every second the
     * body is wet, and the rest of what a decision looks at — the sighting, the recipe book, the wall
     * ahead — is a scan of the surroundings that has no bearing on the water and no business being paid
     * for once a second.
     *
     * @param reserve what the plan is holding back, which is never what gets stacked underfoot
     */
    public abstract MobGoal create(Water water, Reserve reserve);

    /**
     * Whether there is anything for this choice to act on.
     *
     * @param hasBlocks whether the body is carrying something it could put down
     */
    public abstract boolean isApplicable(Water water, boolean hasBlocks);
}
