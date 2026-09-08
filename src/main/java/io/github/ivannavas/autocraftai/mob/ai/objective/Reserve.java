package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Map;

import net.minecraft.world.item.ItemStack;

/**
 * What the plan is holding back: an amount of something the body is not allowed to spend.
 *
 * <h2>It is about the item, not about the bag</h2>
 * A reserve is a standing rule that lasts as long as the plan does, and it says nothing about whether the
 * body is carrying any of the thing right now. The usual case is the opposite: ten obsidian put aside
 * before a single one has been found, so that the ones it digs up on the way are still there when it gets
 * to the portal. Nothing has to be held for the rule to be in force, and nothing about picking one up
 * releases it — what may be spent is what is held <em>above</em> the line, which for a body ten short is
 * nothing at all.
 *
 * <p>{@link Plan#needs()} says what the objective takes and prices losing it. That is a reward, and a
 * reward is an argument — it can be outvoted by a bigger one, and until the tables have learned it, it is
 * not an argument at all. Some things are not arguments. Four obsidian is not most of a portal; the twelfth
 * one is the portal and the eleventh is nothing, so a run that builds a wall out of its obsidian has not
 * made a slightly worse decision, it has thrown the objective away. The same goes for the three planks a
 * pickaxe needs when there is one log left in the world worth walking to.
 *
 * <p>So the planner can put a number of something aside, and this is what that means in practice: the
 * craft is not offered, and the block is not in the hand. It is a legality mask rather than a price, which
 * is the difference between teaching the run not to and not letting it.
 *
 * <h2>What it holds back is a count, not a thing</h2>
 * Reserving three planks out of eight leaves five to spend. That is the whole arithmetic, and it is why
 * every question here takes a census: what may be spent is what is held less what is kept, and the first
 * half changes every time the body picks something up.
 *
 * <p>A census rather than the inventory itself, because the run already has one taken this step and
 * walking the bag again for every hotbar slot would be paying twice for an answer it was holding.
 *
 * <h2>Two lists, because they came from different places</h2>
 * What the planner puts aside may not be spent at all: not crafted away, not put down as a block. It said
 * so on purpose and it knew what the body was carrying.
 *
 * <p>What an <em>objective</em> puts aside is an inference — "get ten dirt" plainly means "and still have
 * ten dirt" — and an inference should not be able to wall the body in. Told to gather dirt while holding
 * nothing else, a body forbidden to place any of it has lost the one move that gets it out of a hole, and
 * that is a worse failure than the one this is preventing. So the objective's own list stops at the
 * crafting grid, which is where the problem actually was: a log turns into planks and is gone, whereas a
 * block put down is still a block, standing where the body can break it again.
 *
 * @param kept          how many of each the plan will not spend on anything
 * @param keptFromCrafts how many of each the plan will not let a craft consume
 */
public record Reserve(Map<Resource, Integer> kept, Map<Resource, Integer> keptFromCrafts) {

    private static final Reserve NOTHING = new Reserve(Map.of(), Map.of());

    public Reserve {
        kept = kept == null ? Map.of() : Map.copyOf(kept);
        keptFromCrafts = keptFromCrafts == null ? Map.of() : Map.copyOf(keptFromCrafts);
    }

    /** Held back from everything, which is what the planner means when it names something. */
    public Reserve(Map<Resource, Integer> kept) {
        this(kept, Map.of());
    }

    /** Held back from the crafting grid only, which is what a gathering objective implies about itself. */
    public static Reserve fromCrafts(Map<Resource, Integer> kept) {
        return kept == null || kept.isEmpty() ? NOTHING : new Reserve(Map.of(), kept);
    }

    /** The plan has nothing put aside, which is most plans. */
    public static Reserve none() {
        return NOTHING;
    }

    public boolean isEmpty() {
        return kept.isEmpty() && keptFromCrafts.isEmpty();
    }

    /** How many of this the plan is holding back, by whichever of the two lists asks for more. */
    public int keptOf(Resource resource) {
        return Math.max(kept.getOrDefault(resource, 0), keptFromCrafts.getOrDefault(resource, 0));
    }

    /** How many of this the body is free to spend: what it holds, less what is being held back. */
    public int spare(Resource resource, InventoryCensus held) {
        return Math.max(0, held.count(resource) - keptOf(resource));
    }

    /**
     * Whether one of these could be made without eating into the reserve.
     *
     * <p>Priced from {@link Resource#ingredients()} rather than from the recipe book, so it is the plan's
     * own idea of what a thing costs that is checked. The two agree — the map is written from the recipes —
     * and where they could ever drift apart, the plan's is the one this is about.
     */
    public boolean allowsMaking(Resource made, InventoryCensus held) {
        if (isEmpty()) {
            return true;
        }
        for (Map.Entry<Resource, Integer> ingredient : made.ingredients().entrySet()) {
            if (spare(ingredient.getKey(), held) < ingredient.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether this stack may be put down.
     *
     * <p>Only the planner's list is asked, never the objective's own — see the note above about walling
     * the body in with an inference. Anything the plan has no name for is allowed too: the reserve can
     * only hold back what it can count, and a body bricking itself in with gravel is not the problem this
     * solves.
     */
    public boolean allowsPlacing(ItemStack stack, InventoryCensus held) {
        if (kept.isEmpty()) {
            return true;
        }
        return Resource.of(stack)
                .map(resource -> held.count(resource) - kept.getOrDefault(resource, 0) > 0)
                .orElse(true);
    }

    /**
     * How far below the reserve a holding of {@code held} falls, which is what a broken reserve is charged
     * for. Zero when the reserve is intact, and never more than the reserve itself.
     */
    public int shortfall(Resource resource, int held) {
        return Math.max(0, keptOf(resource) - Math.max(0, held));
    }
}
