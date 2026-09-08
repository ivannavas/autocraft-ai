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

    NOTHING(null, null, false),
    PLANKS(Resource.PLANKS, null, true),
    STICK(Resource.STICK, null, true),
    CRAFTING_TABLE(Resource.CRAFTING_TABLE, null, true),
    SWORD(Resource.SWORD, null, false),
    PICKAXE(Resource.PICKAXE, null, false),
    /** The tier that mines iron. Its own column, because "a pickaxe" is satisfied by the wooden one. */
    STONE_PICKAXE(Resource.STONE_PICKAXE, null, false),
    /** Eight cobblestone into the block that smelts. Crafted at a table like the rest. */
    FURNACE(Resource.FURNACE, null, false),
    /**
     * The one choice that is not crafted but smelted: raw iron into an ingot, at a furnace, with fuel.
     * Its {@link #input} is what feeds the top slot, which is what tells the brain to reach for a
     * {@link io.github.ivannavas.autocraftai.mob.goal.SmeltGoal} instead of a crafting one.
     */
    IRON(Resource.IRON, Resource.RAW_IRON, false);

    private final Resource resource;
    private final Resource input;
    private final boolean handheld;

    CraftChoice(Resource resource, Resource input, boolean handheld) {
        this.resource = resource;
        this.input = input;
        this.handheld = handheld;
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

    /**
     * Whether this fits the body's own two-by-two grid and so is made in the inventory, with no table to
     * walk to. Planks, sticks and the table itself; a pickaxe or a furnace is three wide and needs a real
     * one. Crafting these in place is not just faster — it is what lets them get made at all when the body
     * is pinned somewhere it cannot walk a step to reach a table.
     */
    public boolean handheld() {
        return handheld;
    }

    /** What a smelted choice feeds into the furnace, or {@code null} when it is crafted. */
    public Resource input() {
        return input;
    }
}
