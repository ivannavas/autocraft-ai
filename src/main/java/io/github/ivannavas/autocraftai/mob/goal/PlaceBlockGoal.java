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
import net.minecraft.world.entity.player.Inventory;
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
    /** How far down to look for the block to build on when pillaring. */
    private static final int SUPPORT_SEARCH = 3;
    /** Kept under the server's reach so a placement is never sent from too far to land. */
    private static final double REACH_MARGIN = 0.5;
    private static final float SPEED = 1.0F;

    private final BlockPos target;
    private final Reserve reserve;

    private int ticksRunning;
    private boolean placed;
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
        this.target = target == null ? null : target.immutable();
        this.reserve = reserve == null ? Reserve.none() : reserve;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return !placed && hotbarSlotWithBlock(body.player(), reserve) >= 0;
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
        advance.reset();
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        int slot = hotbarSlotWithBlock(body.player(), reserve);
        if (slot < 0) {
            // Nothing to put down and no way to get any from here. Standing with an empty hand is the
            // clearest case there is of a decision that has stopped going anywhere.
            advance.nothing();
            return;
        }
        advance.walking(body);
        body.player().getInventory().setSelectedSlot(slot);

        if (pillaring(body)) {
            pillar(body);
            return;
        }
        placeAt(body, target);
    }

    /** Whether the block is going where the body itself is, which is the one case that needs a jump. */
    private boolean pillaring(MobBody body) {
        return target == null || target.equals(body.player().blockPosition());
    }

    /** Jump, and while off the ground drop a block onto whatever was holding the body up. */
    private void pillar(MobBody body) {
        // Standing still: the block has to go under the body, not wherever it drifted to.
        body.moveControl().stop();
        LocalPlayer player = body.player();
        if (player.onGround()) {
            body.jump();
            return;
        }
        BlockPos support = supportBelow(body);
        if (support == null) {
            return;
        }
        click(body, support, Direction.UP, Vec3.atCenterOf(support).add(0.0, 0.5, 0.0));
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

    private void click(MobBody body, BlockPos against, Direction face, Vec3 hit) {
        gameMode().ifPresent(mode -> {
            // Noted before the click, with what is in the hand now: after it the stack may be gone.
            Placed.get().mark(against.relative(face), body.player().getMainHandItem());
            mode.useItemOn(body.player(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, face, against, false));
            body.player().swing(InteractionHand.MAIN_HAND);
        });
        placed = true;
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

    /** The highest solid block under the body, which is the one a new block goes on top of. */
    private BlockPos supportBelow(MobBody body) {
        BlockPos feet = body.player().blockPosition();
        for (int drop = 1; drop <= SUPPORT_SEARCH; drop++) {
            BlockPos candidate = feet.below(drop);
            if (solid(body, candidate)) {
                return candidate;
            }
        }
        return null;
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
        if (player == null) {
            return -1;
        }
        Inventory inventory = player.getInventory();
        // Counted once for the whole hotbar rather than once per slot, and not at all when there is
        // nothing being held back, which is nearly every decision of nearly every run.
        InventoryCensus held = reserve.isEmpty() ? InventoryCensus.empty()
                : InventoryCensus.of(inventory);
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            // Building material only. A log is a block too, and a body that could place one did, four
            // times in a row, the moment placing stopped costing anything: see Resource#buildsWith.
            if (Resource.buildsWith(stack) && reserve.allowsPlacing(stack, held)) {
                return slot;
            }
        }
        return -1;
    }

    private Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        return target == null ? "PlaceBlock" : "PlaceBlock" + target.toShortString();
    }
}
