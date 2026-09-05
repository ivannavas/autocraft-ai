package io.github.ivannavas.autocraftai.mob.ai;

import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.goal.ApproachSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.FleeSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.MineSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.RandomStrollGoal;
import io.github.ivannavas.autocraftai.mob.goal.WatchSightingGoal;

/**
 * The moves the brain can make: one column of the Q-table each.
 *
 * <p>Every action builds the goal the engine will actually run, so choosing an action and installing a goal
 * are the same act. Three of the four need something in view to act on; {@link #WANDER} is the fallback
 * that is always available, which is what guarantees the brain always has a legal move.
 *
 * <p>The order of these constants is the column order on disk. A saved table records the names it was
 * written with and is discarded if they no longer match, so reordering or renaming loses the learning
 * rather than quietly reading the wrong column.
 */
public enum GoalAction {

    WANDER {
        @Override
        public MobGoal create(Sighting sighting) {
            return new RandomStrollGoal();
        }

        @Override
        public boolean isApplicable(Sighting sighting) {
            return true;
        }

        @Override
        public boolean usesSighting() {
            return false;
        }
    },

    APPROACH {
        @Override
        public MobGoal create(Sighting sighting) {
            return new ApproachSightingGoal(sighting);
        }
    },

    FLEE {
        @Override
        public MobGoal create(Sighting sighting) {
            return new FleeSightingGoal(sighting);
        }
    },

    WATCH {
        @Override
        public MobGoal create(Sighting sighting) {
            return new WatchSightingGoal(sighting);
        }
    },

    /** Only ever legal against a block: there is nothing to break about a cow. */
    MINE {
        @Override
        public MobGoal create(Sighting sighting) {
            return new MineSightingGoal(sighting);
        }

        @Override
        public boolean isApplicable(Sighting sighting) {
            return sighting.isBlock();
        }
    };

    public abstract MobGoal create(Sighting sighting);

    /** Whether the action means anything given what is in view. */
    public boolean isApplicable(Sighting sighting) {
        return sighting.isValid();
    }

    /**
     * Whether the goal acts on the sighting. An action that ignores it must not be torn down and rebuilt
     * every time something drifts through the field of view.
     */
    public boolean usesSighting() {
        return true;
    }
}
