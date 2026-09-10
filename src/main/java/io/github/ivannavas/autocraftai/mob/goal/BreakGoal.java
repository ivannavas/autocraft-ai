package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks a short list of blocks, in order, from where the body stands, and is done when they are gone.
 *
 * <p>This is the passage layer's hands — see {@link io.github.ivannavas.autocraftai.mob.ai.Passage}. It is
 * not mining: nothing here is after a drop, the blocks are whatever happens to be in the way, and the body
 * does not walk anywhere to get at them, because the whole point is that they are already within arm's
 * reach. It is the two blocks in front of a body facing a wall, the block over its head under a ledge, or
 * the one under its feet.
 *
 * <p>Each block is looked at and struck the way {@link MineSightingGoal} strikes, through the same
 * {@code MultiPlayerGameMode} calls the mouse would drive, with the block's own tool in hand when the
 * hotbar has one. A block already gone is skipped, so a list handed over a second ago and half done is
 * picked up where it was left.
 *
 * <p>Not booked to wasted effort, deliberately. Breaking a passage through stone bare-handed drops
 * nothing and is still a passage; whether it was worth the seconds is the passage table's lesson to
 * learn, and it learns it from the seconds.
 */
public final class BreakGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /**
     * A list that has not come apart in this long is not going to from here. Forty seconds: two blocks of
     * deepslate take the bare hand thirty, and at fifteen the goal gave up on a stone wall it was three
     * blows from opening.
     */
    private static final int GIVE_UP_TICKS = 800;
    /** Aimed and getting nowhere for this long: the block will not take the blows, whatever the reason. */
    private static final int STALLED_TICKS = 60;

    private final List<BlockPos> targets;

    private int ticksRunning;
    private int ticksStalled;
    private boolean blocked;
    private boolean breaking;
    private BlockPos equippedFor;

    /** @param targets what to break, in the order to break it; positions already air are skipped */
    public BreakGoal(List<BlockPos> targets) {
        this.targets = List.copyOf(targets);
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return !blocked && ticksRunning < GIVE_UP_TICKS && next(body) != null;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return canUse(body);
    }

    /** Ticks since a blow last landed. A block coming apart resets it every tick. */
    @Override
    public int stalledTicks() {
        return ticksStalled;
    }

    /**
     * Whether a block is coming apart under the blows right now. The passage layer keeps its hands off
     * the choice while this holds: re-chosen every stuck second, a slow break was traded for a stroll
     * three blows from the end, and the game forgets the cracks the moment the blows stop.
     */
    public boolean breaking() {
        return breaking && !blocked;
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        // Standing still: the blocks were chosen from where the body is, and drifting changes the angles.
        body.moveControl().stop();
        BlockPos target = next(body);
        if (target == null) {
            return;
        }
        Vec3 centre = Vec3.atCenterOf(target);
        body.lookControl().lookAt(centre);
        equip(body, target);

        BlockHitResult hit = aimedAt(body, target);
        if (hit == null || !strike(body, hit)) {
            // Still turning, something in the way, or a blow the game would not take.
            breaking = false;
            if (++ticksStalled >= STALLED_TICKS) {
                blocked = true;
            }
            return;
        }
        ticksStalled = 0;
        breaking = true;
    }

    @Override
    public void stop(MobBody body) {
        breaking = false;
        body.moveControl().stop();
        gameMode().ifPresent(MultiPlayerGameMode::stopDestroyBlock);
    }

    /** The first block on the list that is still there, or null when the passage is open. */
    private BlockPos next(MobBody body) {
        for (BlockPos target : targets) {
            if (body.level().isLoaded(target) && !body.level().getBlockState(target).isAir()) {
                return target;
            }
        }
        return null;
    }

    /** The right thing in the hand for this block, chosen once per block: switching mid-break cancels it. */
    private void equip(MobBody body, BlockPos target) {
        if (target.equals(equippedFor)) {
            return;
        }
        equippedFor = target;
        BlockState state = body.level().getBlockState(target);
        int slot = Tool.bestFor(state).hotbarSlotFor(body.player().getInventory(), state);
        if (slot >= 0) {
            body.player().getInventory().setSelectedSlot(slot);
        }
    }

    private boolean strike(MobBody body, BlockHitResult hit) {
        MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
        if (gameMode == null || !gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
            return false;
        }
        Minecraft.getInstance().level.addBreakingBlockEffect(hit.getBlockPos(), hit.getDirection());
        body.player().swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** A raycast from the eye to the block, blocks only, or null when something else is in the way. */
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
        return "Break(" + targets.size() + ")";
    }
}
