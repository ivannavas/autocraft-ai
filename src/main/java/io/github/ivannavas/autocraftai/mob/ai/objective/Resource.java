package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.function.Predicate;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The things the ladder counts.
 *
 * <p>Every rung of the progression is "have N of one of these", and every phase reward is "you gained one
 * of these since the last decision". Keeping the list short and explicit is what lets the whole inventory be
 * summarised once a decision instead of being re-scanned by each objective in turn.
 *
 * <p>Tags rather than items wherever a tag exists: a birch log is wood, and the brain has no business
 * learning oak and birch separately.
 */
public enum Resource {

    LOG(stack -> stack.is(ItemTags.LOGS)),
    PLANKS(stack -> stack.is(ItemTags.PLANKS)),
    STICK(stack -> stack.is(Items.STICK)),
    CRAFTING_TABLE(stack -> stack.is(Items.CRAFTING_TABLE)),
    PICKAXE(stack -> stack.is(ItemTags.PICKAXES)),
    SWORD(stack -> stack.is(ItemTags.SWORDS)),
    COBBLESTONE(stack -> stack.is(Items.COBBLESTONE));

    private final Predicate<ItemStack> test;

    Resource(Predicate<ItemStack> test) {
        this.test = test;
    }

    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && test.test(stack);
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
