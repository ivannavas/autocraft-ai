package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /** Twelve: three for the stone pickaxe, eight for the furnace, and one to spare. */
    GATHER_STONE(Resource.COBBLESTONE, 12),

    /** The pickaxe that iron ore will actually yield to. */
    GET_STONE_PICKAXE(Resource.STONE_PICKAXE, 1),

    /** The furnace, so the raw iron the pickaxe digs up can become the iron tools are made of. */
    GET_FURNACE(Resource.FURNACE, 1);

    private final Gather gather;

    Rung(Resource resource, int required) {
        this.gather = Gather.of(resource, required);
    }

    /** Where iron is commonest, and where the ladder sends the body once it has a pick that mines it. */
    private static final int IRON_DEPTH = 16;

    /**
     * The ladder in order, as the plain objectives everything else deals in.
     *
     * <p>Three steps past the last rung that are not rungs, because an enum constant has to be a gather
     * and these are not: a descent to the height iron is thickest at, the raw ore mined there, and then
     * the ingot — which the crafting layer smelts from the ore, once there is a furnace and some fuel.
     * Without them a run with nobody to ask stopped at a stone pickaxe and stood on the grass holding it.
     */
    public static List<Phase> ladder() {
        List<Phase> steps = new ArrayList<>(List.of(values()));
        steps.add(new Descend(IRON_DEPTH, ""));
        steps.add(Gather.of(Resource.RAW_IRON, 1));
        steps.add(Gather.of(Resource.IRON, 1));
        return List.copyOf(steps);
    }

    public Resource resource() {
        return gather.resource();
    }

    public int required() {
        return gather.amount();
    }

    @Override
    public Map<Resource, Integer> needs() {
        return gather.needs();
    }

    @Override
    public Map<Resource, Integer> reserved() {
        return gather.reserved();
    }

    @Override
    public boolean minesWhatItSees() {
        return gather.minesWhatItSees();
    }

    @Override
    public Optional<Resource> scores() {
        return gather.scores();
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

    /** The resource's own folder, shared with every planned objective for the same thing. */
    @Override
    public Pursuit pursuit(BlockState seen, int y, Bounds plan) {
        return gather.pursuit(seen, y, plan);
    }
}
