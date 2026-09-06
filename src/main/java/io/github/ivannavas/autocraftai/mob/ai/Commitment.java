package io.github.ivannavas.autocraftai.mob.ai;

/**
 * How long the brain commits to a goal once it picks one.
 *
 * <p>This is half of every decision, and the half that carries context between them. A body that re-chooses
 * every second cannot hold a plan: it flees, the mob leaves the view cone, the situation reads as "nothing
 * here", it strolls, and the stroll walks it back into the mob. Patching that for hostiles alone would leave
 * the same hole open for every other errand — walking to a tree, digging a shaft, waiting out a fall.
 *
 * <p>Committing fixes it in general, and without a special case anywhere: the goal keeps its own target for
 * as long as it runs, whatever perception has to say meanwhile. How long is worth committing for is not
 * something to guess at either, so it is a column in the table like everything else.
 */
public enum Commitment {

    /** One decision, the way every choice used to work. */
    SHORT(1),
    MEDIUM(4),
    /**
     * Long enough to break contact with something chasing you — the flee goal wants about fourteen blocks
     * and the body covers four a second — and no longer. Every extra second here is also a second spent
     * committed to whatever exploration picked, and with eighteen columns to try that bill is paid often.
     */
    LONG(10);

    private final int steps;

    Commitment(int steps) {
        this.steps = steps;
    }

    /** Decisions the goal is held for. One step is one second. */
    public int steps() {
        return steps;
    }

    /** Short label for the overlay. */
    public String label() {
        return steps + "s";
    }
}
