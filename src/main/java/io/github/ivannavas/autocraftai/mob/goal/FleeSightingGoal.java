package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.Sighting;
import net.minecraft.world.phys.Vec3;

/**
 * Backs away from whatever the body has in view until it is a safe distance off.
 *
 * <p>It runs to a point straight away from the target rather than pathing anywhere clever, and re-aims that
 * point as the target closes, so being chased turns into a running retreat instead of a sprint into the
 * first spot that was safe when the goal started.
 */
public final class FleeSightingGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** Far enough away to stop running. */
    private static final double SAFE_DISTANCE = 14.0;
    /** How far ahead the retreat point is placed each time it is re-aimed. */
    private static final double RETREAT_STEP = 8.0;
    private static final int REAIM_INTERVAL_TICKS = 10;
    private static final int GIVE_UP_TICKS = 200;
    private static final float SPEED = 1.0F;

    private final Sighting sighting;

    private int ticksRunning;

    public FleeSightingGoal(Sighting sighting) {
        this.sighting = sighting;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return sighting.isValid() && body.position().distanceTo(sighting.position()) < SAFE_DISTANCE;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return canUse(body) && ticksRunning < GIVE_UP_TICKS;
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        aim(body);
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        if (ticksRunning % REAIM_INTERVAL_TICKS == 0 || !body.moveControl().hasDestination()) {
            // Re-aim on arrival as well as on the clock, or the retreat stops dead at the first point that
            // was far enough away when it was picked.
            aim(body);
        }
        // Look where it is running, not back at what it is running from: the body walks in the direction it
        // faces, and a mob staring over its shoulder would trot backwards into the scenery.
        body.lookControl().lookAt(body.moveControl().destination());
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
    }

    private void aim(MobBody body) {
        Vec3 position = body.position();
        Vec3 away = position.subtract(sighting.position());
        if (away.lengthSqr() < 1.0E-4) {
            // Standing right on top of it: any direction will do, so keep whatever way the body faces.
            away = body.player().getLookAngle();
        }
        Vec3 retreat = position.add(away.normalize().scale(RETREAT_STEP));
        body.moveControl().moveTo(retreat.x, position.y, retreat.z, SPEED);
    }

    @Override
    public String name() {
        return "Flee(" + sighting.kind() + ")";
    }
}
