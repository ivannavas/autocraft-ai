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
