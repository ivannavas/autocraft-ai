package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Locale;

/**
 * The heights the plan says are the right ones to be at.
 *
 * <p>Everything the planner could say until now was about what to have or where to get to, and neither
 * says anything about where to <em>stay</em>. Told to fetch cobblestone the body will dig to bedrock;
 * told to find a forest it will happily do it from the bottom of a ravine. Both are the objective being
 * met by a route nobody would have chosen, and the objective alone has no way to rule it out.
 *
 * <p>So the plan carries a band as well as an aim: fetch stone, and be between forty and seventy while you
 * do it. Outside it costs, per block and per second, which leaves the body free to dip out of the band
 * when there is a reason and expensive to live there.
 *
 * <p>{@link #anywhere()} is the default and means what it says. A plan that plainly does not care —
 * crossing a continent to find trees — should not have a band invented for it, and a body charged for
 * being at the wrong height by a rule nobody wrote would be learning something untrue.
 */
public record Bounds(int floor, int ceiling) {

    /** Below the world and above the clouds: the band that rules nothing out. */
    private static final int OPEN_FLOOR = -64;
    private static final int OPEN_CEILING = 320;

    /** Per block outside the band, per second. A few blocks out for a few seconds is a real cost. */
    private static final double PER_BLOCK_PER_SECOND = 0.15;
    /**
     * The most it can charge in one second.
     *
     * <p>Set so that a whole commitment spent at the bottom of a ravine costs less than dying does. Falling
     * into one is an accident, not a habit, and a body that had learned to be frightened of every edge
     * would be worse at the game than one that occasionally falls.
     */
    private static final double MOST_PER_SECOND = 1.5;
    /**
     * Per block of distance closed towards the band. Above the per-block charge for standing outside it,
     * so a body that spends a second climbing one block is better off than one that stands still at the
     * wrong height, and well below what a resource pays, so the climb never becomes the point.
     */
    private static final double CLOSED_PER_BLOCK = 0.35;

    public Bounds {
        floor = Math.max(OPEN_FLOOR, Math.min(OPEN_CEILING, floor));
        ceiling = Math.max(OPEN_FLOOR, Math.min(OPEN_CEILING, ceiling));
        if (ceiling < floor) {
            int swap = floor;
            floor = ceiling;
            ceiling = swap;
        }
    }

    /** No opinion about height at all. */
    public static Bounds anywhere() {
        return new Bounds(OPEN_FLOOR, OPEN_CEILING);
    }

    /** Whether this band actually rules anything out. */
    public boolean bind() {
        return floor > OPEN_FLOOR || ceiling < OPEN_CEILING;
    }

    public boolean contains(int y) {
        return y >= floor && y <= ceiling;
    }

    /** How far outside the band a height is, in blocks. Zero when it is inside. */
    public int outside(int y) {
        if (y < floor) {
            return floor - y;
        }
        return y > ceiling ? y - ceiling : 0;
    }

    /** What being at this height for this long is worth, which is never more than nothing. */
    public double charge(int y, int seconds) {
        if (!bind()) {
            return 0.0;
        }
        double perSecond = Math.min(MOST_PER_SECOND, outside(y) * PER_BLOCK_PER_SECOND);
        return -perSecond * Math.max(1, seconds);
    }

    /**
     * What a step that moved towards the band was worth: the blocks of distance it closed, priced a
     * little above the second it cost.
     *
     * <p>Being outside the band was charged for and getting into it paid nothing, so a climb earned the
     * body exactly the seconds it took, and the goal table learned what that means: on one savanna run
     * REACH_BAND sat between -2 and -9 in every row of the food folder, chosen thirty-eight times, cut
     * short seventeen, and never once worth it. The charge said "not here"; nothing said "this way".
     */
    public double closed(int before, int after) {
        if (!bind()) {
            return 0.0;
        }
        return CLOSED_PER_BLOCK * (outside(before) - outside(after));
    }

    /** The nearest height inside the band, which is where a body outside it should be heading. */
    public int nearestEdge(int y) {
        if (y < floor) {
            return floor;
        }
        return y > ceiling ? ceiling : y;
    }

    /** Where a height sits relative to the band, in a word, for the state key. */
    public String where(int y) {
        if (!bind()) {
            return "ANY";
        }
        if (y < floor) {
            return "BELOW";
        }
        return y > ceiling ? "ABOVE" : "IN";
    }

    @Override
    public String toString() {
        return bind() ? String.format(Locale.ROOT, "y %d..%d", floor, ceiling) : "any height";
    }
}
