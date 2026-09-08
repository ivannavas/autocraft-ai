package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.FocusKind;
import io.github.ivannavas.autocraftai.mob.ai.Sighting;

/**
 * Walks up to whatever the body has in view and stops just short of it.
 *
 * <p>The destination is re-issued every tick because the sighting can move: the straight line is recomputed
 * against where the target is now, not where it was when the goal started.
 */
public final class ApproachSightingGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** Close enough to a creature or a block. Below this the goal is done rather than shoving at it. */
    private static final double ARRIVAL_DISTANCE = 2.5;
    /**
     * A dropped item has to be walked onto, not walked up to: the server picks it up within about a block,
     * so stopping at conversational distance would leave the body admiring its own loot forever.
     */
    private static final double PICKUP_DISTANCE = 0.0;
    private static final int GIVE_UP_TICKS = 200;
    private static final float SPEED = 1.0F;

    private final Sighting sighting;
    private final double arrivalDistance;

    private int ticksRunning;
    private boolean arrived;
    private final Advance advance = new Advance();

    public ApproachSightingGoal(Sighting sighting) {
        this.sighting = sighting;
        this.arrivalDistance = sighting.kind() == FocusKind.ITEM ? PICKUP_DISTANCE : ARRIVAL_DISTANCE;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return sighting.isValid() && distanceTo(body) > arrivalDistance;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        // A drop that is no longer there was picked up — by this body, nearly always, since it was
        // walking onto it — and that is the job done rather than the target lost. Said here because the
        // engine asks this before it would tick, and a goal that has stopped is never ticked again.
        if (ticksRunning > 0 && sighting.kind() == FocusKind.ITEM && !sighting.isValid()) {
            arrived = true;
        }
        return canUse(body) && ticksRunning < GIVE_UP_TICKS;
    }

    /**
     * Arrived: within arm's length of something that is still there, or a drop that is there no longer.
     * Not the same as having lost a creature, which stays a goal that could not.
     */
    @Override
    public boolean isDone() {
        return arrived;
    }

    /**
     * Walking to something and not getting any closer to it. Arriving is not this: the goal stops the
     * moment it is within arm's length, and a goal that has stopped is the brain's business rather than
     * this counter's.
     */
    @Override
    public int stalledTicks() {
        return advance.stalledTicks();
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        arrived = false;
        advance.reset();
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        body.moveControl().moveTo(sighting.position(), SPEED);
        body.lookControl().lookAt(sighting.eyePosition());
        advance.walking(body);
        arrived = sighting.isValid() && distanceTo(body) <= arrivalDistance;
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
    }

    private double distanceTo(MobBody body) {
        return body.position().distanceTo(sighting.position());
    }

    @Override
    public String name() {
        return "Approach(" + sighting.kind() + ")";
    }
}
