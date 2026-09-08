package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.CraftLog;
import io.github.ivannavas.autocraftai.mob.ai.Recipes;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Crafts at a real crafting table: finds one or puts one down, walks to it, opens it, makes one batch and
 * closes it again.
 *
 * <p>This is what a sword and a pickaxe need. Both are three squares tall, which the body's own two-by-two
 * grid cannot hold however many planks it is carrying, so until something opened a table those two rungs
 * were unreachable no matter what the brain chose.
 *
 * <p>Unlike {@link CraftGoal} this one claims the body. Walking to a block and standing at it is
 * locomotion, so it cannot be a background activity the way a two-by-two craft can — the engine's control
 * claims are what say so, and they are telling the truth.
 *
 * <p>The table is placed from the hotbar. A crafted table is quick-moved into the inventory and lands in
 * the hotbar while that is still empty, which it is at this point in a run; if it ends up further in, this
 * goal gives up rather than shuffling the inventory around, and the brain is free to learn from that.
 */
public final class CraftAtTableGoal implements CraftingGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    private static final int ATTEMPT_INTERVAL_TICKS = 10;
    private static final int GIVE_UP_TICKS = 400;
    /** How far out an existing table is worth walking to. */
    private static final int TABLE_SCAN_RANGE = 8;
    /** Kept under the server's reach so an interaction is never sent from too far to land. */
    private static final double REACH_MARGIN = 0.5;
    private static final float SPEED = 1.0F;

    private final Resource target;

    private int ticksRunning;
    private boolean crafted;
    private final Advance advance = new Advance();

    public CraftAtTableGoal(Resource target) {
        this.target = target;
    }

    @Override
    public Resource target() {
        return target;
    }

    @Override
    public boolean isFinished() {
        return crafted;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        if (crafted || Recipes.find(body.player(), target).isEmpty()) {
            return false;
        }
        return findTable(body) != null || hotbarSlotWithTable(body.player()) >= 0;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !crafted && ticksRunning < GIVE_UP_TICKS;
    }

    /**
     * Counted because this one takes the body away from whatever the goal table chose, so its own progress
     * is the excuse for the other goal not making any. An open grid is work; walking to a table is work
     * while the ground goes by; clicking at a table that never opens is not.
     */
    @Override
    public int stalledTicks() {
        return advance.stalledTicks();
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        crafted = false;
        advance.reset();
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;

        // A three-by-three grid open means the table is already in use: get on with the recipe.
        AbstractCraftingMenu open = openTableMenu(body.player());
        if (open != null) {
            body.moveControl().stop();
            advance.progress();
            if (ticksRunning % ATTEMPT_INTERVAL_TICKS == 0) {
                craft(body, open);
            }
            return;
        }

        BlockPos table = findTable(body);
        if (table == null) {
            // Nothing to walk to. Either a table goes down here — which the placing itself reports — or
            // this goal is standing about holding the body it took off something else.
            advance.nothing();
            placeTable(body);
            return;
        }

        Vec3 centre = Vec3.atCenterOf(table);
        body.lookControl().lookAt(centre);
        if (!withinReach(body, centre)) {
            body.moveControl().moveTo(centre, SPEED);
            advance.walking(body);
            return;
        }
        body.moveControl().stop();
        // Standing at the table with the screen shut: the click either opens it within a tick or two or
        // it is not going to, and the second of those is a decision going nowhere.
        advance.nothing();
        if (ticksRunning % ATTEMPT_INTERVAL_TICKS == 0) {
            // The same right-click a player makes. The server answers by opening the screen for us.
            use(body, table, Direction.UP, centre);
        }
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
        closeTable(body.player());
    }

    private void craft(MobBody body, AbstractCraftingMenu menu) {
        Slot result = menu.getResultSlot();
        if (target.matches(result.getItem())) {
            CraftLog.get().record(result.getItem().copy());
            gameMode().ifPresent(mode -> mode.handleContainerInput(
                    menu.containerId, result.index, 0, ContainerInput.QUICK_MOVE, body.player()));
            crafted = true;
            closeTable(body.player());
            return;
        }
        Recipes.find(body.player(), target).ifPresent(recipe -> gameMode().ifPresent(mode ->
                mode.handlePlaceRecipe(menu.containerId, recipe, false)));
    }

    /** Puts a table down in front of the body, on the first bit of ground with room above it. */
    private void placeTable(MobBody body) {
        int slot = hotbarSlotWithTable(body.player());
        if (slot < 0 || ticksRunning % ATTEMPT_INTERVAL_TICKS != 0) {
            return;
        }
        BlockPos ground = spotForTable(body);
        if (ground == null) {
            return;
        }
        body.moveControl().stop();
        body.lookControl().lookAt(Vec3.atCenterOf(ground.above()));
        body.player().getInventory().setSelectedSlot(slot);
        // Clicking the top face of the ground block is what puts the table on top of it.
        use(body, ground, Direction.UP, Vec3.atCenterOf(ground).add(0.0, 0.5, 0.0));
        advance.progress();
    }

    private void use(MobBody body, BlockPos pos, Direction face, Vec3 hit) {
        gameMode().ifPresent(mode -> mode.useItemOn(body.player(), InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, pos, false)));
    }

    /** A block next to the body with solid ground and air above, within arm's reach. */
    private BlockPos spotForTable(MobBody body) {
        Level level = body.level();
        BlockPos feet = body.player().blockPosition();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos ground = feet.relative(side).below();
            BlockPos above = ground.above();
            if (level.isLoaded(ground)
                    && level.getBlockState(ground).isSolid()
                    && level.getBlockState(above).isAir()
                    && level.getBlockState(above.above()).isAir()) {
                return ground;
            }
        }
        return null;
    }

    /** Whether a table is within walking distance or in the hotbar ready to be put down. */
    public static boolean tableAvailable(LocalPlayer player) {
        return player != null
                && (findTable(player) != null || hotbarSlotWithTable(player) >= 0);
    }

    private BlockPos findTable(MobBody body) {
        return findTable(body.player());
    }

    private static BlockPos findTable(LocalPlayer player) {
        Level level = player.level();
        BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-TABLE_SCAN_RANGE, -3, -TABLE_SCAN_RANGE),
                origin.offset(TABLE_SCAN_RANGE, 3, TABLE_SCAN_RANGE))) {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                continue;
            }
            double distance = Vec3.atCenterOf(pos).distanceToSqr(player.position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    private static int hotbarSlotWithTable(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.CRAFTING_TABLE)) {
                return slot;
            }
        }
        return -1;
    }

    /** The open container, but only when it is a grid big enough for the recipes this goal exists for. */
    private AbstractCraftingMenu openTableMenu(LocalPlayer player) {
        if (player != null && player.containerMenu instanceof AbstractCraftingMenu menu
                && menu.getGridWidth() >= 3) {
            return menu;
        }
        return null;
    }

    private void closeTable(LocalPlayer player) {
        if (openTableMenu(player) != null) {
            player.closeContainer();
            Minecraft.getInstance().setScreenAndShow(null);
        }
    }

    private boolean withinReach(MobBody body, Vec3 centre) {
        double reach = body.player().blockInteractionRange() - REACH_MARGIN;
        return body.player().getEyePosition().distanceToSqr(centre) <= reach * reach;
    }

    private Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        return "CraftAtTable(" + target + ")";
    }
}
