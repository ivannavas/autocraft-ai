package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.WastedEffort;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Cuts a staircase upwards, one step at a time.
 *
 * <h2>The move the repertoire did not have</h2>
 * {@link DigDownGoal} needs nothing in view and nothing in the bag: a body anywhere can always go down.
 * Going up had no such move. {@link ReachBandGoal} says it plainly — walk first, build second, dig last —
 * and its {@code dig last} breaks the block under the feet, which only ever goes the other way. So the
 * three ways up were: walk, which a sealed chamber does not allow; stack blocks, which needs blocks; and
 * the terrain layer choosing {@code BREAK_ABOVE}, which is an opinion a fresh table does not hold.
 *
 * <p>The repertoire could always descend and could not always ascend, and that asymmetry is a trap with
 * two faces. One morning a body towered out of a forest canopy and could not get off its own pillar; that
 * same afternoon another sat at y=-20 with no blocks, starving, told to climb to 63, and paced a chamber
 * seven blocks wide for twenty minutes while the planner rewrote the same objective three times. Both are
 * the same missing verb seen from opposite ends.
 *
 * <h2>How a body climbs through rock with nothing</h2>
 * Exactly the way a person does. The block beside the feet is the step; what stops you standing on it is
 * the two blocks above it, and your own ceiling. Break those three and walk into the column, and the feet
 * end a block higher than they began. Repeat and it is a spiral staircase.
 *
 * <p>No materials, only time and whatever is in the hand — and bare hands are fine through dirt, which is
 * most of what seals a body in. Stone costs the same wasted-effort charge here as anywhere else.
 */
public final class CarveUpGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** Nothing above this is worth climbing towards; the sky starts well below it. */
    private static final int TOP = 200;
    /** A block that has not given way in this long is not going to. */
    private static final int GIVE_UP_TICKS = 400;
    /** Getting nowhere for this long: something is wrong that standing there will not fix. */
    private static final int STALLED_TICKS = 30;
    /** The four ways a staircase can turn. */
    private static final Direction[] WAYS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private int ticksRunning;
    private int ticksStalled;
    private boolean breaking;
    private boolean unsafe;
    private BlockPos equippedFor;

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return !unsafe && ticksRunning < GIVE_UP_TICKS && somewhereToCut(body) != null;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return canUse(body);
    }

    /**
     * Refuses interruption while a block is genuinely coming apart, for {@link MineSightingGoal}'s reason:
     * every swap throws the progress away, and a block that is never finished is never rewarded.
     */
    @Override
    public boolean isInterruptable() {
        return !breaking;
    }

    @Override
    public boolean isDone() {
        return unsafe || ticksRunning >= GIVE_UP_TICKS;
    }

    @Override
    public int stalledTicks() {
        return ticksStalled;
    }

    @Override
    public void start(MobBody body) {
        breaking = false;
        ticksStalled = 0;
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        Direction cutting = somewhereToCut(body);
        if (cutting == null) {
            unsafe = true;
            return;
        }
        BlockPos next = inTheWay(body, cutting);
        if (next == null) {
            // The step is clear: walk into it and the feet come up a block. The move control does the
            // jump, the same as it would over any other step.
            BlockPos step = body.player().blockPosition().relative(cutting);
            body.moveControl().moveTo(Vec3.atBottomCenterOf(step.above()), 1.0F);
            body.lookControl().lookAt(Vec3.atCenterOf(step.above()));
            breaking = false;
            ticksStalled++;
            return;
        }
        equip(body, next);
        body.lookControl().lookAt(Vec3.atCenterOf(next));
        body.moveControl().stop();
        swing(body, next);
    }

    @Override
    public void stop(MobBody body) {
        breaking = false;
        body.moveControl().stop();
    }

    /**
     * Which way a step could be cut, or null when none of the four will do.
     *
     * <p>A way works when the block beside the feet is something to stand on and the two above it can be
     * broken. Asked afresh every tick rather than remembered, because the answer changes as the blocks
     * come away, and a staircase that insisted on its first choice would cut into lava rather than turn.
     */
    private Direction somewhereToCut(MobBody body) {
        if (body.player().getBlockY() >= TOP) {
            return null;
        }
        Direction facing = body.player().getDirection();
        if (cuttable(body, facing)) {
            return facing;
        }
        for (Direction other : WAYS) {
            if (other != facing && cuttable(body, other)) {
                return other;
            }
        }
        return null;
    }

    private boolean cuttable(MobBody body, Direction towards) {
        Level level = body.level();
        BlockPos step = body.player().blockPosition().relative(towards);
        if (!level.isLoaded(step) || !level.isLoaded(step.above(2))) {
            return false;
        }
        // Something to stand on, and no fluid anywhere in the column: lava above a staircase is how a
        // body cuts its way into the one thing it cannot survive.
        if (!level.getBlockState(step).isSolid()) {
            return false;
        }
        for (int up = 1; up <= 2; up++) {
            if (!level.getFluidState(step.above(up)).isEmpty()) {
                return false;
            }
        }
        return level.getFluidState(body.player().blockPosition().above(2)).isEmpty();
    }

    /** The next block that has to come away before the step can be walked onto, or null if none is left. */
    private BlockPos inTheWay(MobBody body, Direction towards) {
        Level level = body.level();
        BlockPos feet = body.player().blockPosition();
        BlockPos step = feet.relative(towards);
        // Head room where the body is going, then head room where it is: the last one only matters when
        // the body is sealed under a ceiling, which is exactly when this move is worth having.
        for (BlockPos candidate : new BlockPos[] {step.above(), step.above(2), feet.above(2)}) {
            BlockState state = level.getBlockState(candidate);
            if (!state.isAir() && !state.canBeReplaced() && level.getFluidState(candidate).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether a body could cut its way up from here at all. The legality question, asked the way
     * {@code canDigDown} is: a move that cannot be made should not be on the table for a table to
     * discover one punished decision at a time.
     */
    public static boolean anywhereToCut(MobBody body) {
        return new CarveUpGoal().somewhereToCut(body) != null;
    }

    /** The right thing in the hand for this block, once per block: switching mid-break cancels it. */
    private void equip(MobBody body, BlockPos target) {
        if (target.equals(equippedFor)) {
            return;
        }
        equippedFor = target.immutable();
        BlockState state = body.level().getBlockState(target);
        int slot = Tool.bestFor(state).hotbarSlotFor(body.player().getInventory(), state);
        if (slot >= 0) {
            body.player().getInventory().setSelectedSlot(slot);
        }
    }

    private void swing(MobBody body, BlockPos target) {
        BlockHitResult hit = aimedAt(body, target);
        if (hit == null || !strike(body, target, hit)) {
            breaking = false;
            if (++ticksStalled >= STALLED_TICKS) {
                unsafe = true;
            }
            return;
        }
        ticksStalled = 0;
    }

    private boolean strike(MobBody body, BlockPos target, BlockHitResult hit) {
        MultiPlayerGameMode gameMode = gameMode().orElse(null);
        if (gameMode == null || !gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
            return false;
        }
        Minecraft.getInstance().level.addBreakingBlockEffect(hit.getBlockPos(), hit.getDirection());
        body.player().swing(InteractionHand.MAIN_HAND);
        breaking = true;
        // Cutting up through stone with no pickaxe still gets there, very slowly, and comes up with
        // nothing on the way. The height is the objective's business; the waste is charged here.
        if (!body.player().hasCorrectToolForDrops(body.level().getBlockState(target))) {
            WastedEffort.get().wastedSwing();
        }
        return true;
    }

    /** A raycast from the eye to the block, and the face it enters by. Its own, not the crosshair's. */
    private BlockHitResult aimedAt(MobBody body, BlockPos target) {
        LocalPlayer player = body.player();
        BlockHitResult hit = body.level().clip(new ClipContext(
                player.getEyePosition(), Vec3.atCenterOf(target),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target) ? hit : null;
    }

    private Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        return "CarveUp";
    }
}
