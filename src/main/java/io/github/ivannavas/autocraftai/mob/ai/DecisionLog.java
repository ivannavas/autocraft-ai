package io.github.ivannavas.autocraftai.mob.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The last few moves the body made and what each one earned.
 *
 * <p>This is what the planner reads when an objective is taking too long. The situation alone says where
 * the body is and what it is carrying, and neither of those explains <em>why</em> nothing is happening. A
 * run of moves does: six decisions in a row that were all "dig down" and all lost points, taken in a state
 * that says there is a wall in front, is a body at the bottom of a hole — and that is a thing a reader can
 * see and a set of inventory counts is not.
 *
 * <p>Written once a decision and never anywhere else, so the record is one line per move rather than a
 * stream of ticks. Kept short on purpose: the point is enough context to recognise a rut, and a hundred
 * lines of it would cost more to send than it could possibly be worth.
 *
 * <p>Everything happens on the client thread except the reading, which the planner does on its own thread
 * from a copy taken while the situation was built. Same arrangement as {@link CraftLog}.
 */
public final class DecisionLog {

    /** How many moves back the planner gets to see. About a minute and a half of a run. */
    private static final int KEEP = 10;

    private static final DecisionLog INSTANCE = new DecisionLog();

    private final Deque<String> recent = new ArrayDeque<>();

    private DecisionLog() {
    }

    public static DecisionLog get() {
        return INSTANCE;
    }

    /**
     * Records a move that has just ended.
     *
     * @param state    the observation the move was chosen in
     * @param action   what the body did
     * @param seconds  how long it was held for
     * @param reward   what it earned, which is the part that says whether it was working
     */
    public void record(String state, String action, int seconds, double reward) {
        if (action == null) {
            return;
        }
        recent.addLast(String.format(Locale.ROOT, "%s -> %s for %ds, earned %+.1f",
                state == null ? "?" : state, action, seconds, reward));
        while (recent.size() > KEEP) {
            recent.removeFirst();
        }
    }

    /** The moves so far, oldest first. A copy: the planner reads it from another thread. */
    public List<String> recent() {
        return List.copyOf(recent);
    }

    /** Forgotten when the episode is, so a new life is not explained by the last one's mistakes. */
    public void clear() {
        recent.clear();
    }
}
