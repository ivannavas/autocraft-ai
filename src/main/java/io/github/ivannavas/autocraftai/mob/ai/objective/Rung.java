package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.world.level.block.state.BlockState;

/**
 * The opening the run falls back on when nobody is planning it.
 *
 * <p>This used to be the plan itself. It is now the reserve: the objectives come from the planner, and
 * these are what the body climbs while an answer is on its way, or for the whole run when there is no key
 * to ask with. Keeping it is not politeness towards the old design — a run that stops having objectives
 * the moment the network does is a run that stops learning, and the fallback is what makes the planner an
 * improvement rather than a dependency.
 *
 * <p>Every rung is a {@link Gather} like any other, so the two sources of objectives are the same thing to
 * everything downstream. Only the name differs, and only because these seven have been on the overlay long
 * enough to be worth keeping legible.
 */
public enum Rung implements Phase {

    /** Punch trees. Three logs is twelve planks, one more than the whole wooden stretch spends. */
    GATHER_LOGS(Resource.LOG, 3),

    /** Table 4, sticks 2, sword 2, pickaxe 3 — eleven planks before anything is spare. */
    GET_PLANKS(Resource.PLANKS, 11),

    GET_CRAFTING_TABLE(Resource.CRAFTING_TABLE, 1),

    /** One stick craft makes four, which is one more than the sword and pickaxe need between them. */
    GET_STICKS(Resource.STICK, 4),

    /** Before the pickaxe on purpose: the body has to survive the night it spends mining. */
    GET_SWORD(Resource.SWORD, 1),

    GET_PICKAXE(Resource.PICKAXE, 1),

    /** With a pickaxe in hand, stone is the first thing worth digging for. */
    GATHER_STONE(Resource.COBBLESTONE, 8);

    private final Gather gather;

    Rung(Resource resource, int required) {
        this.gather = Gather.of(resource, required);
    }

    /** The ladder in order, as the plain objectives everything else deals in. */
    public static List<Phase> ladder() {
        return List.of(values());
    }

    public Resource resource() {
        return gather.resource();
    }

    public int required() {
        return gather.amount();
    }

    @Override
    public String shape() {
        return gather.shape();
    }

    @Override
    public boolean isComplete(StepContext context) {
        return gather.isComplete(context);
    }

    @Override
    public double score(StepContext context) {
        return gather.score(context);
    }

    @Override
    public Optional<Predicate<BlockState>> wanted() {
        return gather.wanted();
    }
}
