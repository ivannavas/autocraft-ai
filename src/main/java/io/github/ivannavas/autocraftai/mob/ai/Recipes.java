package io.github.ivannavas.autocraftai.mob.ai;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * What the body could make right now, asked of the recipe book rather than worked out from a table of our
 * own.
 *
 * <p>Both callers need the same answer for different reasons — the brain to know which craft choices are
 * legal, the goal to know which recipe to send — so the scan lives here and is done once per decision
 * instead of once per choice.
 */
public final class Recipes {

    private Recipes() {
    }

    /** Everything on the craft menu that the inventory can currently pay for. */
    public static Set<Resource> craftableNow(LocalPlayer player) {
        Set<Resource> craftable = EnumSet.noneOf(Resource.class);
        if (player == null) {
            return craftable;
        }
        for (CraftChoice choice : CraftChoice.values()) {
            if (choice.makesSomething() && find(player, choice.resource()).isPresent()) {
                craftable.add(choice.resource());
            }
        }
        return craftable;
    }

    /**
     * The first recipe the body could make right now whose result is the given resource.
     *
     * <p>Availability is the recipe book's own answer, so an ingredient the body has not got rules the
     * recipe out before anything is sent to the server. A recipe the grid is too small for is not ruled
     * out — the book does not know which grid is open — so a three-wide recipe will be attempted and fail
     * until something opens a crafting table.
     */
    public static Optional<RecipeDisplayId> find(LocalPlayer player, Resource resource) {
        StackedItemContents carried = new StackedItemContents();
        player.getInventory().fillStackedContents(carried);
        ContextMap context = SlotDisplayContext.fromLevel(player.level());

        return player.getRecipeBook().getCollections().stream()
                .map(RecipeCollection::getRecipes)
                .flatMap(java.util.List::stream)
                .filter(entry -> makes(entry, resource, context))
                .filter(entry -> entry.canCraft(carried))
                .map(RecipeDisplayEntry::id)
                .findFirst();
    }

    private static boolean makes(RecipeDisplayEntry entry, Resource resource, ContextMap context) {
        for (ItemStack result : entry.resultItems(context)) {
            if (resource.matches(result)) {
                return true;
            }
        }
        return false;
    }
}
