package io.github.ivannavas.autocraftai.mob.ai;

import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.goal.ApproachSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.DigDownGoal;
import io.github.ivannavas.autocraftai.mob.goal.FleeSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.MineSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.PlaceBlockGoal;
import io.github.ivannavas.autocraftai.mob.goal.RandomStrollGoal;
import io.github.ivannavas.autocraftai.mob.goal.TravelGoal;
import io.github.ivannavas.autocraftai.mob.goal.WatchSightingGoal;

/**
 * The moves the brain can make: one column of the Q-table each.
 *
 * <p>These are the moves that need the body — legs, or eyes, or both. Crafting is not among them: it needs
 * neither, so it is not a choice between doing that and doing something else and it lives in its own table
 * as a thing the body does while it gets on with whatever this picked.
 *
 * <p>Every action builds the goal the engine will actually run, so choosing an action and installing a goal
 * are the same act. Most need something in view to act on; {@link #WANDER} is the fallback that is always
 * available, which is what guarantees the brain always has a legal move.
 *
 * <p>The order of these constants is the column order on disk. A saved table records the names it was
 * written with and is discarded if they no longer match, so reordering or renaming loses the learning
 * rather than quietly reading the wrong column.
 */
public enum GoalAction {

    WANDER {
        @Override
        public MobGoal create(ActionContext context) {
            return new RandomStrollGoal();
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return true;
        }

        @Override
        public boolean usesSighting() {
            return false;
        }
    },

    APPROACH {
        @Override
        public MobGoal create(ActionContext context) {
            return new ApproachSightingGoal(context.sighting());
        }
    },

    /** Only against something that can follow you. Running away from a log is not a strategy. */
    FLEE {
        @Override
        public MobGoal create(ActionContext context) {
            return new FleeSightingGoal(context.sighting());
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.sighting().isValid() && context.sighting().isCreature();
        }
    },

    WATCH {
        @Override
        public MobGoal create(ActionContext context) {
            return new WatchSightingGoal(context.sighting());
        }
    },

    /** Only ever legal against a block: there is nothing to break about a cow. */
    MINE {
        @Override
        public MobGoal create(ActionContext context) {
            return new MineSightingGoal(context.sighting(), context.tool());
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.sighting().isBlock();
        }
    },

    /** Build a step up. Legal whenever the body has something to put down, walled in or not. */
    PLACE {
        @Override
        public MobGoal create(ActionContext context) {
            return new PlaceBlockGoal();
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.hasBlocks();
        }

        @Override
        public boolean usesSighting() {
            return false;
        }
    },

    /**
     * Go somewhere else and keep going. Wandering covers a circle ten blocks across; this is how a body
     * that has been told the problem is the desert it is standing in gets out of the desert.
     */
    TRAVEL {
        @Override
        public MobGoal create(ActionContext context) {
            return new TravelGoal();
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return true;
        }

        @Override
        public boolean usesSighting() {
            return false;
        }
    },

    /**
     * Dig straight down. The one move that can reach anything under the ground, and so the one that makes
     * stone, coal and iron objectives worth setting at all.
     */
    DIG_DOWN {
        @Override
        public MobGoal create(ActionContext context) {
            return new DigDownGoal();
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.canDigDown();
        }

        @Override
        public boolean usesSighting() {
            return false;
        }
    };

    public abstract MobGoal create(ActionContext context);

    /** Whether the action means anything given what is in view and what the run is after. */
    public boolean isApplicable(ActionContext context) {
        return context.sighting().isValid();
    }

    /**
     * Whether the goal acts on the sighting. An action that ignores it must not be torn down and rebuilt
     * every time something drifts through the field of view.
     */
    public boolean usesSighting() {
        return true;
    }
}
