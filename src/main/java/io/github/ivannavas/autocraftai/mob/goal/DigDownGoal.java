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
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Digs straight down, one block at a time.
 *
 * <p>Stone is underground and the eyes only look a few blocks below the feet, so a body told to fetch
 * cobblestone on the surface could look for it for ever and never see any. Digging was the missing verb:
 * without it, every objective under the ground was one the run could be given and never reach.
 *
 * <h2>Where it refuses to dig</h2>
 * Straight down is the fastest way to the stone and the classic way to die, so two things stop it. Lava or
 * a drop under the block being broken means the goal ends rather than opens it, and so does the bottom of
 * the world. Neither is a rule the brain has to learn — falling into lava would teach it, at the cost of
 * the run.
 *
 * <p>The tool is picked per block rather than once, because a shaft goes through dirt and then through
 * stone and the right thing to hold changes on the way down. Which it is comes from the game's own mineable
 * tags — see {@link Tool} — since the objective's own sources are about what it is looking for, and what is
 * under the feet on the way there is whatever happens to be there.
 *
 * <p>Like {@link MineSightingGoal} it refuses interruption once a block is genuinely coming apart, for the
 * same reason: every swap throws the progress away, and a block that is never finished is never rewarded.
 */
public final class DigDownGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** Bedrock starts at -64; there is nothing below this worth the trip. */
    private static final int BOTTOM = -59;
    /** A block that has not given way in this long is not going to. */
    private static final int GIVE_UP_TICKS = 400;

    private int ticksRunning;
    private boolean breaking;
    private boolean unsafe;
    private BlockPos equippedFor;

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return body.onGround() && !unsafe && diggable(body, under(body));
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !unsafe && ticksRunning < GIVE_UP_TICKS;
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
        unsafe = false;
        equippedFor = null;
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        // Standing still matters more here than anywhere else: drifting off the block being broken is
        // drifting off the only solid ground left.
        body.moveControl().stop();

        BlockPos target = under(body);
        if (!safeToOpen(body, target)) {
            unsafe = true;
            breaking = false;
            return;
        }
        body.lookControl().lookAt(Vec3.atCenterOf(target));
        equip(body, target);
        swing(body, target);
    }

    @Override
    public void stop(MobBody body) {
        breaking = false;
        body.moveControl().stop();
        gameMode().ifPresent(MultiPlayerGameMode::stopDestroyBlock);
    }

    private static BlockPos under(MobBody body) {
        return body.player().blockPosition().below();
    }

    private boolean diggable(MobBody body, BlockPos target) {
        BlockState state = body.level().getBlockState(target);
        return body.level().isLoaded(target) && !state.isAir()
                && body.level().getFluidState(target).isEmpty();
    }

    /**
     * Whether breaking this block leaves somewhere to stand rather than somewhere to fall.
     *
     * <p>Checks two blocks down as well as the one being broken: what matters is not what is being dug but
     * what is underneath it, which is what the body will be standing on a second from now.
     */
    private boolean safeToOpen(MobBody body, BlockPos target) {
        Level level = body.level();
        if (target.getY() <= BOTTOM || !diggable(body, target)) {
            return false;
        }
        BlockPos below = target.below();
        if (!level.isLoaded(below)) {
            return false;
        }
        // Air below means a drop of unknown depth; a fluid below means lava or a swim. Neither is a floor.
        return level.getBlockState(below).isSolid() && level.getFluidState(below).isEmpty();
    }

    /**
     * Puts the right thing in the hand for whatever is under the feet now, once per block. Switching slots
     * mid-break cancels it, so this happens on arrival at each new block and not again.
     */
    private void equip(MobBody body, BlockPos target) {
        if (target.equals(equippedFor)) {
            return;
        }
        equippedFor = target.immutable();
        int slot = Tool.bestFor(body.level().getBlockState(target)).hotbarSlot(body.player().getInventory());
        if (slot >= 0) {
            body.player().getInventory().setSelectedSlot(slot);
        }
    }

    private void swing(MobBody body, BlockPos target) {
        BlockHitResult hit = crosshairOn(target);
        if (hit == null) {
            // Still turning to look down. Swinging now would ask the server to break whatever the crosshair
            // happens to be on instead, which is the wall in front rather than the floor.
            breaking = false;
            return;
        }
        gameMode().ifPresent(gameMode -> {
            if (gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
                Minecraft.getInstance().level.addBreakingBlockEffect(hit.getBlockPos(), hit.getDirection());
                body.player().swing(InteractionHand.MAIN_HAND);
                breaking = true;
                // A shaft through stone with no pickaxe still gets deeper, very slowly, and comes up with
                // nothing on the way. The depth is the objective's business; the waste is charged here.
                if (!body.player().hasCorrectToolForDrops(body.level().getBlockState(target))) {
                    WastedEffort.get().wastedSwing();
                }
            }
        });
    }

    /** The game's own raycast, but only when it landed on the block under the feet. */
    private BlockHitResult crosshairOn(BlockPos target) {
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
        return "DigDown";
    }
}
