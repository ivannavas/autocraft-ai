package io.github.ivannavas.autocraftai.mob.ai.objective.mentor;

/**
 * One thing the mentor taught the local policy: in the stuck state, this move is worth this much.
 *
 * <p>A positive value on the escape move and a negative one on the moves that kept failing is the whole of
 * how a block is resolved and remembered — the numbers go straight into the goal table, so the next time
 * the body is in that state it already knows the answer and no agent is called. See
 * {@link io.github.ivannavas.autocraftai.mob.ai.QTable#seed}.
 *
 * @param action the {@link io.github.ivannavas.autocraftai.mob.ai.GoalAction} name
 * @param value  the Q-value to plant; large and positive to prefer, negative to avoid
 */
public record Lesson(String action, double value) {
}
