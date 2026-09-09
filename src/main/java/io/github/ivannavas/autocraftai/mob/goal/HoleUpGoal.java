package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Digs two blocks down, caps the hole over its head, and waits.
 *
 * <p>The oldest survival move in the game, and the one the run could not make: it had digging down and
 * it had placing a block over its head, as separate moves in separate tables, and nothing that would do
 * the one and then the other on purpose. Thirteen deaths in one night said what that was worth.
 *
 * <p>Two down rather than one, so the cap goes over the head with room to stand under it; the block
 * that comes out of the hole is what caps it, so this works with nothing in the bag wherever the ground
 * is dirt or sand. Once sealed the body stands still for a while — the point is to be somewhere nothing
 * can reach — and the tactics table, asked every second, is what decides how long that goes on.
 */
public final class HoleUpGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** How deep. Two blocks down puts the cap over the head and a block of air under it. */
    private static final int DEPTH = 2;
    /** How long to wait sealed in before the move counts as over. */
    private static final int HOLD_TICKS = 2400;
    /** A hole that is not dug and capped in this long is not going to be. */
    private static final int WORK_TICKS = 400;
    /** Blows that landed nothing before the block is called unbreakable from here. */
    private static final int STALLED_TICKS = 60;
    /** Bedrock is at -64; there is nothing to hide in below this. */
    private static final int BOTTOM = -59;

    private final Reserve reserve;

    private int dug;
    private BlockPos digging;
    private int ticksStalled;
    private int ticksWorking;
    private int ticksHeld;
    private PlaceBlockGoal cap;
    private boolean sealed;
    private boolean done;
    private final Advance advance = new Advance();

    public HoleUpGoal(Reserve reserve) {
        this.reserve = reserve == null ? Reserve.none() : reserve;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return !done && body.onGround();
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !done;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    /** Blows that land nothing while digging; the waiting at the bottom is the point, not a stall. */
    @Override
    public int stalledTicks() {
        return advance.stalledTicks();
    }

    @Override
    public void start(MobBody body) {
        advance.reset();
        digging = null;
    }

    @Override
    public void tick(MobBody body) {
        body.moveControl().stop();
        if (sealed) {
            if (++ticksHeld >= HOLD_TICKS) {
                done = true;
            }
            return;
        }
        if (++ticksWorking > WORK_TICKS) {
            done = true;
            return;
        }
        if (dug < DEPTH) {
            dig(body);
            return;
        }
        seal(body);
    }

    /** One tick of digging the block under the feet, counting the block gone when it is. */
    private void dig(MobBody body) {
        if (digging != null && body.level().getBlockState(digging).isAir()) {
            // The block that was being dug is gone. The body drops onto the next one over the next
            // few ticks, and nothing is read off the ground until it has.
            dug++;
            digging = null;
            ticksStalled = 0;
            advance.progress();
            return;
        }
        if (!body.onGround()) {
            return;
        }
        BlockPos under = standingOn(body);
        if (!safeToOpen(body, under)) {
            // Lava, a drop, or the bottom of the world underneath: not a hole to hide in.
            done = true;
            return;
        }
        if (!under.equals(digging)) {
            digging = under.immutable();
            Digging.equip(body, under);
        }
        if (Digging.strike(body, under)) {
            ticksStalled = 0;
        } else {
            advance.nothing();
            if (++ticksStalled >= STALLED_TICKS) {
                done = true;
            }
        }
    }

    /** Puts a block over the head — the hole's own dirt, usually — and calls the hole sealed. */
    private void seal(MobBody body) {
        if (cap == null) {
            if (!body.onGround()) {
                // Still dropping into the hole: where the cap goes is read off where the feet land.
                return;
            }
            Digging.stopBreaking();
            // Two down, so the cap goes where the feet were when the digging began.
            cap = new PlaceBlockGoal(body.player().blockPosition().above(DEPTH), reserve, false);
            if (!cap.canUse(body)) {
                // Nothing to cap with. Two blocks down with an open top is still better than the
                // surface, so the move ends here rather than being called a failure.
                done = true;
                return;
            }
            cap.start(body);
        }
        if (cap.isDone()) {
            sealed = true;
            advance.progress();
            return;
        }
        if (!cap.canContinueToUse(body)) {
            done = true;
            return;
        }
        cap.tick(body);
    }

    /**
     * The block the body is actually standing on: the one under the feet, or the feet's own block when
     * that is a slab or the like — a body on a bottom slab has its feet inside the slab's block, and a
     * swing at the block "under" it goes through the slab and lands nothing.
     */
    static BlockPos standingOn(MobBody body) {
        BlockPos feet = body.player().blockPosition();
        if (!body.level().getBlockState(feet).getCollisionShape(body.level(), feet).isEmpty()) {
            return feet;
        }
        return feet.below();
    }

    /** Whether breaking the block under the feet leaves ground to land on rather than a drop or lava. */
    private static boolean safeToOpen(MobBody body, BlockPos under) {
        Level level = body.level();
        if (under.getY() <= BOTTOM || !Digging.breakable(body, under)
                || !level.getFluidState(under).isEmpty()) {
            return false;
        }
        BlockPos below = under.below();
        return level.isLoaded(below) && level.getBlockState(below).isSolid()
                && level.getFluidState(below).isEmpty();
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
        Digging.stopBreaking();
        if (cap != null) {
            cap.stop(body);
        }
    }

    @Override
    public String name() {
        return sealed ? "HoleUp(sealed)" : "HoleUp(" + dug + "/" + DEPTH + ")";
    }
}
