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
    /** Per block covered, so that going nowhere is never the safe answer. */
    private static final double EXPLORATION_WEIGHT = 0.05;
    /** Cap on the distance that pays, so one lucky fall does not swamp a run of good choices. */
    private static final double EXPLORATION_CAP = 6.0;
    /** Charged every decision, so dithering costs something even when nothing else happens. */
    private static final double IDLE_COST = 0.05;

    private static final List<Objective> ALL = List.of(
            objective("survival", context -> context.healthDelta() * HEALTH_WEIGHT),
            objective("exploration",
                    context -> Math.min(context.distanceCovered(), EXPLORATION_CAP) * EXPLORATION_WEIGHT),
            objective("impatience", context -> -IDLE_COST));

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
