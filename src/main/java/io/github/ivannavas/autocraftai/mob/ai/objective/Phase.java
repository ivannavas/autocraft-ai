package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.world.level.block.state.BlockState;

/**
 * One thing the run is trying to bring about, and what getting closer to it is worth.
 *
 * <p>An objective is complete when {@link #isComplete} holds, and until then it is an {@link Objective}
 * like any other, paying for the steps that move towards it. {@link #wanted()} is the objective telling the
 * eyes what to look for, which is how the same view of the same forest turns into a different sighting
 * depending on whether the run is after wood or after stone.
 *
 * <h2>Five shapes, and room for more</h2>
 * "Have N of a thing" was the only shape for a while and it was too narrow to say the obvious: a body in a
 * desert should be told to go and find trees, not to go and find wood that is not there. So there are five
 * — {@link Gather}, {@link Travel}, {@link Descend}, {@link Ascend} and {@link Build} — and the only thing
 * they have to agree on is this interface. Adding a sixth is writing a record: something the run can check
 * for itself, something it can be paid for approaching, and a name.
 *
 * <p>What every shape must have in common is {@link #isComplete}: an objective the run cannot be told it
 * has finished is an objective the run never gets past. That is the whole constraint on what the planner is
 * allowed to ask for, and the reason it answers in a closed vocabulary rather than in prose.
 */
public interface Phase extends Objective {

    /** Whether this has been brought about. */
    boolean isComplete(StepContext context);

    /**
     * A word for the direction this objective pulls in: {@code GET}, {@code GO}, {@code DOWN}, {@code UP},
     * {@code BUILD}.
     *
     * <p>Coarser than the name on purpose. The placement table needs to know whether the run is trying to
     * climb or to sink, because that is what decides whether the block worth breaking is the one above the
     * wall or the one under the feet — and it needs that in a handful of values rather than in the hundreds
     * that {@link #name()} can take.
     */
    String shape();

    /**
     * The kind of block worth noticing while this objective is current, if any. An objective spent at a
     * crafting grid, or one that is about being somewhere rather than having something, wants nothing from
     * the landscape and returns empty.
     */
    default Optional<Predicate<BlockState>> wanted() {
        return Optional.empty();
    }

    /**
     * Whether pursuing this means going down.
     *
     * <p>Asked before the body is allowed to sink a shaft, because that move is the one way it can put
     * itself somewhere it cannot get out of, and it is only ever a route to things that are down there.
     * Stone, coal and iron are; wood is not, and a body after wood that starts digging has stopped
     * looking for wood.
     */
    default boolean wantsDepth() {
        return false;
    }

    /**
     * What the objective says to break this block with, when it has an opinion about this block.
     *
     * <p>An opinion is all it is: the game's own {@link Tool#bestFor(BlockState)} is right about every
     * block whether the objective mentions it or not, and is what gets used when this comes back empty.
     * What the objective adds is that it was <em>told</em> — which is the difference between a body that
     * discovers stone needs a pickaxe by failing at it and one that was told before it started.
     */
    default Optional<Tool> toolFor(BlockState state) {
        return Optional.empty();
    }

    /**
     * Why this is what the run is after, in a sentence, or empty when nobody said. Nothing in the engine
     * reads it — it exists so a watcher can see the planner's thinking rather than only its conclusion.
     */
    default String reason() {
        return "";
    }
}
