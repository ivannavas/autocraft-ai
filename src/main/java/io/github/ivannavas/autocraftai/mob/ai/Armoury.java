package io.github.ivannavas.autocraftai.mob.ai;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;

/**
 * Puts on armour the body is already carrying.
 *
 * <h2>Why this is not a move to be learned</h2>
 * The same reason holding a pickaxe to break stone is not one. Wearing a helmet you own has no downside
 * and no alternative worth weighing: there is nothing to choose between, so there is nothing for a table
 * to learn. What is worth learning is whether to go and get armour at all, and that is the planner's
 * objective and the craft table's column. This is the hands doing the obvious thing, like
 * {@link io.github.ivannavas.autocraftai.mob.goal.Digging#equip} with a tool.
 *
 * <p>It came from watching a run carry the copper for a helmet through three deaths by skeleton.
 *
 * <h2>Only into an empty slot</h2>
 * A slot that already holds something is left alone. Swapping a worn piece for a better one means
 * comparing two pieces, and a body that got that wrong would take its armour off in front of a creeper;
 * the gain from "leather boots or nothing" is most of the gain there is, and it is unambiguous.
 */
@Slf4j
public final class Armoury {

    /**
     * Head to foot: the order pieces go on when several are waiting, each with the tag of what belongs
     * there. The tags rather than the item's own "you may wear this": a carved pumpkin says yes to the
     * head, and a blinded body is worse off than a bare one.
     */
    private static final List<SlotFor> WORN = List.of(
            new SlotFor(EquipmentSlot.HEAD, ItemTags.HEAD_ARMOR),
            new SlotFor(EquipmentSlot.CHEST, ItemTags.CHEST_ARMOR),
            new SlotFor(EquipmentSlot.LEGS, ItemTags.LEG_ARMOR),
            new SlotFor(EquipmentSlot.FEET, ItemTags.FOOT_ARMOR));

    private record SlotFor(EquipmentSlot slot, TagKey<Item> wears) {
    }

    private Armoury() {
    }

    /** How many pieces the body has on. What a skill's {@code armour} reading reads. */
    public static int worn(LocalPlayer player) {
        int on = 0;
        for (SlotFor worn : WORN) {
            if (player != null && !player.getItemBySlot(worn.slot()).isEmpty()) {
                on++;
            }
        }
        return on;
    }

    /**
     * Puts on one waiting piece, the topmost first. One a step rather than all four at once: each is a
     * click with the piece in hand, and the hand is wanted back.
     *
     * @return true when something was put on
     */
    public static boolean wear(LocalPlayer player) {
        if (player == null || player.containerMenu != player.inventoryMenu) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode == null) {
            return false;
        }
        for (SlotFor worn : WORN) {
            if (!player.getItemBySlot(worn.slot()).isEmpty()) {
                continue;
            }
            int found = slotHolding(player, worn.wears());
            if (found < 0) {
                continue;
            }
            Inventory inventory = player.getInventory();
            int chosen = inventory.getSelectedSlot();
            String what = inventory.getItem(found).getHoverName().getString();
            if (found >= Inventory.SELECTION_SIZE) {
                // Further in than the bar: the same swap a player makes with a number key over the slot.
                client.gameMode.handleContainerInput(player.inventoryMenu.containerId, found, chosen,
                        ContainerInput.SWAP, player);
            } else {
                inventory.setSelectedSlot(found);
            }
            // Using a piece of armour is what puts it on; the slot it came from is left holding whatever
            // the swap displaced, and the hand goes back to what it was on.
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            inventory.setSelectedSlot(chosen);
            log.info("Put on {}", what);
            return true;
        }
        return false;
    }

    /** An inventory slot holding a piece for this part of the body, or -1. */
    private static int slotHolding(LocalPlayer player, TagKey<Item> wears) {
        Inventory inventory = player.getInventory();
        for (int at = 0; at < Inventory.INVENTORY_SIZE; at++) {
            if (inventory.getItem(at).is(wears)) {
                return at;
            }
        }
        return -1;
    }
}
