package io.github.ivannavas.autocraftai.mob.ai;

import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;

/**
 * What the tactics table can do about the surroundings by itself: nothing. Every strategy it used to
 * hold — fight, retreat, tower, wall off, hole up, daylight — is a skill now, written in the skill
 * language, shipped as a starter shelf, and learned, revised or forgotten like any other; the columns
 * past this one are those skills. This is the one move that is not a strategy: leave the surroundings
 * to the goal underneath.
 */
public enum Tactic {

    CARRY_ON {
        @Override
        public MobGoal create(Surroundings here, Reserve reserve) {
            return null;
        }

        @Override
        public boolean isApplicable(Surroundings here) {
            return true;
        }
    };

    public abstract MobGoal create(Surroundings here, Reserve reserve);

    public abstract boolean isApplicable(Surroundings here);
}
