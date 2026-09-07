package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.function.Predicate;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The things the run can be told to build.
 *
 * <p>A structure here is deliberately not a blueprint. Checking that ten obsidian sit in exactly the shape
 * of a portal frame would be a lot of code in the service of a distinction the body cannot act on, so what
 * is checked is what is achievable and what matters: enough of the right block, close enough together, in
 * a place the body is standing. Every one of these is three numbers — what, how many, how far — and adding
 * another is one line.
 */
public enum Structure {

    /** Somewhere to craft that is not carried. The one on this list the run can reach today. */
    WORKBENCH(state -> state.is(Blocks.CRAFTING_TABLE), Resource.CRAFTING_TABLE, 1, 6),

    /** Four walls' worth of stone: what surviving a night underground actually takes. */
    SHELTER(state -> state.is(Blocks.COBBLESTONE), Resource.COBBLESTONE, 8, 4),

    /**
     * The frame, not the lit portal. Ten obsidian is a doorway waiting for a light, and the light needs a
     * flint and steel the run cannot make yet — so the frame is where this objective honestly ends, and
     * lighting it is a later objective for a later action set.
     */
    NETHER_PORTAL(state -> state.is(Blocks.OBSIDIAN), Resource.OBSIDIAN, 10, 6);

    private final Predicate<BlockState> block;
    private final Resource material;
    private final int count;
    private final int radius;

    Structure(Predicate<BlockState> block, Resource material, int count, int radius) {
        this.block = block;
        this.material = material;
        this.count = count;
        this.radius = radius;
    }

    public Predicate<BlockState> block() {
        return block;
    }

    /** What it is made of, which is both what to carry and what leaving the bag means progress. */
    public Resource material() {
        return material;
    }

    public int count() {
        return count;
    }

    public int radius() {
        return radius;
    }
}
