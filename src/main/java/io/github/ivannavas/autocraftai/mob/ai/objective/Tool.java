package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Optional;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block wants swung at it.
 *
 * <p>Punching stone gives nothing at all and punching a log takes six seconds, so which thing is in the
 * hand is not a detail — it is the difference between an objective that completes and one that does not.
 * The body used to swing with whatever it happened to be holding, which for most of a run is nothing.
 *
 * <p>{@link #bestFor(BlockState)} is Minecraft's own answer, read off the mineable tags, and it is right
 * about every block in the game. The planner can name one anyway, on a {@link Source}, because saying
 * "oak_log, with an axe" is how it says what a resource is <em>for</em> — and a planner that has to name
 * the tool is a planner that notices the run has not got one yet.
 */
public enum Tool {

    /** Fists. Fine for wood and dirt, useless on stone. */
    HAND(null),
    PICKAXE(ItemTags.PICKAXES),
    AXE(ItemTags.AXES),
    SHOVEL(ItemTags.SHOVELS),
    SWORD(ItemTags.SWORDS),
    HOE(ItemTags.HOES);

    private final TagKey<Item> tag;

    Tool(TagKey<Item> tag) {
        this.tag = tag;
    }

    /** The tag of items that count as this tool, or empty for bare hands. */
    public Optional<TagKey<Item>> tag() {
        return Optional.ofNullable(tag);
    }

    public boolean matches(ItemStack stack) {
        return tag != null && !stack.isEmpty() && stack.is(tag);
    }

    /** The hotbar slot holding one of these, or -1. Only the hotbar: nothing else can be swung with. */
    public int hotbarSlot(Inventory inventory) {
        if (tag == null) {
            return -1;
        }
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            if (matches(inventory.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * The hotbar slot best suited to this block: one of these that the block will actually drop for, or
     * failing that any of these, or -1.
     *
     * <p>Tier is the whole difference. A wooden pickaxe is a pickaxe and iron ore does not care: it gives
     * nothing to anything below stone. Asking for "a pickaxe" put the wooden one in the hand in front of
     * iron and booked the swings as wasted, when the stone one was two slots along.
     */
    public int hotbarSlotFor(Inventory inventory, BlockState state) {
        int any = -1;
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!matches(stack)) {
                continue;
            }
            if (stack.isCorrectToolForDrops(state)) {
                return slot;
            }
            if (any < 0) {
                any = slot;
            }
        }
        return any;
    }

    /**
     * Whether something in the hotbar will get this block to drop — or nothing is needed, as with wood
     * and dirt. The question every legality check about breaking a block should be asking, rather than
     * whether what happens to be in the hand right now would do.
     */
    public static boolean canHarvest(Inventory inventory, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            if (inventory.getItem(slot).isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    /** What the game itself says should break this block. {@link #HAND} when nothing in particular. */
    public static Tool bestFor(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return PICKAXE;
        }
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return AXE;
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return SHOVEL;
        }
        return state.is(BlockTags.MINEABLE_WITH_HOE) ? HOE : HAND;
    }
}
