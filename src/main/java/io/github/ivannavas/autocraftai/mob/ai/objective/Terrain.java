package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.List;
import java.util.Locale;

/**
 * The kinds of place the run can be told to go and find.
 *
 * <p>Matched against the biome's own name rather than against a tag, because what a {@link Travel}
 * objective means is "somewhere like this", and Minecraft's biome list is long, versioned and full of
 * variants — birch forest, old growth pine taiga, windswept savanna. A handful of words that appear in
 * those names covers the lot and keeps working when the next version adds three more.
 *
 * <p>{@link #WOODED} is the one that started this. A body that spawns in a desert and is told to go and get
 * three logs will spend the whole run failing to find a tree, because the objective was the wood and not
 * the forest. Being able to ask for the forest is the point.
 */
public enum Terrain {

    /**
     * Anywhere with trees in it, which is anywhere the run can start. Not a bamboo jungle: it has the word
     * and hardly a tree, and what it has are thirty blocks up behind a wall of bamboo.
     */
    WOODED("forest", "taiga", "jungle", "grove", "wooded", "birch", "woodland"),

    /**
     * Open, flat and safe to cross. Not the snowy kind: snowy plains have the word and none of the
     * trees or the animals, and a body told oak grows on plains spent eight minutes on them finding out.
     */
    PLAINS("plains", "meadow", "savanna", "sunflower"),

    DESERT("desert", "badlands", "mesa"),

    /** High ground: good for seeing, and where stone shows above the surface. */
    MOUNTAIN("peaks", "hills", "windswept", "slopes", "mountain"),

    /** Underground, where the stone and the ore are. */
    CAVE("cave", "lush", "dripstone", "deep_dark"),

    SNOWY("snowy", "frozen", "ice"),

    SWAMP("swamp", "mangrove"),

    WATER("ocean", "river", "beach");

    private final List<String> words;

    Terrain(String... words) {
        this.words = List.of(words);
    }

    /**
     * Whether a biome counts as this kind of place.
     *
     * @param biome the biome's registered name, namespace and all
     */
    public boolean matches(String biome) {
        if (biome == null) {
            return false;
        }
        String lower = biome.toLowerCase(Locale.ROOT);
        if (this == PLAINS && SNOWY.words.stream().anyMatch(lower::contains)) {
            return false;
        }
        if (this == WOODED && lower.contains("bamboo")) {
            return false;
        }
        return words.stream().anyMatch(lower::contains);
    }
}
