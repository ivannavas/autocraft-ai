package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.Sighting;

/**
 * Stands and watches whatever is in view.
 *
 * <p>It claims {@link MobControl#LOOK} and nothing else, so it is the one action that can share the body:
 * the engine is free to run a lower priority goal on the legs while this one keeps the head turned.
 */
public final class WatchSightingGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.LOOK);

    private static final int WATCH_TICKS = 80;

    private final Sighting sighting;

    private int ticksRunning;

    public WatchSightingGoal(Sighting sighting) {
        this.sighting = sighting;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return sighting.isValid();
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return sighting.isValid() && ticksRunning < WATCH_TICKS;
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        body.lookControl().lookAt(sighting.eyePosition());
    }

    @Override
    public String name() {
        return "Watch(" + sighting.kind() + ")";
    }
}
