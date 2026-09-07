package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.OptionalDouble;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Wanders. Every so often it picks a spot it could plausibly stand on somewhere nearby and walks over to
 * it, then goes idle until the next urge.
 *
 * <p>The counterpart of vanilla's {@code RandomStrollGoal} minus the pathfinding: the destination is only
 * checked for standing room, and getting there is {@link io.github.ivannavas.autocraftai.mob.MoveControl}'s
 * straight line. A destination behind a wall it cannot hop is given up on after {@link #GIVE_UP_TICKS}.
 *
 * <p>Given a heading it leans that way rather than rolling flat: the spot is still picked at random, but
 * from a box pushed out along the line the position table chose. Still wandering, and no longer wandering
 * in whichever direction the dice went.
 */
public final class RandomStrollGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** Walking into a wall it cannot hop would otherwise keep the goal running forever. */
    private static final int GIVE_UP_TICKS = 200;
    private static final int PLACEMENT_ATTEMPTS = 10;
    private static final int HORIZONTAL_RANGE = 10;
    private static final int VERTICAL_RANGE = 4;
    /** Blocks searched up and down from a candidate for a floor to stand on. */
    private static final int FLOOR_SEARCH_UP = 2;
    private static final int FLOOR_SEARCH_DOWN = 3;
    /** Anything closer than this is not worth walking to. */
    private static final double MIN_DISTANCE = 2.0;
    /** How far to push the search box along a chosen heading. Two thirds of the box: a lean, not a rail. */
    private static final int LEAN = 7;

    private final float speed;
    private final OptionalDouble told;

    private Vec3 destination;
    private int ticksRunning;

    public RandomStrollGoal() {
        this(1.0F, OptionalDouble.empty());
    }

    /** @param speed fraction of walking speed, in {@code (0, 1]} */
    public RandomStrollGoal(float speed) {
        this(speed, OptionalDouble.empty());
    }

    /** @param told the way to lean, or empty to wander evenly in every direction */
    public RandomStrollGoal(float speed, OptionalDouble told) {
        this.speed = speed;
        this.told = told == null ? OptionalDouble.empty() : told;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        if (!body.onGround() || body.player().isPassenger()) {
            return false;
        }
        // No dice roll before setting off: by the time this goal is installed the brain has already decided
        // that wandering is the thing to do, and hesitating again would just leave the body standing there.
        destination = findDestination(body);
        return destination != null;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return body.moveControl().hasDestination() && ticksRunning < GIVE_UP_TICKS;
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        body.moveControl().moveTo(destination, speed);
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
    }

    @Override
    public void stop(MobBody body) {
        destination = null;
        body.moveControl().stop();
    }

    private Vec3 findDestination(MobBody body) {
        RandomSource random = body.random();
        BlockPos origin = body.player().blockPosition();

        // The box is centred on the body, or pushed out along the chosen line so that most of what it
        // covers lies that way. Still a box and still random: a lean, not a rail.
        int leanX = 0;
        int leanZ = 0;
        if (told.isPresent()) {
            leanX = (int) Math.round(-Math.sin(told.getAsDouble()) * LEAN);
            leanZ = (int) Math.round(Math.cos(told.getAsDouble()) * LEAN);
        }

        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
            BlockPos candidate = origin.offset(
                    leanX + random.nextInt(-HORIZONTAL_RANGE, HORIZONTAL_RANGE + 1),
                    random.nextInt(-VERTICAL_RANGE, VERTICAL_RANGE + 1),
                    leanZ + random.nextInt(-HORIZONTAL_RANGE, HORIZONTAL_RANGE + 1));

            Vec3 spot = findStandingSpot(body, candidate);
            if (spot != null && spot.distanceToSqr(body.position()) >= MIN_DISTANCE * MIN_DISTANCE) {
                return spot;
            }
        }
        return null;
    }

    /**
     * Drops from just above the candidate looking for the first floor the player could stand on with room
     * for its whole body. Returns {@code null} if the column is unloaded or offers nowhere to land.
     */
    private Vec3 findStandingSpot(MobBody body, BlockPos candidate) {
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
}
