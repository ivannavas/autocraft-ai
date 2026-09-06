package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Optional;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * The ladder the run climbs, in order.
 *
 * <p>Every rung is the same shape — hold enough of one {@link Resource} — which is what makes the
 * progression something you extend by adding a line rather than by writing a new class. Completion is read
 * off the inventory, so a body that joins a world already carrying a pickaxe starts partway up rather than
 * being told to go and chop wood it does not need.
 *
 * <p>This is the wood-and-stone stretch the run opens with. The rungs past it — iron, the Nether, the
 * End — are the same shape and go on the end of this list; what gates them is not the ladder but the
 * actions available to climb it, and today there is no action that crafts.
 */
public enum Rung implements Phase {

    /** Punch trees. Three logs is twelve planks, one more than the whole wooden stretch spends. */
    GATHER_LOGS(Resource.LOG, 3, 4.0, BlockTags.LOGS),

    /** Table 4, sticks 2, sword 2, pickaxe 3 — eleven planks before anything is spare. */
    GET_PLANKS(Resource.PLANKS, 11, 2.0, null),

    GET_CRAFTING_TABLE(Resource.CRAFTING_TABLE, 1, 6.0, null),

    /** One stick craft makes four, which is one more than the sword and pickaxe need between them. */
    GET_STICKS(Resource.STICK, 4, 3.0, null),

    /** Before the pickaxe on purpose: the body has to survive the night it spends mining. */
    GET_SWORD(Resource.SWORD, 1, 10.0, null),

    GET_PICKAXE(Resource.PICKAXE, 1, 10.0, null),

    /** With a pickaxe in hand, stone is the first thing worth digging for. */
    GATHER_STONE(Resource.COBBLESTONE, 8, 2.0, BlockTags.BASE_STONE_OVERWORLD);

    private final Resource resource;
    private final int required;
    private final double rewardPerUnit;
    private final TagKey<Block> wanted;

    Rung(Resource resource, int required, double rewardPerUnit, TagKey<Block> wanted) {
        this.resource = resource;
        this.required = required;
        this.rewardPerUnit = rewardPerUnit;
        this.wanted = wanted;
    }

    public Resource resource() {
        return resource;
    }


    public int required() {
        return required;
    }

    /**
     * Reached once the run has held this much at any point, not only while it still is.
     *
     * <p>Measuring the bag as it stands made the first rung unreachable in practice: the crafting table
     * turns logs into planks as soon as it can, so the log count never got to three at once and everything
     * downstream stayed frozen behind it — a body with a sword and a crafting table still being told to go
     * and find wood.
     */
    @Override
    public boolean isComplete(StepContext context) {
        return context.obtained().count(resource) >= required;
    }

    /** Paid per unit picked up, so the climb is rewarded on the way and not only at the top. */
    @Override
    public double score(StepContext context) {
        return context.gained(resource) * rewardPerUnit;
    }

    @Override
    public Optional<TagKey<Block>> wanted() {
        return Optional.ofNullable(wanted);
    }
}
