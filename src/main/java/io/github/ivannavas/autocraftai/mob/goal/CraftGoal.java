package io.github.ivannavas.autocraftai.mob.goal;

import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.CraftLog;
import io.github.ivannavas.autocraftai.mob.ai.Recipes;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

/**
 * Makes one batch of something in the body's own two-by-two grid, with the inventory screen open.
 *
 * <p>The screen is not needed to craft — {@code player.inventoryMenu} is the open container whenever no
 * other one is — but a player who crafts does open their inventory, and a run that is being watched should
 * look like one. So the goal opens it, works, and closes it again.
 *
 * <h2>It claims no controls, and that is the point</h2>
 * A two-by-two craft needs neither legs nor eyes, so the engine never sees this goal as competing with
 * anything and it runs <em>alongside</em> whatever the body is doing — fleeing a creeper and turning logs
 * into planks at once. Anything three wide needs a real table, which needs walking to and standing at, and
 * that is {@link CraftAtTableGoal}'s job.
 *
 * <h2>One batch, then done</h2>
 * Crafting until the commitment ran out was how thirty-two sticks got made when four were wanted. Stopping
 * after one result hands the decision back and gives the table a clean signal per craft.
 */
public final class CraftGoal implements CraftingGoal {

    private static final Set<MobControl> CONTROLS = Set.of();

    private static final int ATTEMPT_INTERVAL_TICKS = 10;
    private static final int GIVE_UP_TICKS = 120;

    private final Resource target;

    private int ticksRunning;
    private boolean crafted;
    private boolean openedScreen;

    public CraftGoal(Resource target) {
        this.target = target;
    }

    @Override
    public Resource target() {
        return target;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return !crafted && Recipes.find(body.player(), target).isPresent();
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !crafted && ticksRunning < GIVE_UP_TICKS;
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        crafted = false;
        openedScreen = false;
    }

    /** Whether the batch is done, which is what tells the brain this secondary has finished. */
    @Override
    public boolean isFinished() {
        return crafted;
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        // Placing a recipe is a round trip to the server; hammering it every tick would only queue work
        // against a grid whose contents the client has not heard about yet.
        if (ticksRunning % ATTEMPT_INTERVAL_TICKS != 0) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() == null) {
            client.setScreenAndShow(new InventoryScreen(body.player()));
            openedScreen = true;
            return;
        }

        AbstractCraftingMenu menu = craftingMenu(body.player());
        if (menu == null) {
            return;
        }

        Slot result = menu.getResultSlot();
        if (target.matches(result.getItem())) {
            // Nothing has actually been crafted until the result is taken, which is why the craft is
            // recorded here and not when the recipe was placed.
            CraftLog.get().record(result.getItem().copy());
            gameMode().ifPresent(mode -> mode.handleContainerInput(
                    menu.containerId, result.index, 0, ContainerInput.QUICK_MOVE, body.player()));
            crafted = true;
            closeScreen(client);
            return;
        }

        Recipes.find(body.player(), target).ifPresent(recipe -> gameMode().ifPresent(mode ->
                mode.handlePlaceRecipe(menu.containerId, recipe, false)));
    }

    @Override
    public void stop(MobBody body) {
        closeScreen(Minecraft.getInstance());
    }

    /** Only ever closes a screen this goal opened: the pause menu is not ours to dismiss. */
    private void closeScreen(Minecraft client) {
        if (openedScreen && client.gui.screen() instanceof InventoryScreen) {
            client.setScreenAndShow(null);
        }
        openedScreen = false;
    }

    private AbstractCraftingMenu craftingMenu(LocalPlayer player) {
        return player != null && player.containerMenu instanceof AbstractCraftingMenu menu ? menu : null;
    }

    private Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        return "Craft(" + target + ")";
    }
}
