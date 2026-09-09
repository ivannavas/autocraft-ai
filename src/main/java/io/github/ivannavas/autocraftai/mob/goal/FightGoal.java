package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.FocusKind;
import io.github.ivannavas.autocraftai.mob.ai.Sighting;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;

/**
 * Fights everything hostile in range, one at a time, in the order it was handed them.
 *
 * <p>{@link AttackSightingGoal} fights one thing: the one the eyes settled on, which is the nearest.
 * The nearest is the wrong one whenever a skeleton is about — it stands off and shoots while the body
 * is busy with the zombie in its face — so this is handed the whole list, most dangerous first, and
 * works down it. Each fight is the attack goal's own; what is added is the order, and the moving on.
 *
 * <p>Creepers are skipped whatever their place on the list. A wooden sword takes five blows to kill one
 * and it explodes on the third.
 */
public final class FightGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** A fight that has not been settled in this long is one to walk away from. */
    private static final int GIVE_UP_TICKS = 600;

    private final List<LivingEntity> targets;

    private AttackSightingGoal fight;
    private LivingEntity target;
    private int ticksRunning;
    private boolean done;

    /** @param targets what to fight, most dangerous first */
    public FightGoal(List<LivingEntity> targets) {
        this.targets = List.copyOf(targets);
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return !done && next() != null;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return canUse(body) && ticksRunning < GIVE_UP_TICKS;
    }

    /** Everything on the list is dead, or was never worth fighting. */
    @Override
    public boolean isDone() {
        return done || next() == null;
    }

    @Override
    public int stalledTicks() {
        return fight == null ? 0 : fight.stalledTicks();
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        engage(body, next());
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        if (target == null || !target.isAlive()) {
            LivingEntity following = next();
            if (following == null) {
                done = true;
                return;
            }
            engage(body, following);
        }
        fight.tick(body);
    }

    @Override
    public void stop(MobBody body) {
        if (fight != null) {
            fight.stop(body);
        }
    }

    private void engage(MobBody body, LivingEntity next) {
        if (fight != null) {
            fight.stop(body);
        }
        target = next;
        fight = next == null ? null : new AttackSightingGoal(Sighting.of(FocusKind.HOSTILE, next));
        if (fight != null) {
            fight.start(body);
        }
    }

    /** The first thing on the list still alive and worth hitting, or null. */
    private LivingEntity next() {
        for (LivingEntity candidate : targets) {
            if (candidate.isAlive() && !candidate.isRemoved() && !(candidate instanceof Creeper)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public String name() {
        return "Fight(" + targets.size() + ")";
    }
}
