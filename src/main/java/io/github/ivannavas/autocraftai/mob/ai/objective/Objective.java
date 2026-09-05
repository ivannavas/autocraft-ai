package io.github.ivannavas.autocraftai.mob.ai.objective;

/**
 * One thing the brain is trying to achieve, expressed as a reward for the step just taken.
 *
 * <p>The total reward is the sum over the objectives in play: the general ones, which apply for the whole
 * run, plus whichever {@link Phase} is current. Splitting them this way is what makes the aim editable —
 * changing what the body wants is adding or reweighting an objective, not rewriting the brain.
 */
public interface Objective {

    String name();

    /** What the step was worth against this objective. */
    double score(StepContext context);
}
