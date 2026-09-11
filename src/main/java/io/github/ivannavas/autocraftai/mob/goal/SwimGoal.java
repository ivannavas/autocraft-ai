package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Swims to one place: a lungful of air, or the bank.
 *
 * <p>Both are the same move. What tells them apart is where {@link io.github.ivannavas.autocraftai.mob.ai.Water}
 * found the spot, and that is the water table's choice rather than this goal's — here there is only a
 * position and the job of getting the body to it before its air runs out.
 *
 * <h2>Rising is not moving</h2>
 * The one thing this cannot leave to {@link io.github.ivannavas.autocraftai.mob.MoveControl}. That steers
 * on the flat and jumps when it is in water, which does rise — but a destination straight overhead is zero
 * blocks away horizontally, so it counts as reached the moment it is set and nothing ever holds the jump.
 * A body told to surface would have floated there until it drowned. So the climb is asked for directly, on
 * every tick the target is above the head, and the steering is only used for the part of the way that is
 * across.
 */
public final class SwimGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** Close enough on the flat that steering towards it would only jitter the body. */
    private static final double ARRIVED_WITHIN = 1.0;
    /** A swim that has not got there in this long is not going to; the brain can choose again. */
    private static final int GIVE_UP_TICKS = 200;
    private static final float SPEED = 1.0F;

    private final String choice;
    private final BlockPos target;

    private int ticksRunning;
    /** Ticks since the body was last in water: a bob at the shoreline, not a landing, until it is not. */
    private int dryTicks;
    /**
     * How long out of the water a swim keeps the body. At the water's edge the game reads "in water"
     * one tick and not the next as the body bobs, and a swim that let go on the first dry tick was
     * stopped and started seven times in a second, never got its stroke in, and the body drowned a
     * block from the shore.
     */
    private static final int SHORELINE_GRACE_TICKS = 10;
    private final Advance advance = new Advance();

    /**
     * @param choice which water choice this is carrying out, for the goal listing
     * @param target where to get to
     */
    public SwimGoal(String choice, BlockPos target) {
        this.choice = choice;
        this.target = target == null ? null : target.immutable();
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        // Out of the water there is nothing here to do, and saying so is what hands the body straight back
        // to whatever the goal table had chosen rather than making it wait for the next decision.
        return target != null && body.player().isInWater() && !arrived(body);
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return ticksRunning < GIVE_UP_TICKS && target != null && !arrived(body)
                && (body.player().isInWater() || dryTicks < SHORELINE_GRACE_TICKS);
    }

    /**
     * A swim that is covering no water. Counted in three dimensions like every other, which matters more
     * here than anywhere: rising is the whole move when the target is the surface, and a body holding jump
     * against a ceiling is going nowhere however much water it is churning.
     */
    @Override
    public int stalledTicks() {
        return advance.stalledTicks();
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        dryTicks = 0;
        advance.reset();
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        dryTicks = body.player().isInWater() ? 0 : dryTicks + 1;
        Vec3 spot = Vec3.atCenterOf(target);
        body.lookControl().lookAt(spot);

        // Held every tick it is below the target rather than once on arrival at the surface: in water the
        // jump key is the only way up, and letting go of it is sinking.
        if (target.getY() > body.player().getBlockY()) {
            body.jump();
        }
        if (across(body) > ARRIVED_WITHIN) {
            body.moveControl().moveTo(spot, SPEED);
        } else {
            body.moveControl().stop();
        }
        advance.walking(body);
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
    }

    /** Whether the body is at the target, counting height as well: a spot overhead is not somewhere else. */
    private boolean arrived(MobBody body) {
        return across(body) <= ARRIVED_WITHIN && body.player().getBlockY() >= target.getY();
    }

    /** How far there is still to go on the flat, which is the only part steering can help with. */
    private double across(MobBody body) {
        Vec3 here = body.position();
        double dx = target.getX() + 0.5 - here.x;
        double dz = target.getZ() + 0.5 - here.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String name() {
        return "Swim(" + choice + ")";
    }
}
