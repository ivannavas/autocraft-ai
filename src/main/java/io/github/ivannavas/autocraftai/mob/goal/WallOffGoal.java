package io.github.ivannavas.autocraftai.mob.goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Puts a two-block wall between the body and where something is coming from.
 *
 * <p>The block at the feet first, then the one over it, on the side the threat is on: what a person
 * does in a cave mouth with a zombie in the corridor. One side only — the tactics table is asked again
 * a second later and, if something is coming from another side, walls that one too, so a body that
 * needs to be boxed in gets boxed in one wall at a time and a body that needed one wall gets one wall.
 */
public final class WallOffGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** A wall that is not up in this long is not going up. */
    private static final int GIVE_UP_TICKS = 160;

    private final Vec3 threatAt;
    private final Reserve reserve;

    private final List<BlockPos> spots = new ArrayList<>(2);
    private int at;
    private PlaceBlockGoal placing;
    private int ticksRunning;
    private boolean done;

    /** @param threatAt where the thing to wall off is */
    public WallOffGoal(Vec3 threatAt, Reserve reserve) {
        this.threatAt = threatAt;
        this.reserve = reserve == null ? Reserve.none() : reserve;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        // Given up stays given up: start() keeps the count, so a wall that ran out its ticks is not
        // offered the body again next tick and restarted forty times a second.
        return !done && ticksRunning < GIVE_UP_TICKS
                && PlaceBlockGoal.hotbarSlotWithBuildingBlock(body.player(), reserve) >= 0;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !done && ticksRunning < GIVE_UP_TICKS;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public int stalledTicks() {
        return placing == null ? 0 : placing.stalledTicks();
    }

    @Override
    public void start(MobBody body) {
        if (spots.isEmpty()) {
            BlockPos feet = body.player().blockPosition();
            Direction towards = towards(body.position(), threatAt);
            BlockPos low = feet.relative(towards);
            spots.add(low.immutable());
            spots.add(low.above().immutable());
        }
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        // Never drift: the spots were chosen beside where the body stood.
        if (placing == null) {
            if (at >= spots.size()) {
                done = true;
                return;
            }
            BlockPos spot = spots.get(at);
            if (Digging.solid(body, spot)) {
                // Already solid — the wall is half there. On to the next.
                at++;
                return;
            }
            placing = new PlaceBlockGoal(spot, reserve, false);
            if (!placing.canUse(body)) {
                done = true;
                return;
            }
            placing.start(body);
        }
        if (placing.isDone() || !placing.canContinueToUse(body)) {
            placing.stop(body);
            placing = null;
            at++;
            if (at >= spots.size()) {
                done = true;
            }
            return;
        }
        placing.tick(body);
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
        if (placing != null) {
            placing.stop(body);
        }
    }

    /** The horizontal direction from here towards there, snapped to the four the blocks allow. */
    private static Direction towards(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        return Direction.fromYRot(yaw);
    }

    @Override
    public String name() {
        return "WallOff(" + at + "/" + spots.size() + ")";
    }
}
