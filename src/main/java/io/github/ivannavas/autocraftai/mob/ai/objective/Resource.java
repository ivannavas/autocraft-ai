package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The things the run can be told to go and get.
 *
 * <p>Gathering is only one of the shapes an objective takes — see {@link Phase} for the others — but it is
 * the one with a vocabulary, and this is it. Keeping the list short and explicit is what lets the whole
 * inventory be summarised once a decision instead of being re-scanned by each objective in turn, and it is
 * also what the planner is allowed to name: a resource nothing can count is a resource the run would never
 * be told it had enough of.
 *
 * <p>Tags rather than items wherever a tag exists: a birch log is wood, and the brain has no business
 * learning oak and birch separately.
 *
 * <h2>Worth belongs to the thing, not to the objective</h2>
 * What picking one up pays is a property of the resource — a log is four points of progress whether the run
 * was asked for three of them or for thirty — so it lives here rather than being restated by every
 * objective that mentions it. The same goes for {@link #inWorld()}: what a log looks like in the landscape
 * does not depend on who asked for it.
 *
 * <p>The world form is a predicate rather than a tag because not everything worth walking to has a tag.
 * Obsidian is one block and no more, coal ore is two, wood is a whole tag; a predicate says all three
 * without pretending they are the same kind of thing.
 */
public enum Resource {

    LOG(stack -> stack.is(ItemTags.LOGS), 4.0, state -> state.is(BlockTags.LOGS), Where.SURFACE),
    PLANKS(stack -> stack.is(ItemTags.PLANKS), 2.0, null, Where.SURFACE),
    STICK(stack -> stack.is(Items.STICK), 3.0, null, Where.SURFACE),
    CRAFTING_TABLE(stack -> stack.is(Items.CRAFTING_TABLE), 6.0,
            state -> state.is(Blocks.CRAFTING_TABLE), Where.SURFACE),
    /** Made of eight cobblestone, placed and kept: it is what turns raw iron into iron. */
    FURNACE(stack -> stack.is(Items.FURNACE), 6.0, state -> state.is(Blocks.FURNACE), Where.SURFACE),
    /** Any pickaxe at all: the first one, which is wood, or better. What the ladder's rung asks for. */
    PICKAXE(stack -> stack.is(ItemTags.PICKAXES), 10.0, null, Where.SURFACE),
    /**
     * A stone pickaxe in particular, because iron ore drops nothing to a wooden one. The one place the
     * tier is the whole point: a plan that wants iron has to be able to ask for this and not for "a pick".
     */
    STONE_PICKAXE(stack -> stack.is(Items.STONE_PICKAXE), 14.0, null, Where.SURFACE),
    SWORD(stack -> stack.is(ItemTags.SWORDS), 10.0, null, Where.SURFACE),
    COBBLESTONE(stack -> stack.is(Items.COBBLESTONE), 2.0,
            state -> state.is(BlockTags.BASE_STONE_OVERWORLD), Where.UNDERGROUND),

    /** Free to dig and the cheapest thing to build with, which is what makes it worth naming. */
    DIRT(stack -> stack.is(Items.DIRT), 1.0, state -> state.is(BlockTags.DIRT), Where.SURFACE),
    /** What a desert has instead of dirt. Builds just as well, and a plan made in a desert needs a word for it. */
    SAND(stack -> stack.is(ItemTags.SAND), 1.0, state -> state.is(BlockTags.SAND), Where.SURFACE),
    /** Another block that is everywhere and stacks: beaches, riverbeds, and most of the underground. */
    GRAVEL(stack -> stack.is(Items.GRAVEL), 1.0, state -> state.is(Blocks.GRAVEL), Where.SURFACE),

    COAL(stack -> stack.is(Items.COAL), 6.0,
            state -> state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE), Where.UNDERGROUND),

    /** What the ore drops. Dug up with a stone pickaxe, then smelted into {@link #IRON}. */
    RAW_IRON(stack -> stack.is(Items.RAW_IRON), 12.0, state -> state.is(BlockTags.IRON_ORES),
            Where.UNDERGROUND),

    /**
     * The ingot: usable iron, and the only kind tools are made of. It is not found and not crafted — it
     * is smelted from {@link #RAW_IRON} in a {@link #FURNACE}, which is why its world form is empty and
     * its recipe lives in the smelting goal rather than the recipe book.
     */
    IRON(stack -> stack.is(Items.IRON_INGOT), 14.0, null, Where.SURFACE),

    /** Out of reach until there is a diamond pickaxe, and nameable anyway: the portal is made of it. */
    OBSIDIAN(stack -> stack.is(Items.OBSIDIAN), 20.0, state -> state.is(Blocks.OBSIDIAN), Where.UNDERGROUND),

    /**
     * Anything edible, which the game itself decides: a stack is food when it carries the food component.
     * No block form — it comes off animals, not out of the ground — so the eyes never look for it and the
     * way to get it is to go and hit something.
     */
    FOOD(stack -> stack.get(DataComponents.FOOD) != null, 5.0, null, Where.SURFACE);


    /**
     * Two words instead of two bare booleans at the end of every line above.
     *
     * <p>They live in a type of their own because an enum constant cannot see a static field of its own
     * enum — the constants are built first, and the field does not exist yet.
     */
    private interface Where {
        boolean UNDERGROUND = true;
        boolean SURFACE = false;
    }

    /** Enough that hunting another animal is not worth the time. Two full meals in hand. */
    public static final int ENOUGH_FOOD = 8;

    /**
     * The things that are for putting down: cheap, everywhere, and worth nothing but the height or the
     * wall they make. Everything else that happens to be a block — a log, a plank, a crafting table — is
     * not building material, however placeable the game says it is. The first run put four logs into the
     * ground one after another because a log is a block and placing had just stopped costing anything.
     */
    private static final java.util.Set<Resource> BUILDING = java.util.EnumSet.of(DIRT, SAND, GRAVEL, COBBLESTONE);

    /** Whether this is something the body may put down to stand on or hide behind. */
    public boolean buildable() {
        return BUILDING.contains(this);
    }

    /**
     * The kind of place this is found in when nobody has said: wood where the trees are, food where the
     * animals are, nothing in particular for what is dug up. What the fixed ladder's rungs go looking in,
     * and what a planner that named the block and not the place gets filled in — a run with no planner
     * walked a hundred blocks into a desert after logs before this existed.
     */
    public java.util.List<Terrain> terrain() {
        return switch (this) {
            case LOG -> java.util.List.of(Terrain.WOODED);
            case FOOD -> java.util.List.of(Terrain.PLAINS, Terrain.WOODED);
            default -> java.util.List.of();
        };
    }

    /**
     * Whether a stack is fit to build with: a block the run has no better use for. Unknown blocks — stone,
     * andesite, netherrack, whatever the shovel turned up — count; the resources the plan is made of do
     * not, unless they are the ones that exist to be built with.
     */
    public static boolean buildsWith(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem item)) {
            return false;
        }
        // A block that only goes on certain ground — bamboo, a sapling, a flower — is not something to
        // stand on or hide behind. What builds is a full cube that can go anywhere.
        if (!item.getBlock().defaultBlockState().isSolid()) {
            return false;
        }
        // The crafting table is the one valuable that is meant to be put down: standing, it is what the
        // three-wide recipes are made at, and the run finds it again wherever it was left.
        return of(stack).map(resource -> resource.buildable() || resource == CRAFTING_TABLE).orElse(true);
    }

    /**
     * What each craftable is made of, one step back, and how many of each it takes.
     *
     * <p>Written out rather than read off the recipe book, and for the same reason the rest of the
     * vocabulary is: this is the shape of the plan, not the shape of Minecraft. It is the same five lines
     * the planner's brief already states in prose, and having them here as well is what lets the run tell
     * a craft that is on the way to the objective from one that is a detour away from it.
     *
     * <p>The counts are what {@link Reserve} needs. "Has it got a spare one of each ingredient" is the
     * right answer for planks and the wrong one for a pickaxe, which eats three planks and two sticks —
     * a bag with two spare planks in it can pay for neither, and a reserve that could not say so would be
     * broken by exactly the craft it was put there to prevent.
     */
    private static final Map<Resource, Map<Resource, Integer>> MADE_FROM = Map.of(
            PLANKS, Map.of(LOG, 1),
            STICK, Map.of(PLANKS, 2),
            CRAFTING_TABLE, Map.of(PLANKS, 4),
            SWORD, Map.of(PLANKS, 1, STICK, 1),
            PICKAXE, Map.of(PLANKS, 3, STICK, 2),
            STONE_PICKAXE, Map.of(COBBLESTONE, 3, STICK, 2),
            FURNACE, Map.of(COBBLESTONE, 8),
            // Smelted rather than crafted, but the chain is the same shape: one raw iron makes one ingot,
            // so contributesTo and the reserve can reason about it like any other step.
            IRON, Map.of(RAW_IRON, 1));

    /**
     * Whether having this is a step towards having {@code target} — the thing itself, or something it is
     * made of, however many crafts down the chain.
     *
     * <p>A log contributes to a pickaxe: log to planks to sticks to pickaxe. A sword does not contribute
     * to anything, which is exactly what makes making one a detour when the plan wanted a pickaxe.
     */
    public boolean contributesTo(Resource target) {
        if (this == target) {
            return true;
        }
        for (Resource ingredient : target.ingredients().keySet()) {
            if (contributesTo(ingredient)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What making one of these costs, one step back: each ingredient and how many of it.
     *
     * <p>Empty for everything that is found rather than made, which is the honest answer — a log costs a
     * walk and an axe swing, and neither of those is something the bag can be short of.
     */
    public Map<Resource, Integer> ingredients() {
        return MADE_FROM.getOrDefault(this, Map.of());
    }

    /** The resource a stack counts as, or empty when it is not one the run has a name for. */
    public static Optional<Resource> of(ItemStack stack) {
        for (Resource resource : values()) {
            if (resource.matches(stack)) {
                return Optional.of(resource);
            }
        }
        return Optional.empty();
    }

    private final Predicate<ItemStack> test;
    private final double worth;
    private final Predicate<BlockState> inWorld;
    private final boolean underground;

    Resource(Predicate<ItemStack> test, double worth, Predicate<BlockState> inWorld,
             boolean underground) {
        this.test = test;
        this.worth = worth;
        this.inWorld = inWorld;
        this.underground = underground;
    }

    /**
     * Whether this is found below the ground rather than on it.
     *
     * <p>The one fact that says whether digging towards a thing is a route to it or a way of burying
     * yourself. There is no wood under the grass, so a body after logs that starts a shaft is not taking
     * a slower path to them, it is leaving.
     */
    public boolean underground() {
        return underground;
    }

    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && test.test(stack);
    }

    /** What one of these is worth picking up, paid per unit so a climb is rewarded on the way up. */
    public double worth() {
        return worth;
    }

    /**
     * How this looks as a block in the landscape, if it is found rather than made. Empty for anything that
     * only ever comes off a crafting grid, which is the eyes being told there is nothing out there worth
     * looking for.
     */
    public Optional<Predicate<BlockState>> inWorld() {
        return Optional.ofNullable(inWorld);
    }

    /**
     * The resource a block yields, or empty when no resource is made of it.
     *
     * <p>The inverse of {@link #inWorld()}, and it exists because the planner keeps reaching for it. Asked
     * for a target it sometimes answers with a block id — {@code minecraft:oak_log} where {@code LOG} was
     * wanted — and that is a reasonable thing to say about a resource that is dug out of the ground. The
     * run already knows oak logs are what {@code LOG} looks like out there, so it can take the answer
     * rather than throw it away over a vocabulary the model got half right.
     *
     * <p>Read off the same predicate the eyes use, so the two can never disagree about what a block is.
     */
    public static Optional<Resource> yieldedBy(BlockState state) {
        for (Resource resource : values()) {
            if (resource.inWorld != null && resource.inWorld.test(state)) {
                return Optional.of(resource);
            }
        }
        return Optional.empty();
    }

    /** How many of this the inventory holds, counting stack sizes rather than slots. */
    public int countIn(Inventory inventory) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matches(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
