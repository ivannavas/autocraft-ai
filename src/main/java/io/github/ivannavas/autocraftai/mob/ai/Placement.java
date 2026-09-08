package io.github.ivannavas.autocraftai.mob.ai;

/**
 * The state the placement table is keyed by: which source is in play, which verb, and what is around the
 * body.
 *
 * <p>Not the full observation, and for the same reason the crafting table does not use it either. Most of
 * what is in an observation — which mob, how far off, how much health — has no bearing on whether the block
 * worth breaking is the one in front or the one above it. What does is these three:
 *
 * <ul>
 *   <li><b>the source</b>, because the table lives in a folder that already says what the run is after
 *       (see {@link io.github.ivannavas.autocraftai.mob.ai.objective.Pursuit}) and this is the one thing
 *       within the folder that changes the geometry: an ore is dug out from under the feet and a log is
 *       cut from in front;</li>
 *   <li><b>the verb</b>, because breaking and building want opposite things from the same geometry: a wall
 *       is what you mine through and what you stand on;</li>
 *   <li><b>the surroundings</b>, the same short flag string every other table uses, because being walled in
 *       is exactly the situation where the answer stops being "the block in front".</li>
 * </ul>
 *
 * <p>Which way the run is pulling — up, down, get, go — used to be the middle field. It is the folder now,
 * which is the same information kept in a place that does not have to be spelled into every key.
 */
public final class Placement {

    private Placement() {
    }

    /**
     * Key for the placement table.
     *
     * @param source the source in play, as {@code Pursuit#source()} names it
     * @param action the verb about to be used, which is only ever one that acts on a block
     * @param flags  the surroundings, as {@link ActionContext#flags()} writes them
     */
    public static String key(String source, GoalAction action, String flags) {
        return source + '|' + action.name() + '|' + flags;
    }
}
