package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Gets the body back under the open sky, by whatever the ground allows.
 *
 * <p>The move for a body that has been trapped: down a pit it fell into, in a cave it dug into after
 * stone, under a roof with an objective that lives on the surface. Three ways up, tried in order every
 * tick: break the ceiling when there is one within reach; stack a block under the feet when there is
 * room; and, with nothing to stack, cut steps — break the two blocks at head height in the wall ahead
 * and walk up onto the one at the feet, which is a staircase one step at a time. Done the moment the
 * sky is over the head.
 *
 * <p>Not clever about routes. A pit is climbed straight up its wall and a cave straight up through its
 * roof, because the sky is straight up and every other direction is a guess; the tactics table is what
 * learns whether the guess pays.
 */
public final class DaylightGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** A climb that has not found the sky in this long is not going to from here. */
    private static final int GIVE_UP_TICKS = 900;
    /** Ticks without a block broken, a block placed or height gained before the climb is called stuck. */
    private static final int STALLED_TICKS = 120;
    /** Refused clicks in a row before stacking is given up on for this tick's spot. */
    private static final int REFUSALS_ALLOWED = 8;
    private static final float SPEED = 1.0F;

    private final Reserve reserve;
    /** The height of the ground around: where a body out of the pit, or up through the roof, stands. */
    private final int surface;

    private int ticksRunning;
    private int ticksStalled;
    private int refusals;
    private double highest;
    private BlockPos breaking;
    private boolean done;

    /**
     * @param surface the height of the surface around the body, which is where "out" is: the sky is
     *                over the head at the bottom of a pit as well, and a goal that stopped at the sky
     *                never started in one
     */
    public DaylightGoal(int surface, Reserve reserve) {
        this.surface = surface;
        this.reserve = reserve == null ? Reserve.none() : reserve;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        // A climb that has run out its ticks, or stalled, stays given up: start() keeps the counters, and
        // offering it the body again next tick is what had it jumping, breaking the ceiling and placing
        // a block forty times a second on the box without ever getting anywhere.
        return !done && !out(body) && ticksRunning < GIVE_UP_TICKS && ticksStalled < STALLED_TICKS;
    }

    /** Out: the sky over the head and the feet up at the level of the ground around. */
    private boolean out(MobBody body) {
        return skyOverhead(body) && body.player().getBlockY() >= surface - 1;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !done && ticksRunning < GIVE_UP_TICKS && ticksStalled < STALLED_TICKS;
    }

    /** The sky is over the head, which was the whole of the job. */
    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public int stalledTicks() {
        return ticksStalled;
    }

    @Override
    public void start(MobBody body) {
        highest = body.position().y;
        breaking = null;
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        if (out(body)) {
            done = true;
            return;
        }
        // Height gained is progress whichever way it came.
        if (body.position().y > highest + 0.9) {
            highest = body.position().y;
            ticksStalled = 0;
        }
        BlockPos feet = body.player().blockPosition();
        BlockPos ceiling = ceilingOver(body, feet);
        if (ceiling != null) {
            body.moveControl().stop();
            breakBlock(body, ceiling);
            return;
        }
        int slot = PlaceBlockGoal.hotbarSlotWithBuildingBlock(body.player(), reserve);
        if (slot >= 0) {
            body.player().getInventory().setSelectedSlot(slot);
            stack(body);
            return;
        }
        steps(body, feet);
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
        Digging.stopBreaking();
    }

    private static boolean skyOverhead(MobBody body) {
        return body.level().canSeeSky(body.player().blockPosition().above());
    }

    /** How far up the column over the head is cleared before a block is stacked: what the arm reaches. */
    private static final int CLEAR_UP = 5;

    /**
     * The lowest solid block over the head within the arm's reach, or null when the column is clear.
     *
     * <p>Looking only two blocks up made the climb a stutter: one block broken, one stacked, and the
     * ceiling two blocks over the head again. Clearing the whole column the arm can reach first turns
     * that into four broken and three stacked, which is what a player does. A block beyond the third
     * that cannot be broken is not a reason to stall: the column is clear enough to stack under it.
     */
    private static BlockPos ceilingOver(MobBody body, BlockPos feet) {
        for (int up = 2; up <= CLEAR_UP; up++) {
            BlockPos pos = feet.above(up);
            if (!Digging.solid(body, pos)) {
                continue;
            }
            if (up <= 3 || Digging.breakable(body, pos)) {
                return pos.immutable();
            }
            return null;
        }
        return null;
    }

    /** One tick of breaking a block, with the stall counted on blows that land nothing. */
    private void breakBlock(MobBody body, BlockPos target) {
        if (!Digging.breakable(body, target)) {
            ticksStalled = STALLED_TICKS;
            return;
        }
        if (!target.equals(breaking)) {
            breaking = target;
            Digging.equip(body, target);
        }
        if (Digging.strike(body, target)) {
            ticksStalled = 0;
        } else {
            ticksStalled++;
        }
    }

    /** One tick of stacking a block under the feet. */
    private void stack(MobBody body) {
        switch (Pillar.tick(body)) {
            case PLACED -> {
                refusals = 0;
                ticksStalled = 0;
            }
            case REFUSED -> {
                if (++refusals >= REFUSALS_ALLOWED) {
                    ticksStalled = STALLED_TICKS;
                }
            }
            case NO_SUPPORT -> ticksStalled = STALLED_TICKS;
            case RISING -> {
                // Mid-jump.
            }
        }
    }

    /**
     * Cuts a step into the wall ahead and climbs it: with the two blocks at head height gone, the block
     * at the feet is a one-block step the legs hop up on their own. With no wall ahead, turns to face
     * the nearest one, since a pit has a wall on every side.
     */
    private void steps(MobBody body, BlockPos feet) {
        Direction facing = body.player().getDirection();
        BlockPos footAhead = feet.relative(facing);
        if (!Digging.solid(body, footAhead)) {
            Direction wall = wallBeside(body, feet);
            if (wall == null) {
                // Open ground on every side and no sky: a cave floor. Nothing to climb here.
                ticksStalled++;
                return;
            }
            body.lookControl().lookAt(Vec3.atCenterOf(feet.relative(wall).above()));
            return;
        }
        for (int up = 1; up <= 2; up++) {
            BlockPos over = footAhead.above(up);
            if (Digging.solid(body, over)) {
                body.moveControl().stop();
                breakBlock(body, over);
                return;
            }
        }
        // The step is cut: walk onto it. The legs hop a one-block step on their own.
        body.moveControl().moveTo(Vec3.atBottomCenterOf(footAhead.above()), SPEED);
        ticksStalled++;
    }

    /** A side with a solid block at foot height, for a body that is not facing one. */
    private static Direction wallBeside(MobBody body, BlockPos feet) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (Digging.solid(body, feet.relative(side))) {
                return side;
            }
        }
        return null;
    }

    @Override
    public String name() {
        return "Daylight";
    }
}
