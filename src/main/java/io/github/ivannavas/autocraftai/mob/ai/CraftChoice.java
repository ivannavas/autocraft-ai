package io.github.ivannavas.autocraftai.mob.ai;

import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;

/**
 * What the crafting table can decide to make, including deciding not to.
 *
 * <p>Only things that are actually made are here. Logs and cobblestone are gathered, and offering them as
 * craft choices would spend exploration on columns that can never be legal.
 *
 * <p>{@link #NOTHING} is not padding. Without it the body would be obliged to craft something whenever
 * anything at all was craftable, which — with a log in the bag — is nearly always.
 */
public enum CraftChoice {

    NOTHING(null, null),
    PLANKS(Resource.PLANKS, null),
    STICK(Resource.STICK, null),
    CRAFTING_TABLE(Resource.CRAFTING_TABLE, null),
    SWORD(Resource.SWORD, null),
    PICKAXE(Resource.PICKAXE, null),
    /** The tier that mines iron. Its own column, because "a pickaxe" is satisfied by the wooden one. */
    STONE_PICKAXE(Resource.STONE_PICKAXE, null),
    /** Eight cobblestone into the block that smelts. Crafted at a table like the rest. */
    FURNACE(Resource.FURNACE, null),
    /**
     * The one choice that is not crafted but smelted: raw iron into an ingot, at a furnace, with fuel.
     * Its {@link #input} is what feeds the top slot, which is what tells the brain to reach for a
     * {@link io.github.ivannavas.autocraftai.mob.goal.SmeltGoal} instead of a crafting one.
     */
    IRON(Resource.IRON, Resource.RAW_IRON);

    private final Resource resource;
    private final Resource input;

    CraftChoice(Resource resource, Resource input) {
        this.resource = resource;
        this.input = input;
    }

    /** What this makes, or {@code null} for {@link #NOTHING}. */
    public Resource resource() {
        return resource;
    }

    public boolean makesSomething() {
        return resource != null;
    }

    /** Whether this is smelted at a furnace rather than crafted; then {@link #input} is what it consumes. */
    public boolean isSmelted() {
        return input != null;
    }

    /** What a smelted choice feeds into the furnace, or {@code null} when it is crafted. */
    public Resource input() {
        return input;
    }
}
