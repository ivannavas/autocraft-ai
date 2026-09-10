package io.github.ivannavas.autocraftai.mob.goal;

import io.github.ivannavas.autocraftai.mob.MobBody;
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
 * One blow at one block, the way every goal here strikes: look at it, hold the right tool, and hit it
 * only when the eye's own raycast says it is the thing under the crosshair.
 *
 * <p>The mining, digging and breaking goals each carried a copy of this; the composite goals that came
 * after them — holing up, climbing back to the sky — would have made five. What is shared is the
 * mechanics, not the judgement: each goal still decides what to hit and when to stop.
 */
final class Digging {

    private Digging() {
    }

    /**
     * Puts the block's own tool in the hand, if the hotbar has one. Callers do this once per block:
     * switching slots mid-break cancels the break.
     */
    static void equip(MobBody body, BlockPos target) {
        BlockState state = body.level().getBlockState(target);
        int slot = Tool.bestFor(state).hotbarSlotFor(body.player().getInventory(), state);
        if (slot >= 0) {
            body.player().getInventory().setSelectedSlot(slot);
        }
    }

    /**
     * Turns towards the block and, once aimed at it, lands one blow.
     *
     * @return true when a blow landed; false while still turning, when something is in the way, or
     *         when the game would not take the blow
     */
    static boolean strike(MobBody body, BlockPos target) {
        body.lookControl().lookAt(Vec3.atCenterOf(target));
        LocalPlayer player = body.player();
        BlockHitResult hit = body.level().clip(new ClipContext(
                player.getEyePosition(), Vec3.atCenterOf(target),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        if (!hit.getBlockPos().equals(target)) {
            // Something on the line: still turning, or a tuft of grass at the feet between the eye and
            // the block under them. Grass and its like come away at a touch, so they are taken off the
            // line rather than waited on — a body in a meadow could not dig its own hole otherwise,
            // because every swing at the ground stopped at the grass it stood in.
            BlockState between = body.level().getBlockState(hit.getBlockPos());
            if (between.getDestroySpeed(body.level(), hit.getBlockPos()) != 0.0F) {
                return false;
            }
        }
        MultiPlayerGameMode mode = Minecraft.getInstance().gameMode;
        if (mode == null || !mode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
            return false;
        }
        Minecraft.getInstance().level.addBreakingBlockEffect(hit.getBlockPos(), hit.getDirection());
        body.player().swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** Lets go of whatever block was being broken, for a goal that is stopping. */
    static void stopBreaking() {
        MultiPlayerGameMode mode = Minecraft.getInstance().gameMode;
        if (mode != null) {
            mode.stopDestroyBlock();
        }
    }

    /** A raycast from the eye to the block, blocks only, or null when something else is in the way. */
    private static BlockHitResult aimedAt(MobBody body, BlockPos target) {
        LocalPlayer player = body.player();
        BlockHitResult hit = body.level().clip(new ClipContext(
                player.getEyePosition(), Vec3.atCenterOf(target),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target) ? hit : null;
    }

    static boolean solid(MobBody body, BlockPos pos) {
        return body.level().isLoaded(pos) && body.level().getBlockState(pos).isSolid();
    }

    /** Whether the block could ever come apart: bedrock cannot, and neither can anything unloaded. */
    static boolean breakable(MobBody body, BlockPos pos) {
        if (!body.level().isLoaded(pos)) {
            return false;
        }
        BlockState state = body.level().getBlockState(pos);
        return !state.isAir() && state.getDestroySpeed(body.level(), pos) >= 0.0F;
    }

    /**
     * The block the body is actually standing on: the one under the feet, or the feet's own block when
     * that is a slab or the like — a body on a bottom slab has its feet inside the slab's block, and a
     * swing at the block "under" it goes through the slab and lands nothing.
     */
    static BlockPos standingOn(MobBody body) {
        BlockPos feet = body.player().blockPosition();
        if (!body.level().getBlockState(feet).getCollisionShape(body.level(), feet).isEmpty()) {
            return feet;
        }
        return feet.below();
    }
}
