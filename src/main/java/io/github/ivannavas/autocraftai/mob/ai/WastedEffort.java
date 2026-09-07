package io.github.ivannavas.autocraftai.mob.ai;

/**
 * Ticks the body has spent swinging at a block that whatever it is holding cannot harvest.
 *
 * <p>Punching stone is the case this exists for. It looks exactly like mining — the animation plays, the
 * block cracks, the block eventually breaks — and it produces nothing at all, so from the reward's point of
 * view it is indistinguishable from standing still except that it takes longer. A body with no pickaxe
 * cannot learn that on its own: the absence of a reward it never had is not a signal, and "mine the stone"
 * and "mine the stone with the wrong thing in your hand" are the same move to the goal table. So the cost
 * is charged explicitly, and {@link io.github.ivannavas.autocraftai.mob.ai.objective.GeneralObjectives}
 * turns it into a reward the tables can act on.
 *
 * <p>Counted where it happens — in the goals, once per swing that lands — and read by the brain once a
 * step, which is the same route {@link CraftLog} takes for the same reason: the thing that knows is not
 * the thing that scores.
 *
 * <p>Everything here happens on the client thread, so the counter is a plain int. It is a tally of a
 * moment, not a record of the run: draining it is how it is read, and a body that dies or leaves throws it
 * away rather than carrying it into the next episode.
 */
public final class WastedEffort {

    private static final WastedEffort INSTANCE = new WastedEffort();

    private int ticks;

    private WastedEffort() {
    }

    public static WastedEffort get() {
        return INSTANCE;
    }

    /** One tick of swinging at something the held item will not get a drop out of. */
    public void wastedSwing() {
        ticks++;
    }

    /** How much has been wasted since this was last asked, and start counting again. */
    public int drain() {
        int spent = ticks;
        ticks = 0;
        return spent;
    }

    public void clear() {
        ticks = 0;
    }
}
