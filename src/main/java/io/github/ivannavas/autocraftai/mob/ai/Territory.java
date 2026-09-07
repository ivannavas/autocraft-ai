package io.github.ivannavas.autocraftai.mob.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

import net.minecraft.world.phys.Vec3;

/**
 * Where the body has been lately, coarsely.
 *
 * <p>Nothing in the run remembered this, and it shows in the two ways the body wastes a run. It walks in
 * circles: every decision is made from what is in front of it, and a patch of grass looks exactly as
 * promising the fourth time as the first. And it sits in holes: from the bottom of one, every direction is
 * a wall, and a body with no memory cannot tell "I have been trying this for two minutes" from "I have
 * just arrived".
 *
 * <h2>Cells, not positions</h2>
 * Kept as an eight-block grid rather than a path. A position is never visited twice — the body drifts by
 * fractions of a block — so counting positions would say every step was new ground. What "I have been here
 * before" means at the scale a run cares about is a room-sized patch, and that is what a cell is.
 *
 * <p>Only the recent past is kept. A run that comes back to its starting forest an hour later is not going
 * in circles, it has come back, and the difference is worth preserving.
 */
public final class Territory {

    /** Eight blocks square: about a room, and about as far as the body can see clearly anyway. */
    private static final int CELL = 8;
    /** How many cells to keep counts for. Beyond this the oldest is forgotten. */
    private static final int REMEMBER_CELLS = 192;
    /** How many recent positions the trail holds — a couple of minutes of stepping. */
    private static final int TRAIL = 24;
    /**
     * Below this much of the walking actually getting anywhere, the body is going round in circles.
     *
     * <p>Straightness, not novelty. Counting how many cells of the trail were new sounds right and is a
     * measure of speed: a body plodding one block a step covers three cells in a trail and a body running
     * covers twelve, and neither of them is lost. What tells a loop from a line is how much of the walking
     * turned into distance from where it started, and that is the same number however fast you go.
     */
    private static final double STRAIGHT_ENOUGH = 0.3;
    /** Under this much walking across the whole trail, it is not going anywhere at all. */
    private static final double PINNED_WITHIN = 6.0;

    private final Map<Long, Integer> visits = new HashMap<>();
    private final Deque<Long> order = new ArrayDeque<>();
    private final Deque<Vec3> trail = new ArrayDeque<>();

    /** Notes where the body is now. Called once a step, from the game thread. */
    public void mark(Vec3 position) {
        long cell = cellOf(position);
        if (visits.merge(cell, 1, Integer::sum) == 1) {
            order.addLast(cell);
            while (order.size() > REMEMBER_CELLS) {
                visits.remove(order.removeFirst());
            }
        }
        trail.addLast(position);
        while (trail.size() > TRAIL) {
            trail.removeFirst();
        }
    }

    /** How many times the body has been in this patch lately. Zero is ground it has not covered. */
    public int visitsAt(Vec3 position) {
        return visits.getOrDefault(cellOf(position), 0);
    }

    /**
     * Whether the walking has stopped turning into progress.
     *
     * <p>How far the body ended up from where it started, against how far it actually walked. A line comes
     * out at one and a loop at nearly nothing, whatever the pace — which is the point, because a slow walk
     * is not a lost one.
     */
    public boolean circling() {
        if (trail.size() < TRAIL / 2) {
            return false;
        }
        double walked = walked();
        return walked >= PINNED_WITHIN
                && flatDistance(trail.peekFirst(), trail.peekLast()) / walked < STRAIGHT_ENOUGH;
    }

    /** Whether the body has barely walked at all across the whole trail, which is what a hole feels like. */
    public boolean pinned() {
        return trail.size() >= TRAIL / 2 && walked() < PINNED_WITHIN;
    }

    /** How far the body actually walked over the trail, which is not how far it got. */
    private double walked() {
        double total = 0.0;
        Vec3 previous = null;
        for (Vec3 position : trail) {
            if (previous != null) {
                total += flatDistance(previous, position);
            }
            previous = position;
        }
        return total;
    }

    /**
     * The way towards the least-trodden patch next door, as a yaw in radians.
     *
     * <p>Only the eight neighbours are considered. Somewhere genuinely new might be a hundred blocks off,
     * but the body cannot aim at what it has never seen; what it can do is take the next step away from
     * where it has been, and repeat.
     */
    public OptionalDouble towardsFresh(Vec3 from) {
        double best = Double.MAX_VALUE;
        double heading = 0.0;
        boolean found = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Vec3 neighbour = from.add(dx * CELL, 0.0, dz * CELL);
                int seen = visits.getOrDefault(cellOf(neighbour), 0);
                if (seen < best) {
                    best = seen;
                    heading = Math.atan2(-dx, dz);
                    found = true;
                }
            }
        }
        return found ? OptionalDouble.of(heading) : OptionalDouble.empty();
    }

    /** The way the body came, as a yaw in radians, or empty when it has not been anywhere. */
    public OptionalDouble back() {
        if (trail.size() < 2) {
            return OptionalDouble.empty();
        }
        Vec3 from = trail.peekFirst();
        Vec3 to = trail.peekLast();
        double dx = from.x - to.x;
        double dz = from.z - to.z;
        if (Math.sqrt(dx * dx + dz * dz) < 1.0E-3) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.atan2(-dx, dz));
    }

    /** Thrown away with the episode: a new life is not explained by the last one's wanderings. */
    public void clear() {
        visits.clear();
        order.clear();
        trail.clear();
    }

    /** How the trail reads, in a word, for the state key. */
    public String state() {
        if (pinned()) {
            return "PINNED";
        }
        return circling() ? "CIRCLING" : "FRESH";
    }

    private static double flatDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Both horizontal coordinates packed into one long, which is all a cell ever needs to be. */
    private static long cellOf(Vec3 position) {
        long x = Math.floorDiv((long) Math.floor(position.x), CELL);
        long z = Math.floorDiv((long) Math.floor(position.z), CELL);
        return x * 4_294_967_311L + z;
    }
}
