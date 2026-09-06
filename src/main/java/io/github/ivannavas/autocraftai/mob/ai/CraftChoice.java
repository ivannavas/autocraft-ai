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

    NOTHING(null),
    PLANKS(Resource.PLANKS),
    STICK(Resource.STICK),
    CRAFTING_TABLE(Resource.CRAFTING_TABLE),
    SWORD(Resource.SWORD),
    PICKAXE(Resource.PICKAXE);

    private final Resource resource;

    CraftChoice(Resource resource) {
        this.resource = resource;
    }

    /** What this makes, or {@code null} for {@link #NOTHING}. */
    public Resource resource() {
        return resource;
    }

    public boolean makesSomething() {
        return resource != null;
    }
}
