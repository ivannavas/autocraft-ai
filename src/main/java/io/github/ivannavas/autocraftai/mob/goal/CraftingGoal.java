package io.github.ivannavas.autocraftai.mob.goal;

import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;

/**
 * What the two crafting goals have in common, so the brain can hold either without caring which.
 *
 * <p>They differ in the one way that matters to the engine: the two-by-two one asks for none of the body
 * and runs alongside anything, the table one walks and stands and so cannot.
 */
public interface CraftingGoal extends MobGoal {

    Resource target();

    /** Whether the batch is done, which is what tells the brain to take the goal back out. */
    boolean isFinished();
}
