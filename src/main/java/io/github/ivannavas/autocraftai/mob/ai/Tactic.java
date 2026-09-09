package io.github.ivannavas.autocraftai.mob.ai;

import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import io.github.ivannavas.autocraftai.mob.goal.DaylightGoal;
import io.github.ivannavas.autocraftai.mob.goal.FightGoal;
import io.github.ivannavas.autocraftai.mob.goal.FleeSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.HoleUpGoal;
import io.github.ivannavas.autocraftai.mob.goal.TowerGoal;
import io.github.ivannavas.autocraftai.mob.goal.WallOffGoal;
import net.minecraft.world.entity.LivingEntity;

/**
 * What to do about the surroundings as a whole: the columns of the tactics table.
 *
 * <p>Every one of these is a composite — several seconds of work made of moves the body already had,
 * strung together for a purpose the single moves could not express. Attacking was a move; attacking the
 * skeleton before the zombie is a tactic. Placing a block was a move; three of them under the feet, or
 * two between the body and what is coming, is a tactic. They sit over the committed goal the way the
 * water layer does, at a priority nothing else outranks, because a body that is being shot at is not
 * chopping a tree however committed it is — and they let go the moment the table says the surroundings
 * are no longer worth a decision.
 *
 * <p>{@link #CARRY_ON} is what keeps a table asked every second from turning every zombie at forty
 * blocks into a tower.
 */
public enum Tactic {

    /** The surroundings are not the problem, or not one worth acting on. Leave the goal to it. */
    CARRY_ON {
        @Override
        public MobGoal create(Surroundings here, Reserve reserve) {
            return null;
        }

        @Override
        public boolean isApplicable(Surroundings here) {
            return true;
        }
    },

    /**
     * Fight, in the right order: the skeleton first, because it keeps shooting from where it stands,
     * then whatever walks up. Never a creeper, which is a fight nobody wins with a wooden sword.
     */
    FIGHT {
        @Override
        public MobGoal create(Surroundings here, Reserve reserve) {
            return new FightGoal(here.hostiles());
        }

        @Override
        public boolean isApplicable(Surroundings here) {
            if (here.worstToFight() == null) {
                return false;
            }
            // Armed, or one thing that has to walk up to be hit and the health to trade blows with it.
            return here.armed()
                    || (here.count() == 1 && here.health() == Perception.Health.HIGH
                            && !here.threat().contains("SKELETON"));
        }
    },

    /** Get away from the nearest of them. The move the goal table already had, kept as a choice here. */
    RETREAT {
        @Override
        public MobGoal create(Surroundings here, Reserve reserve) {
            LivingEntity nearest = here.nearestHostile();
            return nearest == null ? null : new FleeSightingGoal(Sighting.of(FocusKind.HOSTILE, nearest));
        }

        @Override
        public boolean isApplicable(Surroundings here) {
            return here.count() > 0;
        }
    },

    /**
     * Stack three blocks under the feet and stay up there. Out of reach of everything that walks; not
     * of anything that shoots, which the table is left to find out.
     */
    TOWER {
        @Override
        public MobGoal create(Surroundings here, Reserve reserve) {
            return new TowerGoal(TowerGoal.HEIGHT, reserve);
        }

        @Override
        public boolean isApplicable(Surroundings here) {
            return here.blocks() >= TowerGoal.HEIGHT && here.roomAbove();
        }
    },

    /** Two blocks between the body and the nearest hostile: a wall on the side it is coming from. */
    WALL_OFF {
        @Override
        public MobGoal create(Surroundings here, Reserve reserve) {
            LivingEntity nearest = here.nearestHostile();
            return nearest == null ? null : new WallOffGoal(nearest.position(), reserve);
        }

        @Override
        public boolean isApplicable(Surroundings here) {
            return here.blocks() >= 2 && here.count() > 0
                    && here.nearest() != Perception.Distance.FAR;
        }
    },

    /**
     * Dig two blocks down and cap the hole: the oldest way there is of living through a night with
     * nothing in hand. The dirt that comes out of the hole is what caps it, so a body with no blocks at
     * all can still do it wherever the ground is soft.
     */
    HOLE_UP {
        @Override
        public MobGoal create(Surroundings here, Reserve reserve) {
            return new HoleUpGoal(reserve);
        }

        @Override
        public boolean isApplicable(Surroundings here) {
            return here.canDig();
        }
    },

    /**
     * Get back to the sky: break the ceiling, stack up, cut steps into the wall — whatever gets the body
     * out from under a roof or up out of a pit. The move for a body that has been trapped for a minute
     * with an objective that cannot be reached from where it is.
     */
    DAYLIGHT {
        @Override
        public MobGoal create(Surroundings here, Reserve reserve) {
            return new DaylightGoal(here.surface(), reserve);
        }

        @Override
        public boolean isApplicable(Surroundings here) {
            return here.cover() != Surroundings.Cover.SKY;
        }
    };

    /**
     * The goal that carries this out, or null for the one choice that is the absence of a goal.
     *
     * @param reserve what the plan is holding back, which is never what gets built with
     */
    public abstract MobGoal create(Surroundings here, Reserve reserve);

    /** Whether there is anything for this choice to act on. */
    public abstract boolean isApplicable(Surroundings here);
}
