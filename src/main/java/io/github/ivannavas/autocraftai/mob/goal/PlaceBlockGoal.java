package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.Placed;
import io.github.ivannavas.autocraftai.mob.ai.objective.InventoryCensus;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Puts one block down, where it is told to.
 *
 * <p>It used to only ever pillar — jump, and drop a block where the feet were. That is the right move for
 * getting out of a hole and the wrong one for everything else, and it was the only one, so building was a
 * single trick rather than a verb. Now the place comes from
 * {@link io.github.ivannavas.autocraftai.mob.ai.Spot}, chosen by a table, and this goal is what carries it
 * out: bridge a gap, cap a hole, wall off a corridor, or still pillar when pillaring is what was asked for.
 *
 * <h2>Two ways to put a block somewhere</h2>
 * Placing where the body already is means jumping first and dropping the block into the space just left,
 * and that is what happens when the target is the body's own square. Anywhere else is the ordinary way:
 * walk until it is in reach, look at the face of something solid beside it, and right-click.
 *
 * <p>Walking is part of the move rather than a precondition for it. The table may well pick a spot the body
 * cannot touch from where it stands, and the honest answer to that is to go there.
 */
public final class PlaceBlockGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    private static final int GIVE_UP_TICKS = 100;
    /** How many refused clicks at one spot before the spot is given up on. */
    private static final int REFUSALS_ALLOWED = 3;
    /** Kept under the server's reach so a placement is never sent from too far to land. */
    private static final double REACH_MARGIN = 0.5;
    private static final float SPEED = 1.0F;

    private final BlockPos target;
    private final Reserve reserve;
    private final boolean mayUseTable;
    /** A hotbar slot to place from whatever it holds, or -1 to pick a building block. */
    private final int fixedSlot;

    private int ticksRunning;
    private boolean placed;
    private int refusals;
    private final Advance advance = new Advance();

    /** Pillars under the body, which is what this goal did before it could be told anything else. */
    public PlaceBlockGoal() {
        this(null, Reserve.none());
    }

    /** @param target where the block goes, or null to pillar under the body wherever it is standing */
    public PlaceBlockGoal(BlockPos target) {
        this(target, Reserve.none());
    }

    /**
     * @param target  where the block goes, or null to pillar under the body wherever it is standing
     * @param reserve what the plan is holding back, which never reaches the hand
     */
    public PlaceBlockGoal(BlockPos target, Reserve reserve) {
        this(target, reserve, true);
    }

    /**
     * @param mayUseTable whether the crafting table counts as a block to put down when nothing else
     *                    does. It does for the moves that build a workbench; it does not for a tower, a
     *                    wall or a cap, where the only table in the bag was walled into the ground.
     */
    public PlaceBlockGoal(BlockPos target, Reserve reserve, boolean mayUseTable) {
        this(target, reserve, mayUseTable, -1);
    }

    /**
     * Places whatever a given hotbar slot holds — a table, a furnace, a torch — rather than a building
     * block of the goal's own choosing. What a skill means by "select it, then place it".
     */
    public PlaceBlockGoal(BlockPos target, int slot) {
        this(target, Reserve.none(), true, slot);
    }

    private PlaceBlockGoal(BlockPos target, Reserve reserve, boolean mayUseTable, int fixedSlot) {
        this.target = target == null ? null : target.immutable();
        this.reserve = reserve == null ? Reserve.none() : reserve;
        this.mayUseTable = mayUseTable;
        this.fixedSlot = fixedSlot;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return !placed && slot(body) >= 0;
    }

    private int slot(MobBody body) {
        if (fixedSlot >= 0) {
            ItemStack held = body.player().getInventory().getItem(fixedSlot);
            return held.getItem() instanceof BlockItem ? fixedSlot : -1;
        }
        return mayUseTable ? hotbarSlotWithBlock(body.player(), reserve)
                : hotbarSlotWithBuildingBlock(body.player(), reserve);
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !placed && ticksRunning < GIVE_UP_TICKS;
    }

    /** The block is down (or there was nowhere to put it): either way this move has run its course. */
    @Override
    public boolean isDone() {
        return placed;
    }

    /**
     * Walking to where the block goes, and nothing else: the placement itself ends the goal, so a body
     * that is neither getting to the spot nor putting anything down is a body waiting for something that
     * is not coming.
     */
    @Override
    public int stalledTicks() {
        return advance.stalledTicks();
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        placed = false;
        refusals = 0;
        advance.reset();
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        int slot = slot(body);
        if (slot < 0) {
            // Nothing to put down and no way to get any from here. Standing with an empty hand is the
            // clearest case there is of a decision that has stopped going anywhere.
            advance.nothing();
            return;
        }
        body.player().getInventory().setSelectedSlot(slot);

        if (pillaring(body)) {
            pillar(body);
            return;
        }
        advance.walking(body);
        placeAt(body, target);
    }

    /** Whether the block is going where the body itself is, which is the one case that needs a jump. */
    private boolean pillaring(MobBody body) {
        return target == null || target.equals(body.player().blockPosition());
    }

    /**
     * Jump, and once there is room drop a block onto whatever was holding the body up. The timing is
     * {@link Pillar}'s; what is decided here is when to stop: a block down is the move done, nothing to
     * build on is the move impossible, and a refusal is tried again a few times before it counts as that.
     */
    private void pillar(MobBody body) {
        switch (Pillar.tick(body)) {
            case PLACED -> {
                advance.progress();
                placed = true;
            }
            case NO_SUPPORT -> placed = true;
            case REFUSED -> {
                advance.nothing();
                if (++refusals >= REFUSALS_ALLOWED) {
                    placed = true;
                }
            }
            case RISING -> advance.nothing();
        }
    }

    /** Walk into reach, aim at the face of something solid next to the target, and right-click it. */
    private void placeAt(MobBody body, BlockPos where) {
        Direction face = open(body, where) ? faceToClick(body, where) : null;
        if (face == null) {
            // Nothing solid beside it to build off. The table chose a spot that has since become
            // impossible; give the decision back rather than standing there clicking at air.
            placed = true;
            return;
        }
        BlockPos against = where.relative(face);
        Vec3 hit = Vec3.atCenterOf(against).add(
                face.getOpposite().getStepX() * 0.5,
                face.getOpposite().getStepY() * 0.5,
                face.getOpposite().getStepZ() * 0.5);

        body.lookControl().lookAt(hit);
        if (!withinReach(body, hit)) {
            body.moveControl().moveTo(Vec3.atBottomCenterOf(where), SPEED);
            return;
        }
        body.moveControl().stop();
        click(body, against, face.getOpposite(), hit);
    }

    /**
     * One right-click, believed only if the game says it took: the client refuses a placement its own rules
     * rule out — a space something occupies, a block that cannot go there — before anything is sent, and
     * calling that placed used to end the move with nothing down. A refusal is tried again a few times,
     * since the next tick may be the one that fits, and then the spot is given up on.
     */
    private void click(MobBody body, BlockPos against, Direction face, Vec3 hit) {
        Optional<MultiPlayerGameMode> mode = gameMode();
        if (mode.isEmpty()) {
            placed = true;
            return;
        }
        // Copied before the click: after it the stack may be gone, and the record wants to know what went.
        ItemStack hand = body.player().getMainHandItem().copy();
        InteractionResult result = mode.get().useItemOn(body.player(), InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, against, false));
        if (result.consumesAction()) {
            Placed.get().mark(against.relative(face), hand);
            body.player().swing(InteractionHand.MAIN_HAND);
            advance.progress();
            placed = true;
        } else if (++refusals >= REFUSALS_ALLOWED) {
            placed = true;
        }
    }

    /**
     * Which side of the target has something solid to build against, preferring the floor.
     *
     * @return the direction from the target towards that neighbour, or null when it is surrounded by air
     */
    private Direction faceToClick(MobBody body, BlockPos where) {
        if (solid(body, where.below())) {
            return Direction.DOWN;
        }
        for (Direction side : Direction.values()) {
            if (side != Direction.DOWN && solid(body, where.relative(side))) {
                return side;
            }
        }
        return null;
    }

    private boolean solid(MobBody body, BlockPos pos) {
        return body.level().isLoaded(pos) && body.level().getBlockState(pos).isSolid();
    }

    /** Whether a block could go here: air, or something a placed block pushes aside, like a snow layer. */
    private boolean open(MobBody body, BlockPos pos) {
        return body.level().isLoaded(pos) && body.level().getBlockState(pos).canBeReplaced();
    }

    private boolean withinReach(MobBody body, Vec3 hit) {
        double reach = body.player().blockInteractionRange() - REACH_MARGIN;
        return body.player().getEyePosition().distanceToSqr(hit) <= reach * reach;
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
    }

    /** Whether the body has something it could put down. Also what makes this action legal at all. */
    public static int hotbarSlotWithBlock(LocalPlayer player) {
        return hotbarSlotWithBlock(player, Reserve.none());
    }

    /**
     * The first block in the hotbar the plan will let go of.
     *
     * <p>The one place a reserve has to be honoured, and it is a slot rather than a rule: a block the
     * plan is holding back is simply never the thing in the hand, so nothing downstream has to know the
     * reserve exists. Every way the body puts a block down goes through here.
     *
     * @return the hotbar slot, or -1 when there is nothing spare to build with
     */
    public static int hotbarSlotWithBlock(LocalPlayer player, Reserve reserve) {
        return slotWithBlock(player, reserve, true);
    }

    /** The same, never the crafting table: for towers, walls and caps, which a table is wasted on. */
    public static int hotbarSlotWithBuildingBlock(LocalPlayer player, Reserve reserve) {
        return slotWithBlock(player, reserve, false);
    }

    private static int slotWithBlock(LocalPlayer player, Reserve reserve, boolean mayUseTable) {
        if (player == null) {
            return -1;
        }
        Inventory inventory = player.getInventory();
        // Counted once for the whole hotbar rather than once per slot, and not at all when there is
        // nothing being held back, which is nearly every decision of nearly every run.
        InventoryCensus held = reserve.isEmpty() ? InventoryCensus.empty()
                : InventoryCensus.of(inventory);
        // Plain building material first, and the crafting table only when there is nothing else: a
        // table is a block, and a table walled into a corridor is a pickaxe that cannot be made.
        int table = -1;
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            // Building material only. A log is a block too, and a body that could place one did, four
            // times in a row, the moment placing stopped costing anything: see Resource#buildsWith.
            if (!Resource.buildsWith(stack) || !reserve.allowsPlacing(stack, held)) {
                continue;
            }
            if (Resource.of(stack).orElse(null) == Resource.CRAFTING_TABLE) {
                table = table < 0 && mayUseTable ? slot : table;
                continue;
            }
            return slot;
        }
        return table;
    }

    private Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        return target == null ? "PlaceBlock" : "PlaceBlock" + target.toShortString();
    }
}
