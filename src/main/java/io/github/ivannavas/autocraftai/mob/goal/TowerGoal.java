package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;

/**
 * Stacks a few blocks under the feet and stays up there.
 *
 * <p>{@link PlaceBlockGoal} pillars one block and hands the body back; a body one block up is a body a
 * zombie can still reach. Three is out of reach of everything that walks, and the staying is the other
 * half: a tower climbed and stepped straight off is a tower for nothing. So this holds the body still at
 * the top for a while, and the tactics table — asked again every second — is what decides whether the
 * holding goes on.
 */
public final class TowerGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** Blocks high. Two is a zombie's reach; three is not. */
    public static final int HEIGHT = 3;
    /** How long to stand on top once it is built before the move counts as over. */
    private static final int HOLD_TICKS = 1200;
    /** Building that has not got the blocks down in this long is not going to. */
    private static final int BUILD_TICKS = 200;
    /** Refused clicks in a row before the stack is given up on. */
    private static final int REFUSALS_ALLOWED = 8;

    private final int height;
    private final Reserve reserve;

    private int placed;
    private int refusals;
    private int ticksBuilding;
    private int ticksHeld;
    private boolean done;
    private final Advance advance = new Advance();

    public TowerGoal(int height, Reserve reserve) {
        this.height = height;
        this.reserve = reserve == null ? Reserve.none() : reserve;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return !done && (placed >= height || PlaceBlockGoal.hotbarSlotWithBuildingBlock(body.player(), reserve) >= 0);
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !done;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    /** Refused clicks while building; standing on top is the point, not a stall. */
    @Override
    public int stalledTicks() {
        return advance.stalledTicks();
    }

    @Override
    public void start(MobBody body) {
        advance.reset();
    }

    @Override
    public void tick(MobBody body) {
        if (placed >= height) {
            // Built. Stand on it: any step is a step off.
            body.moveControl().stop();
            if (++ticksHeld >= HOLD_TICKS) {
                done = true;
            }
            return;
        }
        if (++ticksBuilding > BUILD_TICKS) {
            done = true;
            return;
        }
        int slot = PlaceBlockGoal.hotbarSlotWithBuildingBlock(body.player(), reserve);
        if (slot < 0) {
            done = true;
            return;
        }
        body.player().getInventory().setSelectedSlot(slot);
        switch (Pillar.tick(body)) {
            case PLACED -> {
                placed++;
                refusals = 0;
                advance.progress();
            }
            case NO_SUPPORT -> done = true;
            case REFUSED -> {
                advance.nothing();
                if (++refusals >= REFUSALS_ALLOWED) {
                    done = true;
                }
            }
            case RISING -> {
                // Mid-jump: nothing to count yet.
            }
        }
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
    }

    @Override
    public String name() {
        return "Tower(" + placed + "/" + height + ")";
    }
}
