package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.EnumSet;
import java.util.Set;

/**
 * The ways a body may move through the world to reach something, as the planner names them.
 *
 * <p>These are not moves — the moves are {@link io.github.ivannavas.autocraftai.mob.ai.GoalAction}s and a
 * table chooses between them. These are the planner's permission for a <em>kind</em> of move, and they
 * work as legality masks: a source the planner says is reached by walking never has a shaft sunk towards
 * it, however much the table might like to try. That is the same division of labour as everywhere else in
 * the brief. The model knows that wood is on the surface and iron is under it; the table's job is to learn
 * what to do about that, not to rediscover it by falling down a hole.
 */
public enum Way {

    /** Over the ground, and across water. Never off the table: a body that may not walk can do nothing. */
    WALK,

    /** Sink a shaft. The one way that can put the body somewhere it cannot get out of, so it is opt-in. */
    DIG,

    /** Find the way up or down: slopes, ledges, cave mouths, and stacking blocks under its own feet. */
    CLIMB;

    public static Set<Way> all() {
        return EnumSet.allOf(Way.class);
    }
}
