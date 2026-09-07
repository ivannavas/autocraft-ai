package io.github.ivannavas.autocraftai.mob.ai.objective;

/**
 * What the planner answered: something to achieve, and where to be while achieving it.
 *
 * <p>The two travel together because they are one judgement. "Fetch cobblestone" and "fetch cobblestone
 * without leaving the forties" are different plans, and the second is the one a person would have meant.
 * Keeping the band on the objective rather than beside it is also what makes it expire correctly: a new
 * objective brings its own band, and nothing is left holding the body to a height that made sense for
 * something it finished ten minutes ago.
 *
 * @param objective what to achieve
 * @param bounds    the heights to stay between while achieving it, or {@link Bounds#anywhere()}
 */
public record Plan(Phase objective, Bounds bounds) {

    public Plan {
        bounds = bounds == null ? Bounds.anywhere() : bounds;
    }

    /** A plan with no opinion about height, which is most of them. */
    public static Plan of(Phase objective) {
        return new Plan(objective, Bounds.anywhere());
    }
}
