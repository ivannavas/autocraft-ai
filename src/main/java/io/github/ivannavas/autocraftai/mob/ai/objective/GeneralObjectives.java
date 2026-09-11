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
     * Per second of blows landing on the block the plan is after, with the tool that drops it.
     *
     * <p>Small on purpose — half a second of standing about — because the drop itself is the prize and
     * this is not to be mistaken for it. It is there so that a move which got to the block and started
     * on it is worth more than one that got nowhere near, which the tables could not tell apart: the
     * block takes a second or two to come apart, the move was cut or rotated away before it did, and in
     * the row for "stone in view, pickaxe in hand" every move sat below zero after a hundred visits.
     * Nothing about a row like that says "mine"; the least bad move wins, then the next.
     */
    private static final double HITTING_WEIGHT = 0.5;
    /**
     * Per second the move spent with its goal getting nowhere at all.
     *
     * <p>Impatience already charges for the passage of time, and deliberately charges very little: a
     * second spent walking to a tree is a second the run had to spend. This is the other kind of second —
     * the block finished breaking and the body kept swinging at the hole, the wall did not move however
     * hard it was leant on, the spot could not be reached from here. Nothing about the bag or the ground
     * covered tells those apart from ordinary slow going, so the goal that is doing it says so itself and
     * this is what saying so costs. See {@link io.github.ivannavas.autocraftai.mob.MobGoal#stalledTicks()}.
     *
     * <p>A quarter of what swinging at stone bare-handed costs, and twenty times what dithering does.
     * Standing about is not as bad as working hard at something that produces nothing — it does not even
     * blunt a tool — but it is the commonest way a run wastes a minute, and it has to be worth more than
     * the rounding error a per-decision charge amounts to. Three seconds of it costs about what a log
     * pays, which is the trade being offered: get on with something or hand the decision back.
     */
    private static final double STALL_COST = 1.0;
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
            // Per second like the two around it, and the seconds are counted by the goal rather than by
            // the clock: a move held for ten that spent three of them stuck is charged for three.
            objective("standing about", context -> -STALL_COST * context.stalledSteps()),
            // Already a per-second quantity, since it is counted in ticks as they pass.
            objective("wasted effort", context -> -WASTED_EFFORT_COST * context.wastedSeconds()),
            objective("hitting", context -> HITTING_WEIGHT * context.usefulSeconds()),
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
