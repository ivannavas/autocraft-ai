package io.github.ivannavas.autocraftai.mob.ai;

import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.goal.ApproachSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.AttackSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.DigDownGoal;
import io.github.ivannavas.autocraftai.mob.goal.EatGoal;
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
        public MobGoal create(ActionContext context, Aim aim) {
            return new RandomStrollGoal(1.0F, aim.heading());
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return true;
        }

        @Override
        public boolean usesSighting() {
            return false;
        }

        @Override
        public boolean usesGround() {
            return true;
        }
    },

    APPROACH {
        @Override
        public MobGoal create(ActionContext context, Aim aim) {
            return new ApproachSightingGoal(context.sighting());
        }
    },

    /** Only against something that can follow you. Running away from a log is not a strategy. */
    FLEE {
        @Override
        public MobGoal create(ActionContext context, Aim aim) {
            return new FleeSightingGoal(context.sighting());
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.sighting().isValid() && context.sighting().isCreature();
        }
    },

    WATCH {
        @Override
        public MobGoal create(ActionContext context, Aim aim) {
            return new WatchSightingGoal(context.sighting());
        }
    },

    /** Only ever legal against a block: there is nothing to break about a cow. */
    MINE {
        @Override
        public MobGoal create(ActionContext context, Aim aim) {
            // The spot is where the placement table said to swing. Without one — nothing legal, or a
            // rescue that already knows the block it means — the block in view is what gets hit.
            Sighting at = aim.spot() == null
                    ? context.sighting() : Sighting.ofBlock(FocusKind.BLOCK, aim.spot());
            return new MineSightingGoal(at, context.tool());
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.sighting().isBlock();
        }

        @Override
        public boolean usesSpot() {
            return true;
        }
    },

    /**
     * Put a block down. Legal whenever the body has something to put down, walled in or not.
     *
     * <p>Where it goes is the placement table's call: under the feet to climb out of a hole, across a gap
     * to bridge it, over the head for a roof. With nothing chosen it pillars, which is what it always did.
     */
    PLACE {
        @Override
        public MobGoal create(ActionContext context, Aim aim) {
            return new PlaceBlockGoal(aim.spot());
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.hasBlocks();
        }

        @Override
        public boolean usesSighting() {
            return false;
        }

        @Override
        public boolean usesSpot() {
            return true;
        }
    },

    /**
     * Go somewhere else and keep going. Wandering covers a circle ten blocks across; this is how a body
     * that has been told the problem is the desert it is standing in gets out of the desert.
     */
    TRAVEL {
        @Override
        public MobGoal create(ActionContext context, Aim aim) {
            return new TravelGoal(aim.heading());
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return true;
        }

        @Override
        public boolean usesSighting() {
            return false;
        }

        @Override
        public boolean usesGround() {
            return true;
        }
    },

    /**
     * Dig straight down. The one move that can reach anything under the ground, and so the one that makes
     * stone, coal and iron objectives worth setting at all.
     *
     * <p>And the one move that can put the body somewhere it cannot get out of, which is why it is not
     * offered unless down is where the plan is. See {@link ActionContext#worthDigging()}: a body after
     * wood that starts a shaft has not found a slower route to wood, it has left.
     */
    DIG_DOWN {
        @Override
        public MobGoal create(ActionContext context, Aim aim) {
            return new DigDownGoal();
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.canDigDown() && context.worthDigging();
        }

        @Override
        public boolean usesSighting() {
            return false;
        }
    },

    /**
     * Hit the thing in view. The body could approach a zombie, watch it and run from it, and could not
     * touch it — so the only answer it could ever learn to a mob was to leave.
     *
     * <p>Legal against anything alive, with one exception: an animal is not worth killing when the bag is
     * already full of food. That is {@link ActionContext#wellFed()}'s job and it is a rule, not a lesson —
     * the reward for a cow you need and one you do not is identical, so no amount of learning would tell
     * them apart. Hostiles are always fair game, however much is in the larder.
     */
    ATTACK {
        @Override
        public MobGoal create(ActionContext context, Aim aim) {
            return new AttackSightingGoal(context.sighting());
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            if (!context.sighting().isValid() || !context.sighting().isCreature()) {
                return false;
            }
            return context.sighting().kind() != FocusKind.PASSIVE || !context.wellFed();
        }
    },

    /**
     * Eat something. Starving is the one way to die that the body could see coming and had no answer to,
     * because nothing in the action set could hold down the use button.
     */
    EAT {
        @Override
        public MobGoal create(ActionContext context, Aim aim) {
            return new EatGoal();
        }

        @Override
        public boolean isApplicable(ActionContext context) {
            return context.canEat();
        }

        @Override
        public boolean usesSighting() {
            return false;
        }
    };

    /**
     * Builds the goal that carries this move out.
     *
     * @param aim where a block-acting move should act and which way a travelling one should go
     */
    public abstract MobGoal create(ActionContext context, Aim aim);

    /**
     * Whether this move acts on a particular block, and so has a second decision behind it: not only
     * whether to mine or build but where. See {@link io.github.ivannavas.autocraftai.mob.ai.Spot}.
     */
    public boolean usesSpot() {
        return false;
    }

    /**
     * Whether this move takes the body somewhere, and so has a second decision behind it: not only
     * whether to walk but which way. See {@link Ground}.
     */
    public boolean usesGround() {
        return false;
    }

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
