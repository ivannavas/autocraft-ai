package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Map;

/**
 * What the planner answered: something to achieve, and where to be while achieving it.
 *
 * <p>The two travel together because they are one judgement. "Fetch cobblestone" and "fetch cobblestone
 * without leaving the forties" are different plans, and the second is the one a person would have meant.
 * Keeping the band on the objective rather than beside it is also what makes it expire correctly: a new
 * objective brings its own band, and nothing is left holding the body to a height that made sense for
 * something it finished ten minutes ago.
 *
 * <h2>And what it will take</h2>
 * {@link #needs()} is the shopping list: everything the run has to be holding for the objective to be
 * reachable, not only the objective's own item. "Get a pickaxe" needs planks and sticks too, and nothing
 * about the word "pickaxe" says so. It matters in two places — the crafting table is keyed by what is
 * still short, and anything on the list leaving the bag is charged for, whether it was crafted away or
 * built into a wall.
 *
 * <p>The planner fills it in. When it says nothing the objective's own idea of what it takes is used
 * instead, so the list is never empty and nothing depends on the model having been thorough.
 *
 * <h2>And what it must not spend</h2>
 * {@link #reserved()} is the harder half of the same thought. The shopping list prices losing something;
 * the reserve forbids it, by keeping the craft off the menu and the block out of the hand. They are
 * different tools for different failures — see {@link Reserve}.
 *
 * <p>Both lists are unions with the objective's own. What the planner says the run needs is added to what
 * the objective already implies, because it knows more — the whole chain behind a pickaxe is invisible
 * from the word "pickaxe" — and forgets the obvious: asked for planks it lists the logs and not the planks.
 * What it says must not be spent is added too, because some objectives are themselves a statement about
 * what may not be spent and the model cannot be relied on to restate it. "Get three logs" is finished by holding three logs, so it holds three logs back whether the
 * reply mentioned it or not — and where both name the same thing, the larger number wins.
 *
 * @param objective what to achieve
 * @param bounds    the heights to stay between while achieving it, or {@link Bounds#anywhere()}
 * @param needs     what the run has to be holding, and how much of each
 * @param reserved  what it may not spend on the way, and how much of each
 */
public record Plan(Phase objective, Bounds bounds, Map<Resource, Integer> needs,
                   Reserve reserved) {

    public Plan {
        bounds = bounds == null ? Bounds.anywhere() : bounds;
        needs = union(objective.needs(), needs);
        reserved = merge(objective, reserved);
    }

    /**
     * The objective's own list and the planner's, together, the larger number winning.
     *
     * <p>Together rather than one replacing the other, because the planner keeps leaving the objective's
     * own item off: asked for twelve planks it listed the three logs they come from and not the planks,
     * and a crafting table keyed on what is short never saw planks as short at all. What the objective
     * says it takes is the floor; the planner only ever adds to it.
     */
    private static Map<Resource, Integer> union(Map<Resource, Integer> own, Map<Resource, Integer> told) {
        if (told == null || told.isEmpty()) {
            return own;
        }
        Map<Resource, Integer> both = new java.util.LinkedHashMap<>(own);
        told.forEach((resource, amount) -> both.merge(resource, amount, Math::max));
        return Map.copyOf(both);
    }

    /**
     * The planner's reserve and the objective's, kept as the two different things they are.
     *
     * <p>The planner's bars spending of every kind; the objective's bars only what a craft would consume.
     * {@link Reserve} says why — an inference about what "get ten dirt" means must not be able to take
     * away the last block the body had to climb out of a hole with.
     */
    private static Reserve merge(Phase objective, Reserve told) {
        Map<Resource, Integer> own = objective.reserved();
        if (told == null || told.isEmpty()) {
            return Reserve.fromCrafts(own);
        }
        return own.isEmpty() ? told : new Reserve(told.kept(), own);
    }

    /** A plan with no opinion about height and no list but the objective's own, which is most of them. */
    public static Plan of(Phase objective) {
        return new Plan(objective, Bounds.anywhere(), Map.of(), Reserve.none());
    }

    public Plan(Phase objective, Bounds bounds) {
        this(objective, bounds, Map.of(), Reserve.none());
    }

    public Plan(Phase objective, Bounds bounds, Map<Resource, Integer> needs) {
        this(objective, bounds, needs, Reserve.none());
    }
}
