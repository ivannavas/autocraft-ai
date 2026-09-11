package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.CraftLog;
import io.github.ivannavas.autocraftai.mob.ai.Placed;
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
    /**
     * How long the walk to the table may make no progress before the goal lets go of the body. Short: a
     * table craft outranks every move the goal table can choose, so a walk that is not getting there is
     * the whole run standing still. Twenty seconds of that, restarted at once, held a body in a shaft for
     * seven minutes while it tried to reach a table on the surface for a sword nobody had asked for.
     */
    private static final int STALLED_GIVE_UP_TICKS = 80;
    /** How far out an existing table is worth walking to. */
    private static final int TABLE_SCAN_RANGE = 8;
    /**
     * How far off the body's own table may stand and still count as its table. It set the workbench up,
     * went for stone, and came back to a stone pickaxe it could not make: the plan's list said "a table",
     * the gate said "none in sight", and five objectives in a row were given up thirty blocks from a
     * table that was still standing. A walk of this length is cheaper than a log, four planks and a
     * second table — and than the ten minutes of punching stone that came instead.
     */
    private static final int OWN_TABLE_RANGE = 48;
    /** Kept under the server's reach so an interaction is never sent from too far to land. */
    private static final double REACH_MARGIN = 0.5;
    private static final float SPEED = 1.0F;

    private final Resource target;

    private int ticksRunning;
    private boolean crafted;
    private boolean gaveUp;
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
        // Once it has let go it stays let go: the engine would otherwise start it again the very next
        // tick, before the brain has seen that it gave up, and the four-second give-up becomes a
        // four-second loop that holds the body just the same.
        if (gaveUp || crafted || Recipes.find(body.player(), target).isEmpty()) {
            return false;
        }
        return findTable(body) != null || hotbarSlotWithTable(body.player()) >= 0;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !crafted && ticksRunning < GIVE_UP_TICKS && advance.stalledTicks() < STALLED_GIVE_UP_TICKS;
    }

    @Override
    public boolean gaveUp() {
        return gaveUp;
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

    /** The item is in the bag; what is left is picking the table back up when it was the body's own. */
    private boolean made;
    private BlockPos reclaiming;
    private int reclaimTicks;
    /** The most ticks spent getting the table back: by hand a table takes about four seconds. */
    private static final int RECLAIM_TICKS = 140;

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        crafted = false;
        made = false;
        reclaiming = null;
        reclaimTicks = 0;
        gaveUp = false;
        advance.reset();
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        if (made) {
            reclaim(body);
            return;
        }

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
        // Given up only when this goal itself ran out of time or of progress. Being stopped because
        // something with a higher claim took the legs — a swim, a wall — is not giving up, and calling
        // it that put the craft on a minute's backoff every time the body got its feet wet on the way
        // to the table: the stone pickaxe was "given up" twice inside four seconds, and never made.
        gaveUp = !crafted && !made && (ticksRunning >= GIVE_UP_TICKS
                || advance.stalledTicks() >= STALLED_GIVE_UP_TICKS);
        body.moveControl().stop();
        Digging.stopBreaking();
        closeTable(body.player());
    }

    /**
     * Picks the table back up after using it. Left standing, the table dropped out of the bag the
     * moment the body walked away, the plan asked for one again, and four planks went into another:
     * seven table objectives in an hour on the box, each paid for in wood the pickaxe needed.
     */
    private void reclaim(MobBody body) {
        if (reclaiming == null || body.level().getBlockState(reclaiming).isAir()
                || ++reclaimTicks > RECLAIM_TICKS) {
            crafted = true;
            return;
        }
        Vec3 centre = Vec3.atCenterOf(reclaiming);
        if (!withinReach(body, centre)) {
            body.moveControl().moveTo(centre, SPEED);
            advance.walking(body);
            return;
        }
        body.moveControl().stop();
        body.lookControl().lookAt(centre);
        if (reclaimTicks == 1) {
            Digging.equip(body, reclaiming);
        }
        if (Digging.strike(body, reclaiming)) {
            advance.progress();
        } else {
            advance.nothing();
        }
    }

    private void craft(MobBody body, AbstractCraftingMenu menu) {
        Slot result = menu.getResultSlot();
        if (target.matches(result.getItem())) {
            CraftLog.get().record(result.getItem().copy());
            gameMode().ifPresent(mode -> mode.handleContainerInput(
                    menu.containerId, result.index, 0, ContainerInput.QUICK_MOVE, body.player()));
            made = true;
            closeTable(body.player());
            // The body's own table goes back in the bag; one found standing is left where it was.
            BlockPos table = findTable(body);
            if (table != null && Placed.get().isOurs(body.level(), table)) {
                reclaiming = table.immutable();
            } else {
                crafted = true;
            }
            return;
        }
        Recipes.find(body.player(), target).ifPresent(recipe -> gameMode().ifPresent(mode ->
                mode.handlePlaceRecipe(menu.containerId, recipe, false)));
    }

    /** Puts a table down in front of the body, on the first bit of ground with room above it. */
    private void placeTable(MobBody body) {
        if (ticksRunning % ATTEMPT_INTERVAL_TICKS != 0) {
            return;
        }
        int slot = hotbarSlotWithTable(body.player());
        if (slot < 0) {
            // In the bag but not on the bar: brought down first, placed on the next attempt.
            bringTableToHotbar(body.player());
            return;
        }
        BlockPos ground = spotForTable(body);
        if (ground == null) {
            return;
        }
        body.moveControl().stop();
        body.lookControl().lookAt(Vec3.atCenterOf(ground.above()));
        body.player().getInventory().setSelectedSlot(slot);
        // Clicking the top face of the ground block is what puts the table on top of it. The table is the
        // body's own once it stands there: it left the bag, and it was not lost.
        Placed.get().mark(ground.above(), body.player().getMainHandItem());
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
            // Room above means nothing that would stop the table going in, not only air: a snow layer,
            // grass or a flower gives way to a placed block, and a taiga is nothing but snow layers.
            if (level.isLoaded(ground)
                    && level.getBlockState(ground).isSolid()
                    && level.getBlockState(above).canBeReplaced()
                    && level.getBlockState(above.above()).canBeReplaced()) {
                return ground;
            }
        }
        return null;
    }

    /** Whether a table is within walking distance, or anywhere in the bag ready to be put down. */
    public static boolean tableAvailable(LocalPlayer player) {
        return player != null
                && (findTable(player) != null || hotbarSlotWithTable(player) >= 0
                        || bagSlotWithTable(player) >= 0);
    }

    /**
     * Whether a craft here costs no trek: a table in sight, or one in the bag to put down. What a
     * craft the plan did not ask for has to show before it is allowed to take the body — the remembered
     * table thirty blocks off is worth walking back to for a pickaxe the plan wants, not for a sword.
     */
    public static boolean tableInSight(LocalPlayer player) {
        return player != null && (nearbyTable(player) != null || hotbarSlotWithTable(player) >= 0
                || bagSlotWithTable(player) >= 0);
    }

    /**
     * Where the table is, in words, for the two agents: they were told "a table" was on the list and
     * never that the body had one forty blocks behind it, and planned a second one from logs it did
     * not have.
     */
    public static String tableWords(LocalPlayer player) {
        if (player == null) {
            return "";
        }
        if (hotbarSlotWithTable(player) >= 0 || bagSlotWithTable(player) >= 0) {
            return "carrying one, ready to put down";
        }
        BlockPos near = nearbyTable(player);
        if (near != null) {
            return "one stands within reach";
        }
        BlockPos own = Placed.get().nearestOwn(player.level(), player.position(), Blocks.CRAFTING_TABLE);
        if (own == null) {
            return "none: not carried, none in sight, none of its own still standing anywhere it knows";
        }
        int away = (int) Math.round(Math.sqrt(own.distToCenterSqr(player.position())));
        return away <= OWN_TABLE_RANGE
                ? "its own stands " + away + " blocks away at " + own.toShortString()
                        + "; a craft the plan asks for walks back to it"
                : "left its own " + away + " blocks behind at " + own.toShortString()
                        + ", too far to walk back for; it needs another or to go back that way";
    }

    /** A main-inventory slot holding a table, as the inventory menu numbers it, or -1. */
    private static int bagSlotWithTable(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = Inventory.SELECTION_SIZE; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.CRAFTING_TABLE)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Swaps a table from the bag into the selected hotbar slot, the click a player makes with a number
     * key over an inventory slot. A crafted table lands in the hotbar only while the hotbar has room,
     * which by the second table of a run it has not, and a table nine slots too far in was "no table".
     */
    private static boolean bringTableToHotbar(LocalPlayer player) {
        int from = bagSlotWithTable(player);
        Minecraft client = Minecraft.getInstance();
        if (from < 0 || client.gameMode == null) {
            return false;
        }
        int chosen = player.getInventory().getSelectedSlot();
        client.gameMode.handleContainerInput(player.inventoryMenu.containerId, from, chosen,
                ContainerInput.SWAP, player);
        return true;
    }

    private BlockPos findTable(MobBody body) {
        return findTable(body.player());
    }

    private static BlockPos findTable(LocalPlayer player) {
        BlockPos best = nearbyTable(player);
        if (best != null) {
            return best;
        }
        // None in sight, but the body may have set one up earlier and walked off mining. It knows where
        // its own went; walk back to the nearest that is still standing rather than stand here stuck —
        // within reason: past OWN_TABLE_RANGE a new table is the shorter errand.
        BlockPos own = Placed.get().nearestOwn(player.level(), player.position(), Blocks.CRAFTING_TABLE);
        return own != null && own.distToCenterSqr(player.position()) <= (double) OWN_TABLE_RANGE * OWN_TABLE_RANGE
                ? own : null;
    }

    /** The nearest table within the scan range, or null. */
    private static BlockPos nearbyTable(LocalPlayer player) {
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
