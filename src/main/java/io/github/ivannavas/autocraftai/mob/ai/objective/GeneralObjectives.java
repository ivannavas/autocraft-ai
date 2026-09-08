package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.List;

/**
 * The objectives that hold for the whole run, whatever rung of the ladder the body is on.
 *
 * <p>They are deliberately weaker than the phase rewards. Staying unhurt and getting about are the
 * background conditions of a run, not the point of it: a body that maximised these alone would stand in a
 * safe field walking in circles forever.
 */
public final class GeneralObjectives {

    /** Half a heart is worth this much. Losing four hearts outweighs any single step of progress. */
    private static final double HEALTH_WEIGHT = 1.0;
    /**
     * Per block covered, so that going nowhere is never the safe answer — and no more than that. This is a
     * nudge, not an achievement: priced any higher it beats the rung it is supposed to be serving, and once
     * moves can be held for ten seconds a body that runs collects ten times the nudge for doing nothing.
     */
    private static final double EXPLORATION_WEIGHT = 0.01;
    /** Cap per second on the distance that pays, so one lucky fall does not swamp a run of good choices. */
    private static final double EXPLORATION_CAP = 4.0;
    /** Charged every decision, so dithering costs something even when nothing else happens. */
    private static final double IDLE_COST = 0.05;
    /**
     * Per second spent swinging at a block the held item cannot harvest.
     *
     * <p>Priced at exactly what a log pays, which is the point: this is the one mistake that looks like
     * progress. Standing still plainly gets nothing and costs the going rate for a wasted second; punching
     * stone bare-handed gets nothing either, but the animation plays and the block cracks and eventually
     * breaks, so nothing about it tells the tables it was a bad move. A second of it now costs what a
     * second of doing it properly would have paid.
     *
     * <p>Eighty times the cost of dithering, and a ten-second commitment spent on it costs twice what
     * dying does. That is not a slip of the pen. Dying is one bad moment; a body that has learned to mine
     * stone with its fists will do it for the rest of the run, and the cost has to be big enough that a
     * single such move outweighs whatever chain of small rewards led the table into it.
     */
    private static final double WASTED_EFFORT_COST = 4.0;
    /**
     * Per point of hunger restored. A loaf is six points, so a meal pays about what a log does.
     *
     * <p>Only gains are paid for. Hunger ticks down all day whatever the body is doing, and charging for
     * that would be charging for the passage of time twice — impatience already does it — while making
     * every long move look worse than it was.
     */
    private static final double NOURISHMENT_WEIGHT = 0.6;

    /**
     * Per tick of breath, so a whole lungful is worth about a log and a half.
     *
     * <p>Drowning damage is already priced by survival, and by the time it starts the body has about four
     * seconds to live. This is the fifteen seconds before that: a signal that gets worse the longer the
     * head stays under, so the water table has something to learn from besides the corpse. Small on
     * purpose — a run that never gets its feet wet should not be collecting anything for it.
     */
    private static final double BREATH_WEIGHT = 0.02;

    private static final List<Objective> ALL = List.of(
            objective("survival", context -> context.healthDelta() * HEALTH_WEIGHT),
            // Both are per-second quantities, so both scale with how long the move was held. Without that
            // a fifteen-second move would be charged one second of impatience and capped as if it had had
            // one second to cover ground.
            objective("exploration",
                    context -> Math.min(context.distanceCovered(), EXPLORATION_CAP * context.steps())
                            * EXPLORATION_WEIGHT),
            objective("impatience", context -> -IDLE_COST * context.steps()),
            // Already a per-second quantity, since it is counted in ticks as they pass.
            objective("wasted effort", context -> -WASTED_EFFORT_COST * context.wastedSeconds()),
            objective("nourishment", context -> context.foodGained() * NOURISHMENT_WEIGHT),
            objective("breath", context -> context.airDelta() * BREATH_WEIGHT));

    private GeneralObjectives() {
    }

    public static List<Objective> all() {
        return ALL;
    }

    private static Objective objective(String name, java.util.function.ToDoubleFunction<StepContext> score) {
        return new Objective() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public double score(StepContext context) {
                return score.applyAsDouble(context);
            }
        };
    }
}
