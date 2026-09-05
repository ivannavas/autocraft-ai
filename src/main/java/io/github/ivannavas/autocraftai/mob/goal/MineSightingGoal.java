package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.Sighting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Walks up to a block and breaks it.
 *
 * <p>Two halves: out of range it is an approach, in range it holds the attack on the block. Breaking goes
 * through the same {@code MultiPlayerGameMode} calls the mouse would drive, so the server sees an ordinary
 * player mining and nothing needs installing on it.
 *
 * <h2>Why it swings only at what the crosshair is on</h2>
 * The block position and the face both come from {@link Minecraft#hitResult} — the game's own raycast —
 * rather than from a face worked out from the body's position. Vanilla mines what you are looking at, and
 * anything else asks the server to accept a break the player is not aimed at. Until the head has finished
 * turning, this goal turns and does not swing.
 *
 * <h2>Why it refuses to be interrupted</h2>
 * A log takes about sixty ticks of punching; the brain re-decides every twenty. Every swap calls
 * {@link MultiPlayerGameMode#stopDestroyBlock()} and throws the progress away, so mining could only ever
 * finish if the same action came up three times running — which, before it has learned that mining pays,
 * it has no reason to. The goal would never once be rewarded for the thing it exists to do. So once a
 * break is genuinely under way this goal declares itself uninterruptable and runs to the end.
 */
public final class MineSightingGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** Kept under the server's reach so a block is never swung at from too far to land. */
    private static final double REACH_MARGIN = 0.5;
    /** A block that has not given way in this long is not going to; let the brain choose again. */
    private static final int GIVE_UP_TICKS = 300;
    private static final float SPEED = 1.0F;

    private final Sighting sighting;
    private final BlockPos target;

    private int ticksRunning;
    private boolean breaking;

    public MineSightingGoal(Sighting sighting) {
        this.sighting = sighting;
        this.target = sighting.blockPos();
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return target != null && !body.level().getBlockState(target).isAir();
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return canUse(body) && ticksRunning < GIVE_UP_TICKS;
    }

    /** Only once the block is actually coming apart — before that there is no progress worth protecting. */
    @Override
    public boolean isInterruptable() {
        return !breaking;
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        breaking = false;
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        Vec3 centre = Vec3.atCenterOf(target);
        body.lookControl().lookAt(centre);

        if (!withinReach(body, centre)) {
            body.moveControl().moveTo(centre, SPEED);
            return;
        }

        // Close enough to swing: stand still, or the body drifts into the block it is breaking and the
        // straight-line steering starts shoving at the wall.
        body.moveControl().stop();
        swing(body);
    }

    @Override
    public void stop(MobBody body) {
        breaking = false;
        body.moveControl().stop();
        gameMode().ifPresent(MultiPlayerGameMode::stopDestroyBlock);
    }

    private boolean withinReach(MobBody body, Vec3 centre) {
        double reach = body.player().blockInteractionRange() - REACH_MARGIN;
        return body.player().getEyePosition().distanceToSqr(centre) <= reach * reach;
    }

    private void swing(MobBody body) {
        BlockHitResult hit = crosshairOnTarget();
        if (hit == null) {
            // Still turning, or something got between the body and the block. Swinging now would only ask
            // the server to break whatever happens to be under the crosshair instead.
            breaking = false;
            return;
        }
        gameMode().ifPresent(gameMode -> {
            // continueDestroyBlock starts the break itself when the target is new, so this one call covers
            // both the first tick and every one after it.
            if (gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
                Minecraft.getInstance().level.addBreakingBlockEffect(hit.getBlockPos(), hit.getDirection());
                body.player().swing(InteractionHand.MAIN_HAND);
                breaking = true;
            }
        });
    }

    /** The game's own raycast, but only when it landed on the block this goal is here for. */
    private BlockHitResult crosshairOnTarget() {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockHitResult block = (BlockHitResult) hit;
        return block.getBlockPos().equals(target) ? block : null;
    }

    private Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        return "Mine(" + sighting.kind() + ")";
    }
}
