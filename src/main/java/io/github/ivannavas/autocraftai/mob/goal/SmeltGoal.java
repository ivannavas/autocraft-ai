package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.ai.CraftLog;
import io.github.ivannavas.autocraftai.mob.ai.Placed;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Smelts one thing at a furnace: finds one or puts one down, walks to it, opens it, loads the ore and some
 * fuel, waits for the bar to fill, and takes the ingot.
 *
 * <p>This is the tier past stone — the one that turns the raw iron a stone pickaxe digs up into the iron
 * that tools are actually made of. Structurally it is {@link CraftAtTableGoal}'s cousin: it claims the body
 * because a furnace has to be walked to and stood at, it places its own if the hotbar has one and finds an
 * old one otherwise (its own, remembered by {@link Placed}, even out of sight), and it works the container
 * through the same {@code MultiPlayerGameMode} calls a player's clicks would drive.
 *
 * <h2>Loading is three shift-clicks and a wait</h2>
 * The furnace has three slots — the ore on top, the fuel below, the result to the side — and vanilla's own
 * quick-move routes an item to the right one: shift a raw iron in and it lands on top, shift a coal in and
 * it lands below. So loading is: quick-move the ore into an empty input, quick-move a fuel into an empty
 * fuel slot, and then wait, because smelting takes ten seconds a piece and nothing about that is the
 * body's to hurry. The ingot is taken the moment it appears.
 */
public final class SmeltGoal implements CraftingGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** Furnace slots, in the order vanilla lays them out: ore, fuel, result. */
    private static final int INPUT = 0;
    private static final int FUEL = 1;
    private static final int RESULT = 2;

    private static final int ATTEMPT_INTERVAL_TICKS = 10;
    /** Long: a smelt is ten seconds a piece, and walking to the furnace is on top of that. */
    private static final int GIVE_UP_TICKS = 600;
    private static final int FURNACE_SCAN_RANGE = 8;
    private static final double REACH_MARGIN = 1.6;
    private static final float SPEED = 1.0F;

    private final Resource target;
    private final Resource input;

    private int ticksRunning;
    private boolean smelted;
    private boolean gaveUp;

    /**
     * @param target what to end up holding — the ingot
     * @param input  what goes in the top slot to become it — the raw ore
     */
    public SmeltGoal(Resource target, Resource input) {
        this.target = target;
        this.input = input;
    }

    @Override
    public Resource target() {
        return target;
    }

    @Override
    public boolean isFinished() {
        return smelted;
    }

    @Override
    public boolean gaveUp() {
        return gaveUp;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        if (smelted || gaveUp) {
            return false;
        }
        LocalPlayer player = body.player();
        // Something to smelt and something to burn, and a furnace to do it at or the means to set one up.
        return has(player, input) && hasFuel(player)
                && (furnace(body) != null || hotbarSlotWithFurnace(player) >= 0);
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !smelted && ticksRunning < GIVE_UP_TICKS;
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        smelted = false;
        gaveUp = false;
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;

        AbstractFurnaceMenu open = openFurnace(body.player());
        if (open != null) {
            body.moveControl().stop();
            if (ticksRunning % ATTEMPT_INTERVAL_TICKS == 0) {
                work(body, open);
            }
            return;
        }

        BlockPos furnace = furnace(body);
        if (furnace == null) {
            placeFurnace(body);
            return;
        }
        Vec3 centre = Vec3.atCenterOf(furnace);
        body.lookControl().lookAt(centre);
        if (!withinReach(body, centre)) {
            body.moveControl().moveTo(centre, SPEED);
            return;
        }
        body.moveControl().stop();
        if (ticksRunning % ATTEMPT_INTERVAL_TICKS == 0) {
            use(body, furnace, Direction.UP, centre);
        }
    }

    @Override
    public void stop(MobBody body) {
        gaveUp = !smelted;
        body.moveControl().stop();
        closeFurnace(body.player());
    }

    /**
     * One pass over the open furnace: take the ingot if it is ready, otherwise load whatever slot is
     * empty and has something in the bag to fill it.
     */
    private void work(MobBody body, AbstractFurnaceMenu menu) {
        Slot result = menu.slots.get(RESULT);
        if (target.matches(result.getItem())) {
            CraftLog.get().record(result.getItem().copy());
            quickMove(body, menu, RESULT);
            smelted = true;
            closeFurnace(body.player());
            return;
        }
        if (menu.slots.get(INPUT).getItem().isEmpty()) {
            int slot = menuSlotOf(menu, input);
            if (slot >= 0) {
                quickMove(body, menu, slot);
                return;
            }
        }
        if (menu.slots.get(FUEL).getItem().isEmpty()) {
            int slot = menuSlotOfFuel(menu);
            if (slot >= 0) {
                quickMove(body, menu, slot);
            }
        }
        // Otherwise both slots are loaded and the bar is filling: nothing to do but wait.
    }

    private void quickMove(MobBody body, AbstractFurnaceMenu menu, int slot) {
        gameMode().ifPresent(mode -> mode.handleContainerInput(
                menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, body.player()));
    }

    /** The menu slot holding this resource somewhere in the player's inventory, or -1. */
    private static int menuSlotOf(AbstractFurnaceMenu menu, Resource resource) {
        for (Slot slot : menu.slots) {
            if (slot.index > RESULT && resource.matches(slot.getItem())) {
                return slot.index;
            }
        }
        return -1;
    }

    /** The menu slot holding a fuel somewhere in the inventory, or -1. */
    private static int menuSlotOfFuel(AbstractFurnaceMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.index > RESULT && isFuel(slot.getItem())) {
                return slot.index;
            }
        }
        return -1;
    }

    private void placeFurnace(MobBody body) {
        int slot = hotbarSlotWithFurnace(body.player());
        if (slot < 0 || ticksRunning % ATTEMPT_INTERVAL_TICKS != 0) {
            return;
        }
        BlockPos ground = spotForFurnace(body);
        if (ground == null) {
            return;
        }
        body.moveControl().stop();
        body.lookControl().lookAt(Vec3.atCenterOf(ground.above()));
        body.player().getInventory().setSelectedSlot(slot);
        Placed.get().mark(ground.above(), body.player().getMainHandItem());
        use(body, ground, Direction.UP, Vec3.atCenterOf(ground).add(0.0, 0.5, 0.0));
    }

    private void use(MobBody body, BlockPos pos, Direction face, Vec3 hit) {
        gameMode().ifPresent(mode -> mode.useItemOn(body.player(), InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, pos, false)));
    }

    /** A block beside the body with solid ground and room above, within arm's reach. */
    private BlockPos spotForFurnace(MobBody body) {
        Level level = body.level();
        BlockPos feet = body.player().blockPosition();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos ground = feet.relative(side).below();
            BlockPos above = ground.above();
            if (level.isLoaded(ground)
                    && level.getBlockState(ground).isSolid()
                    && level.getBlockState(above).canBeReplaced()
                    && level.getBlockState(above.above()).canBeReplaced()) {
                return ground;
            }
        }
        return null;
    }

    /** A furnace within walking distance, or one the body set up earlier and walked away from. */
    private BlockPos furnace(MobBody body) {
        Level level = body.level();
        BlockPos origin = body.player().blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-FURNACE_SCAN_RANGE, -3, -FURNACE_SCAN_RANGE),
                origin.offset(FURNACE_SCAN_RANGE, 3, FURNACE_SCAN_RANGE))) {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.FURNACE)) {
                continue;
            }
            double distance = Vec3.atCenterOf(pos).distanceToSqr(body.player().position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best != null ? best
                : Placed.get().nearestOwn(level, body.player().position(), Blocks.FURNACE);
    }

    private static int hotbarSlotWithFurnace(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            if (inventory.getItem(slot).is(Items.FURNACE)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean has(LocalPlayer player, Resource resource) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (resource.matches(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFuel(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isFuel(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * What will burn in a furnace, kept to the handful the run actually has: coal and charcoal first, and
     * planks, logs or sticks when there is no coal. Never the raw iron itself, and never a plank the plan
     * is holding back — the caller only offers this what it is allowed to spend.
     */
    private static boolean isFuel(ItemStack stack) {
        return stack.is(Items.COAL) || stack.is(Items.CHARCOAL)
                || stack.is(ItemTags.PLANKS) || stack.is(ItemTags.LOGS) || stack.is(Items.STICK);
    }

    private boolean withinReach(MobBody body, Vec3 centre) {
        double reach = Math.max(2.5, body.player().blockInteractionRange() - REACH_MARGIN);
        return body.player().getEyePosition().distanceToSqr(centre) <= reach * reach;
    }

    private AbstractFurnaceMenu openFurnace(LocalPlayer player) {
        return player != null && player.containerMenu instanceof AbstractFurnaceMenu menu ? menu : null;
    }

    private void closeFurnace(LocalPlayer player) {
        if (openFurnace(player) != null) {
            player.closeContainer();
            Minecraft.getInstance().setScreenAndShow(null);
        }
    }

    private Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        return "Smelt(" + target + ")";
    }
}
