package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Where a thing is to be found, and how the body is allowed to get there.
 *
 * <p>This is the planner's answer to a question the run used to answer for itself, badly. Whether stone
 * was underground was a flag on the {@link Resource} enum; whether digging was allowed was a rule read off
 * that flag; and where a forest might be was nobody's business at all, so a body after wood in a desert
 * wandered the desert. The model knows all three, and a source it names now says them: the band of heights
 * the thing lives at, the kinds of place it is common in, and the {@link Way}s worth moving by to reach it.
 *
 * <h2>What the tables make of it</h2>
 * The band and the terrain go into the state of the position table — inside or outside the band, in the
 * right kind of place or not — which is what lets "which way" have a learnable answer: towards fresh ground
 * when the terrain is wrong, onward when it is right. The ways are a mask over the moves, and never a
 * lesson: a body is not left to learn that shafts do not lead to trees.
 *
 * @param band    the heights the thing lives between, or {@link Bounds#anywhere()} when the planner did
 *                not say; a plan's own band stands in for it then, see {@link #within(Bounds)}
 * @param terrain the kinds of place it is common in, or empty for no opinion
 * @param ways    how the body may move to reach it; walking is always among them
 */
public record Whereabouts(Bounds band, List<Terrain> terrain, Set<Way> ways) {

    /** What the state key says when the planner named no terrain, so there is nothing to be in or out of. */
    private static final String ANY = "ANY";
    private static final String IN = "IN";
    private static final String OUT = "OUT";

    public Whereabouts {
        band = band == null ? Bounds.anywhere() : band;
        terrain = terrain == null ? List.of() : List.copyOf(terrain);
        EnumSet<Way> allowed = EnumSet.of(Way.WALK);
        if (ways != null) {
            allowed.addAll(ways);
        }
        ways = Collections.unmodifiableSet(allowed);
    }

    /** No opinion: any height, any place, on foot or by climbing. What a plan with no sources gets. */
    public static Whereabouts anywhere() {
        return new Whereabouts(Bounds.anywhere(), List.of(), EnumSet.of(Way.WALK, Way.CLIMB));
    }

    /**
     * What the run used to assume about a resource before the planner could say: on foot and by climbing,
     * and by digging as well for the ones the enum marks as underground. The fixed ladder's rungs get
     * this, and so does a source the planner named without saying where it was.
     */
    public static Whereabouts of(Resource resource) {
        EnumSet<Way> ways = EnumSet.of(Way.WALK, Way.CLIMB);
        if (resource != null && resource.underground()) {
            ways.add(Way.DIG);
        }
        return new Whereabouts(Bounds.anywhere(), resource == null ? List.of() : resource.terrain(), ways);
    }

    /** The same, with the plan's band standing in where this one has no opinion of its own. */
    public Whereabouts within(Bounds plan) {
        return band.bind() || plan == null ? this : new Whereabouts(plan, terrain, ways);
    }

    public boolean allows(Way way) {
        return ways.contains(way);
    }

    /**
     * Whether the body is in the kind of place the thing is found in, as a word for the state key.
     *
     * @param biome the biome's registered name, namespace and all
     */
    public String terrainKey(String biome) {
        if (terrain.isEmpty()) {
            return ANY;
        }
        return terrain.stream().anyMatch(kind -> kind.matches(biome)) ? IN : OUT;
    }
}
