package io.github.ivannavas.autocraftai.mob.goal;

import io.github.ivannavas.autocraftai.mob.MobBody;
import net.minecraft.world.phys.Vec3;

/**
 * How long a goal has spent getting nowhere.
 *
 * <p>Every goal a body is given is an estimate of how long something will take, and estimates are wrong.
 * What was missing was any way for a goal to say so: shoving at a wall, swinging at a block it cannot
 * reach, standing about with a job that finished ten seconds ago — from outside all three look exactly
 * like a goal that is halfway through something. This is the count each goal keeps about itself, and
 * {@link io.github.ivannavas.autocraftai.mob.MobGoal#stalledTicks()} is where it reads out.
 *
 * <h2>A second at a time, not a tick</h2>
 * Whether the body moved this tick says nothing: walking is a fifth of a block a tick, and standing at a
 * crafting table is none of it on purpose. So ground covered is measured over a whole second at a time,
 * against a bar even a slow, jumping, half-swimming second clears, and a second that does not clear it is
 * a second charged to the count. Net displacement rather than distance travelled, because a body hopping
 * against a wall has been very busy and has got nowhere.
 */
final class Advance {

    /** Ticks in one reading. A second: long enough that ordinary walking always clears the bar. */
    private static final int WINDOW = 20;
    /** Blocks to cover in one reading to count as having gone anywhere. A quarter of a free walk. */
    private static final double MOVED = 1.0;

    private Vec3 mark;
    private int ticks;
    private int stalled;

    /** One tick of trying to get somewhere. The count only grows when a whole second covers no ground. */
    void walking(MobBody body) {
        Vec3 now = body.position();
        if (mark == null) {
            mark = now;
            ticks = 0;
        }
        if (++ticks < WINDOW) {
            return;
        }
        stalled = mark.distanceTo(now) >= MOVED ? 0 : stalled + ticks;
        mark = now;
        ticks = 0;
    }

    /** Something went right that has nothing to do with moving: a blow landed, a block went down. */
    void progress() {
        reset();
    }

    /** A tick that got nowhere, for the goals that can tell without measuring any ground. */
    void nothing() {
        mark = null;
        ticks = 0;
        stalled++;
    }

    /** Forgets the attempt so far, for a goal that has started again on something else. */
    void reset() {
        mark = null;
        ticks = 0;
        stalled = 0;
    }

    /** Ticks this goal has to show for nothing. */
    int stalledTicks() {
        return stalled;
    }
}
