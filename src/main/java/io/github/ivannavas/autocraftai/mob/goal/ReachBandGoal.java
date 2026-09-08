package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Gets the body to a height, by finding a way there rather than by going through the world.
 *
 * <p>This is the move the run has been missing, and its absence is most of what has gone wrong. Height was
 * only ever reachable two ways: sink a shaft, or stack blocks under your own feet. Both work straight down
 * and straight up from wherever you happen to be standing, both ignore that the world is full of hillsides
 * and cave mouths and overhangs that go the same way for free, and one of them is how the body kept ending
 * up at the bottom of a pit it had dug itself.
 *
 * <h2>Walk first, build second, dig last</h2>
 * Every tick it looks for somewhere nearby it could stand that is closer to the height it wants and that it
 * could actually walk to — a step up, a slope, a ledge within a survivable drop. Walking there is free and
 * changes nothing about the world, so it is always the first answer.
 *
 * <p>Only when the ground offers nothing does it fall back: stacking a block under itself to go up, or
 * breaking the one under its feet to go down. Those are the moves that were doing all the work before, and
 * they belong at the end of the list rather than the start of it.
 */
public final class ReachBandGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** How far out to look for somewhere better to stand. */
    private static final int SCAN = 8;
    /** How far up or down of the scan to consider. */
    private static final int SCAN_VERTICAL = 6;
    /** A step this tall can be walked up; anything more needs building. */
    private static final int STEP_UP = 1;
    /** A drop longer than this hurts, so it is not a route. */
    private static final int SAFE_DROP = 3;
    /** Close enough. Standing on the exact block is not the point; being at that height is. */
    private static final int TOLERANCE = 1;
    /** A journey that has not got there in this long is not going to from here. */
    private static final int GIVE_UP_TICKS = 600;
    /** Ticks between looks for a better spot. Every tick would be a scan a tick, for no more progress. */
    private static final int RESCAN_TICKS = 10;
    /** Bedrock is at -64; there is nothing below this worth digging towards. */
    private static final int BOTTOM = -59;
    private static final double REACH_MARGIN = 0.5;
    private static final float SPEED = 1.0F;

    private final int target;
    private final Reserve reserve;

    private int ticksRunning;
    private int ticksSinceScan;
    private boolean stranded;
    private Vec3 heading;
    private final Advance advance = new Advance();

    /** @param target the height to get to */
    public ReachBandGoal(int target) {
        this(target, Reserve.none());
    }

    /**
     * @param target  the height to get to
     * @param reserve what the plan is holding back, which is never what gets stacked underfoot
     */
    public ReachBandGoal(int target, Reserve reserve) {
        this.target = target;
        this.reserve = reserve == null ? Reserve.none() : reserve;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return !arrived(body.player());
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !stranded && !arrived(body.player()) && ticksRunning < GIVE_UP_TICKS;
    }

    /**
     * Ground covered while there is a way to walk, and blocks placed or broken while there is not.
     *
     * <p>Both, because this goal changes hands halfway through: walking to a ledge and stacking blocks
     * under its own feet are the same objective by two methods, and a measure that only understood one of
     * them would call the other stalling. Height alone would not do either — most of a climb is walking
     * across to where the climb starts.
     */
    @Override
    public int stalledTicks() {
        return advance.stalledTicks();
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        ticksSinceScan = RESCAN_TICKS;
        stranded = false;
        heading = null;
        advance.reset();
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        if (++ticksSinceScan >= RESCAN_TICKS || heading == null) {
            ticksSinceScan = 0;
            heading = walkableWayThere(body);
        }
        if (heading != null) {
            body.lookControl().lookAt(heading);
            body.moveControl().moveTo(heading, SPEED);
            advance.walking(body);
            return;
        }
        // The ground offers nothing. Make some.
        body.moveControl().stop();
        if (body.player().getBlockY() < target) {
            build(body);
        } else {
            dig(body);
        }
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
        gameMode().ifPresent(MultiPlayerGameMode::stopDestroyBlock);
    }

    private boolean arrived(LocalPlayer player) {
        return player != null && Math.abs(player.getBlockY() - target) <= TOLERANCE;
    }

    /**
     * The nearest spot the body could stand on that is closer to the height it wants and that it could get
     * to from here without falling or being stopped by a wall.
     *
     * @return where to walk, or null when nothing around here is any better
     */
    private Vec3 walkableWayThere(MobBody body) {
        LocalPlayer player = body.player();
        BlockPos feet = player.blockPosition();
        int here = feet.getY();
        int closest = Math.abs(here - target);

        Vec3 best = null;
        double nearest = Double.MAX_VALUE;

        for (BlockPos candidate : BlockPos.betweenClosed(
                feet.offset(-SCAN, -SCAN_VERTICAL, -SCAN),
                feet.offset(SCAN, SCAN_VERTICAL, SCAN))) {
            int improvement = Math.abs(candidate.getY() - target);
            if (improvement >= closest) {
                continue;
            }
            // Reachable on foot: a step up it can climb, or a drop it can survive.
            int climb = candidate.getY() - here;
            if (climb > STEP_UP || climb < -SAFE_DROP) {
                continue;
            }
            Vec3 spot = standingSpot(body, candidate);
            if (spot == null) {
                continue;
            }
            double distance = spot.distanceToSqr(player.position());
            if (distance < nearest) {
                nearest = distance;
                best = spot;
            }
        }
        return best;
    }

    /** Stacks a block under the body: jump, and drop one into the space just left. */
    private void build(MobBody body) {
        int slot = hotbarSlotWithBlock(body.player(), reserve);
        if (slot < 0) {
            // Nothing to build with and nowhere to walk. The brain gets the decision back, and the plan
            // that wanted this height can be told the body needs blocks first.
            stranded = true;
            return;
        }
        LocalPlayer player = body.player();
        player.getInventory().setSelectedSlot(slot);
        if (player.onGround()) {
            body.jump();
            return;
        }
        BlockPos support = player.blockPosition().below();
        if (!solid(body, support)) {
            return;
        }
        Vec3 top = Vec3.atCenterOf(support).add(0.0, 0.5, 0.0);
        body.lookControl().lookAt(top);
        gameMode().ifPresent(mode -> {
            mode.useItemOn(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(top, Direction.UP, support, false));
            player.swing(InteractionHand.MAIN_HAND);
            // A block under the feet is a block of height, which is the whole of what this goal is for.
            advance.progress();
        });
    }

    /** Breaks the block under the feet, with the same two refusals digging down has always had. */
    private void dig(MobBody body) {
        LocalPlayer player = body.player();
        BlockPos under = player.blockPosition().below();
        BlockPos below = under.below();
        if (under.getY() <= BOTTOM || !solid(body, below)
                || !body.level().getFluidState(below).isEmpty()
                || !body.level().getFluidState(under).isEmpty()) {
            // A drop of unknown depth, lava, or the bottom of the world. Not a way down.
            stranded = true;
            return;
        }
        int slot = Tool.bestFor(body.level().getBlockState(under)).hotbarSlot(player.getInventory());
        if (slot >= 0) {
            player.getInventory().setSelectedSlot(slot);
        }
        Vec3 centre = Vec3.atCenterOf(under);
        body.lookControl().lookAt(centre);
        gameMode().ifPresent(mode -> {
            if (mode.continueDestroyBlock(under, Direction.UP)) {
                Minecraft.getInstance().level.addBreakingBlockEffect(under, Direction.UP);
                player.swing(InteractionHand.MAIN_HAND);
                advance.progress();
            }
        });
    }

    /** The first floor at or under the candidate the player could stand on with room for its whole body. */
    private Vec3 standingSpot(MobBody body, BlockPos candidate) {
        Level level = body.level();
        Player player = body.player();
        if (!level.isLoaded(candidate)) {
            return null;
        }
        BlockPos floor = candidate.below();
        if (!level.isLoaded(floor) || !level.getBlockState(floor).entityCanStandOn(level, floor, player)) {
            return null;
        }
        if (!level.getFluidState(candidate).isEmpty()) {
            return null;
        }
        Vec3 spot = Vec3.atBottomCenterOf(candidate);
        return level.noCollision(player, player.getDimensions(Pose.STANDING).makeBoundingBox(spot))
                ? spot : null;
    }

    private boolean solid(MobBody body, BlockPos pos) {
        return body.level().isLoaded(pos) && body.level().getBlockState(pos).isSolid();
    }

    /**
     * The first block the plan will let go of, which is
     * {@link PlaceBlockGoal#hotbarSlotWithBlock(LocalPlayer, Reserve)}'s answer rather than a second copy
     * of it. Climbing out of a hole is putting a block down like any other, and a reserve that held
     * everywhere except here would be a reserve with a hole in it.
     */
    private static int hotbarSlotWithBlock(LocalPlayer player, Reserve reserve) {
        return PlaceBlockGoal.hotbarSlotWithBlock(player, reserve);
    }

    private Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        return "ReachBand(y=" + target + ")";
    }
}
