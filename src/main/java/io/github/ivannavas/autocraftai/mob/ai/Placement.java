package io.github.ivannavas.autocraftai.mob.ai;

/**
 * The state the placement table is keyed by: which verb, which way the run is trying to go, and what is
 * around the body.
 *
 * <p>Not the full observation, and for the same reason the crafting table does not use it either. Most of
 * what is in an observation — which mob, how far off, how much health — has no bearing on whether the block
 * worth breaking is the one in front or the one above it. What does is these three:
 *
 * <ul>
 *   <li><b>the verb</b>, because breaking and building want opposite things from the same geometry: a wall
 *       is what you mine through and what you stand on;</li>
 *   <li><b>where the run is trying to get to</b> — see {@link io.github.ivannavas.autocraftai.mob.ai.objective.Phase}
 *       — because cutting a staircase up and sinking a shaft down are the same move at different blocks,
 *       and only the objective says which one this is;</li>
 *   <li><b>the surroundings</b>, the same short flag string every other table uses, because being walled in
 *       is exactly the situation where the answer stops being "the block in front".</li>
 * </ul>
 *
 * <p>Small on purpose: two verbs, a handful of objective shapes and eight flag combinations is under a
 * hundred states, which is a table that fills up in a session rather than one that never gets a second
 * visit.
 */
public final class Placement {

    private Placement() {
    }

    /**
     * Key for the placement table.
     *
     * @param action the verb about to be used, which is only ever one that acts on a block
     * @param shape  what kind of objective is current — up, down, get, go, build
     * @param flags  the surroundings, as {@link ActionContext#flags()} writes them
     */
    public static String key(GoalAction action, String shape, String flags) {
        return action.name() + '|' + shape + '|' + flags;
    }
}
