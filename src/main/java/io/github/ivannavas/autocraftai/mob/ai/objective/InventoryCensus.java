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

    /**
     * This running total plus whatever was gained between two snapshots.
     *
     * <p>A total, not the most ever held at once. The difference is the whole fix: a body that chops one
     * log, turns it into planks, chops another and turns that into planks too never holds three logs at
     * any instant, so a high-water mark of what is in the bag would sit at one for ever and the first rung
     * would stay just as unreachable. What a rung means by "get three logs" is that three logs were got.
     *
     * <p>Only rises. Spending is not un-getting, and losses — dropping, dying — are handled by the caller
     * throwing the whole total away rather than by counting downwards.
     */
    public InventoryCensus plusGains(InventoryCensus before, InventoryCensus after) {
        Map<Resource, Integer> total = new EnumMap<>(Resource.class);
        for (Resource resource : Resource.values()) {
            int gained = Math.max(0, after.count(resource) - before.count(resource));
            total.put(resource, count(resource) + gained);
        }
        return new InventoryCensus(total);
    }

    /**
     * This total with some of it taken back off, floored at nothing.
     *
     * <p>The one way the total goes down, and it is not a loss: it is a gain that was never one. A body
     * that breaks a block it placed itself has the block back in the bag, and {@link #plusGains} counted
     * that as getting it, so this uncounts it. What it means by "got three logs" is three logs from the
     * world, not one log three times.
     */
    public InventoryCensus less(Map<Resource, Integer> amounts) {
        if (amounts.isEmpty()) {
            return this;
        }
        Map<Resource, Integer> total = new EnumMap<>(counts);
        amounts.forEach((resource, amount) ->
                total.merge(resource, -amount, (held, taken) -> Math.max(0, held + taken)));
        return new InventoryCensus(total);
    }

    /**
     * This census with one count raised to at least the given number.
     *
     * <p>For the one thing the bag does not have to hold to count as had: a crafting table standing
     * within reach is a crafting table for every purpose a plan has.
     */
    public InventoryCensus atLeast(Resource resource, int amount) {
        if (count(resource) >= amount) {
            return this;
        }
        Map<Resource, Integer> raised = new EnumMap<>(counts);
        raised.put(resource, amount);
        return new InventoryCensus(raised);
    }

    /** How many more of this the body holds than the given earlier census. Never negative. */
    public int gainedSince(InventoryCensus earlier, Resource resource) {
        return Math.max(0, count(resource) - earlier.count(resource));
    }
}
