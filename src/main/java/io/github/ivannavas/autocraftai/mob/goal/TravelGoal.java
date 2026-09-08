package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.OptionalDouble;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Goes somewhere, and keeps going there.
 *
 * <p>{@link RandomStrollGoal} re-rolls its destination every few seconds, so over a minute it covers a
 * circle about ten blocks across — fine for milling about, useless for leaving a desert. This one is handed
 * a line by the position table (see {@link io.github.ivannavas.autocraftai.mob.ai.Ground}) and walks it.
 *
 * <h2>The bearing is the journey; the heading is only this second</h2>
 * The two used to be one number, and that is why the body walked in circles. Meeting a wall turned the
 * heading forty-five degrees and left it turned, so a run along a coastline or through a forest — anything
 * that deflects you the same way twice — came round on itself by construction, and eight deflections was a
 * full circle. Nothing ever brought the body back to the line it set off on, because after the first wall
 * there was no such line any more.
 *
 * <p>So the bearing is kept, untouched, for the whole journey, and a wall buys a <em>detour</em>: the
 * nearest heading to the bearing that is actually walkable, tried at forty-five, ninety and a hundred and
 * thirty-five degrees off it, and finally straight back. Every second the line itself is offered first
 * again, so the moment the obstacle ends the body is back on it. Detours are tried on whichever side the
 * last one went before the other, which is what keeps the body following one face of a wall to its end
 * instead of sidestepping back and forth in front of the middle of it.
 *
 * <h2>Progress is measured along the bearing, not in footsteps</h2>
 * The old test for being stuck was how far the body moved between re-aims, which a body walking a circle
 * passes with room to spare — it is moving, briskly, in a ring. What counts here is how far along the
 * bearing it has got from where it started, ratcheted: the journey has to keep beating its own record, or
 * it is not a journey and the decision goes back to the brain.
 *
 * <h2>Water is not a wall</h2>
 * It was, and a lake was the end of every journey that met one. Nothing in the water could be stood on, so
 * every heading across it came back empty and the goal stranded itself — on the bank when it walked there
 * itself, and floating in the middle when something else had carried it in, with a bearing it still had
 * and nowhere along it that it was willing to aim. A body can swim. The surface of water is somewhere it
 * can go, {@link io.github.ivannavas.autocraftai.mob.MoveControl} already holds the jump that keeps its
 * head up, and a line across a lake is the same line. Lava is not water, and stays a wall.
 */
public final class TravelGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** The furthest ahead to aim. Far enough to be a direction, near enough to be loaded and walkable. */
    private static final int AIM_FURTHEST = 12;
    /** The nearest. Closer than this the destination is under the body's own feet and steering is noise. */
    private static final int AIM_NEAREST = 4;
    /** How far apart the points along a line are sampled. */
    private static final int AIM_STEP = 4;
    /** Ticks between re-aims, which is also how often the detour is dropped and the bearing tried again. */
    private static final int REAIM_TICKS = 20;
    /** How far a detour turns, and so how far apart the detours that get tried are. */
    private static final double TURN = Math.PI / 4.0;
    /** How many turns to either side are worth trying before giving up on going forwards at all. */
    private static final int DETOURS = 3;
    /** Blocks of new ground along the bearing that count as having got somewhere. */
    private static final double ADVANCE = 4.0;
    /** Ticks allowed without making that much new ground. A ring of any size fails this. */
    private static final int STALL_TICKS = 200;
    /** Long: this is a journey, and the brain's commitment is what really ends it. */
    private static final int GIVE_UP_TICKS = 1200;
    private static final int FLOOR_SEARCH_UP = 3;
    private static final int FLOOR_SEARCH_DOWN = 4;
    private static final float SPEED = 1.0F;

    private final OptionalDouble told;

    /**
     * The journey, which outlives a restart.
     *
     * <p>A goal can be started several times inside one decision — something with a higher claim takes the
     * legs and gives them back — and a journey that forgot where it set off from every time that happened
     * could never tell a body that had walked in a ring from one that had only just arrived.
     */
    private boolean underway;
    private double bearing;
    private Vec3 origin;
    private double furthest;
    private int detourSide = 1;
    private int ticksRunning;
    private int ticksSinceAim;
    private int ticksSinceGain;
    private boolean stranded;

    /** Sets off whichever way the body is facing. */
    public TravelGoal() {
        this(OptionalDouble.empty());
    }

    /** @param told the bearing to walk, or empty to use whichever way the body is facing */
    public TravelGoal(OptionalDouble told) {
        this.told = told == null ? OptionalDouble.empty() : told;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        // Being stranded has to bar the start as well as the continuation. The engine offers a stopped goal
        // the body again on the very next tick, so without this the giving up is a stutter rather than a
        // handover, and the decision never gets back to the brain that has to learn from it.
        // Footing or water: a journey that would not start until the body was on dry ground was a body
        // that floated for the rest of its commitment after the swim goal handed it back mid-lake.
        return !stranded && ticksRunning < GIVE_UP_TICKS
                && (body.onGround() || body.player().isInWater()) && !body.player().isPassenger();
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !stranded && ticksRunning < GIVE_UP_TICKS;
    }

    /**
     * The journey's own measure, which is the one that tells a line from a loop: ticks since it last beat
     * its record along the bearing. A body walking a ring is moving briskly and getting nowhere, and
     * anything that counted footsteps would call that progress.
     */
    @Override
    public int stalledTicks() {
        return ticksSinceGain;
    }

    @Override
    public void start(MobBody body) {
        if (!underway) {
            underway = true;
            // Whatever the position table chose, or the way the body is already facing. It was looking at
            // something a moment ago, and spinning on the spot before walking reads as a bug.
            bearing = told.orElseGet(() -> Math.toRadians(body.player().getYRot()));
            origin = body.position();
            furthest = 0.0;
        }
        // What the stop actually undid: the destination was dropped. Everything else belongs to the
        // journey, and the journey did not begin again just because the body came back.
        ticksSinceAim = 0;
        aim(body);
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        noteProgress(body);
        // Re-aimed on a timer, so the bearing is offered again every second, and on arrival, so a short
        // detour does not leave the body standing at its destination waiting for the timer.
        if (++ticksSinceAim >= REAIM_TICKS || !body.moveControl().hasDestination()) {
            ticksSinceAim = 0;
            aim(body);
        }
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
    }

    /**
     * How the journey is doing, in the only terms that tell a line from a loop.
     *
     * <p>A ratchet against the best the journey has ever managed rather than against the last reading, so
     * rounding a lake — which spends a while going sideways and some of it going backwards — is not
     * mistaken for being stuck. What it will not forgive is ending up where it started.
     */
    private void noteProgress(MobBody body) {
        double advanced = advanced(body.position());
        if (advanced > furthest + ADVANCE) {
            furthest = advanced;
            ticksSinceGain = 0;
            return;
        }
        if (++ticksSinceGain >= STALL_TICKS) {
            stranded = true;
        }
    }

    /** How far along the bearing the body has got from where the journey started. */
    private double advanced(Vec3 position) {
        double dx = position.x - origin.x;
        double dz = position.z - origin.z;
        return dx * -Math.sin(bearing) + dz * Math.cos(bearing);
    }

    /** Points the body along the bearing, or along the least of the detours from it that is walkable. */
    private void aim(MobBody body) {
        for (int turn = 0; turn <= DETOURS; turn++) {
            for (int side : turn == 0 ? new int[] {1} : new int[] {detourSide, -detourSide}) {
                Vec3 spot = furthestWalkable(body, bearing + side * turn * TURN);
                if (spot == null) {
                    continue;
                }
                if (turn > 0) {
                    // Remembered so the next wall is taken on the same side. Alternating sides in front of
                    // a long wall is how a body sidesteps for a minute without ever getting past it.
                    detourSide = side;
                }
                body.lookControl().lookAt(spot);
                body.moveControl().moveTo(spot, SPEED);
                return;
            }
        }
        // Every way out is a cliff, a wall or unloaded chunk. Hand the decision back rather than shove.
        stranded = true;
    }

    /**
     * The furthest point along a line the body could walk or swim to without the way giving out.
     *
     * <p>Sampled outwards and stopped at the first gap, rather than asked about the far end alone. A single
     * tree trunk twelve blocks off used to condemn a whole direction, and a direction condemned was a turn,
     * and the turns were the circling. This aims short of the trunk instead, and a second later the tree is
     * beside the body and the line is clear again.
     *
     * @return where to go, or null when even the first step that way is neither walkable nor swimmable
     */
    private Vec3 furthestWalkable(MobBody body, double heading) {
        Vec3 best = null;
        for (int ahead = AIM_NEAREST; ahead <= AIM_FURTHEST; ahead += AIM_STEP) {
            Vec3 point = body.position().add(
                    -Math.sin(heading) * ahead, 0.0, Math.cos(heading) * ahead);
            Vec3 spot = standingSpot(body, BlockPos.containing(point));
            if (spot == null) {
                break;
            }
            best = spot;
        }
        return best;
    }

    /**
     * The first place in the column the body could be: a floor it could stand on with room for its whole
     * body, or failing that the surface of water it could swim in. Null when the column is unloaded or
     * offers neither.
     *
     * <p>Top down, so a bank above the water is found before the water is, and a body walks where it can
     * and swims only where it must.
     */
    private Vec3 standingSpot(MobBody body, BlockPos candidate) {
        Level level = body.level();
        Player player = body.player();
        for (int y = candidate.getY() + FLOOR_SEARCH_UP; y >= candidate.getY() - FLOOR_SEARCH_DOWN; y--) {
            BlockPos pos = new BlockPos(candidate.getX(), y, candidate.getZ());
            if (!level.isLoaded(pos)) {
                return null;
            }
            if (swimmable(level, pos)) {
                return Vec3.atBottomCenterOf(pos);
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

    /** The surface of water: water here with air over it, which is where a swimming head is. Never lava. */
    private static boolean swimmable(Level level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER) && level.getBlockState(pos.above()).isAir();
    }

    @Override
    public String name() {
        return "Travel";
    }
}
