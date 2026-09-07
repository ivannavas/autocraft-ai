package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Goes somewhere. Picks a heading and keeps to it, which is the whole difference from wandering.
 *
 * <p>{@link RandomStrollGoal} re-rolls its destination every few seconds, so over a minute it covers a
 * circle about ten blocks across — fine for milling about, useless for leaving a desert. This one holds one
 * bearing and walks it, aiming at a spot a few blocks ahead each time so the straight-line steering always
 * has somewhere loaded to head for, and only turns when the ground gives it no choice.
 *
 * <p>Turning by a fixed angle rather than re-rolling is deliberate. A body that turns randomly whenever it
 * is blocked wanders on the spot in front of a wall; one that turns forty-five degrees and tries again
 * walks along the wall until it ends.
 */
public final class TravelGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** How far ahead to aim. Far enough to be a direction, near enough to be loaded and walkable. */
    private static final int AIM_AHEAD = 10;
    /** Ticks between re-aims. The destination is reached long before this on open ground. */
    private static final int REAIM_TICKS = 20;
    /** Under this much ground covered between re-aims, the way ahead is blocked. */
    private static final double PROGRESS = 1.5;
    /** How far to turn when the way ahead is blocked. */
    private static final double TURN = Math.PI / 4.0;
    /** Long: this is a journey, and the brain's commitment is what really ends it. */
    private static final int GIVE_UP_TICKS = 1200;
    /** Tries this many turns before admitting there is nowhere to go from here. */
    private static final int TURN_ATTEMPTS = 8;
    private static final int FLOOR_SEARCH_UP = 3;
    private static final int FLOOR_SEARCH_DOWN = 4;
    private static final float SPEED = 1.0F;

    private double heading;
    private int ticksRunning;
    private int ticksSinceAim;
    private Vec3 lastAimPosition;
    private boolean stranded;

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return body.onGround() && !body.player().isPassenger();
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !stranded && ticksRunning < GIVE_UP_TICKS;
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        ticksSinceAim = 0;
        stranded = false;
        // Set off the way the body is already facing. It was looking at something a moment ago, and
        // spinning on the spot before walking is the sort of thing that reads as a bug.
        heading = Math.toRadians(body.player().getYRot());
        lastAimPosition = body.position();
        aim(body);
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        if (++ticksSinceAim < REAIM_TICKS) {
            return;
        }
        ticksSinceAim = 0;
        if (body.position().distanceTo(lastAimPosition) < PROGRESS) {
            heading += TURN;
        }
        lastAimPosition = body.position();
        aim(body);
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
    }

    /** Points the body at a spot along the current heading, turning until one of them is walkable. */
    private void aim(MobBody body) {
        for (int attempt = 0; attempt < TURN_ATTEMPTS; attempt++) {
            Vec3 ahead = body.position().add(
                    -Math.sin(heading) * AIM_AHEAD, 0.0, Math.cos(heading) * AIM_AHEAD);
            Vec3 spot = standingSpot(body, BlockPos.containing(ahead));
            if (spot != null) {
                body.lookControl().lookAt(spot);
                body.moveControl().moveTo(spot, SPEED);
                return;
            }
            heading += TURN;
        }
        // Every direction is a cliff, a wall or unloaded chunk. Hand the decision back rather than shove.
        stranded = true;
    }

    /**
     * The first floor near the candidate the player could stand on with room for its whole body, or null
     * when the column is unloaded or offers nowhere to land.
     */
    private Vec3 standingSpot(MobBody body, BlockPos candidate) {
        Level level = body.level();
        Player player = body.player();
        for (int y = candidate.getY() + FLOOR_SEARCH_UP; y >= candidate.getY() - FLOOR_SEARCH_DOWN; y--) {
            BlockPos pos = new BlockPos(candidate.getX(), y, candidate.getZ());
            if (!level.isLoaded(pos)) {
                return null;
            }
            BlockPos floor = pos.below();
            if (!level.getBlockState(floor).entityCanStandOn(level, floor, player)) {
                continue;
            }
            if (!level.getFluidState(pos).isEmpty()) {
                continue;
            }
            Vec3 spot = Vec3.atBottomCenterOf(pos);
            if (level.noCollision(player, player.getDimensions(Pose.STANDING).makeBoundingBox(spot))) {
                return spot;
            }
        }
        return null;
    }

    @Override
    public String name() {
        return "Travel";
    }
}
