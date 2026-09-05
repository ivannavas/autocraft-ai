package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Optional;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * One rung of the run: a thing to have, and what it is worth getting closer to.
 *
 * <p>A phase is complete when {@link #isComplete} holds, and until then it is an {@link Objective} like any
 * other, paying for the steps that move towards it. {@link #wanted()} is the phase telling the eyes what to
 * look for, which is how the same view of the same forest turns into a different sighting depending on
 * whether the body is after wood or after stone.
 */
public interface Phase extends Objective {

    /** Whether this rung has been reached. */
    boolean isComplete(StepContext context);

    /**
     * The kind of block worth noticing while this phase is current, if any. A phase spent at a crafting
     * grid wants nothing from the landscape and returns empty.
     */
    default Optional<TagKey<Block>> wanted() {
        return Optional.empty();
    }
}
