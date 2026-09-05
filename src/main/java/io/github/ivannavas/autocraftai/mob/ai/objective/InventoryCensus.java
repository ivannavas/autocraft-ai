package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.world.entity.player.Inventory;

/**
 * How much of each {@link Resource} the body was carrying at one instant.
 *
 * <p>Taken once per decision and kept, so the next decision can ask what changed. Objectives that pay for
 * progress need a before and an after, and re-walking the inventory for each of them would be both slower
 * and open to two objectives disagreeing about when the snapshot was taken.
 */
public record InventoryCensus(Map<Resource, Integer> counts) {

    public static InventoryCensus of(Inventory inventory) {
        Map<Resource, Integer> counts = new EnumMap<>(Resource.class);
        for (Resource resource : Resource.values()) {
            counts.put(resource, resource.countIn(inventory));
        }
        return new InventoryCensus(counts);
    }

    /** An empty census, for the first decision of an episode where there is no "before". */
    public static InventoryCensus empty() {
        Map<Resource, Integer> counts = new EnumMap<>(Resource.class);
        for (Resource resource : Resource.values()) {
            counts.put(resource, 0);
        }
        return new InventoryCensus(counts);
    }

    public int count(Resource resource) {
        return counts.getOrDefault(resource, 0);
    }

    /** How many more of this the body holds than the given earlier census. Never negative. */
    public int gainedSince(InventoryCensus earlier, Resource resource) {
        return Math.max(0, count(resource) - earlier.count(resource));
    }
}
