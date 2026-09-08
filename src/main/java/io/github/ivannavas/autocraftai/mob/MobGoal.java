package io.github.ivannavas.autocraftai.mob;

import java.util.Set;

/**
 * One behaviour the body can run, modelled on vanilla's {@code Goal}.
 *
 * <p>The engine owns the lifecycle: it asks {@link #canUse} every tick while the goal is idle, calls
 * {@link #start} once when it takes over, {@link #tick} every tick after that for as long as
 * {@link #canContinueToUse} holds, and {@link #stop} when it lets go. A goal only ever runs while it holds
 * every control it declared, so implementations can assume nothing else is steering the body underneath
 * them.
 *
 * <p>The body is handed in on each call instead of being captured at construction, so the same goal
 * instance survives respawns and dimension changes, where the underlying player entity is replaced.
 */
public interface MobGoal {

    /** Controls this goal needs before it may run. */
    Set<MobControl> controls();

    /** Whether the goal wants to start right now. Called every tick while idle, so keep it cheap. */
    boolean canUse(MobBody body);

    /** Whether a running goal wants to keep going. Defaults to the same test used to start. */
    default boolean canContinueToUse(MobBody body) {
        return canUse(body);
    }

    /**
     * Whether a higher priority goal may take the controls away mid-run. A goal that returns {@code false}
     * keeps them until it finishes on its own.
     */
    default boolean isInterruptable() {
        return true;
    }

    /**
     * Ticks this goal has spent with nothing to show for them; zero while it is getting somewhere.
     *
     * <p>This is how a decision says it is taking too long. The brain commits to a goal for a length it
     * guessed at, and until a goal could answer this the guess was all there was — a body walled in, one
     * swinging at a block it cannot reach and one standing about with a job that finished ten seconds ago
     * all looked from outside like a body that was busy. What counts as progress is this goal's business
     * (a blow that landed, ground covered, a mouthful being eaten); how much of the absence of it is too
     * much is the brain's.
     *
     * <p>A goal that says nothing is taken to be getting somewhere for as long as it runs, which is the
     * right answer for one that stops the moment it is done.
     */
    default int stalledTicks() {
        return 0;
    }

    /**
     * Whether this goal has finished what it was for, as opposed to having stopped because it could not.
     *
     * <p>The difference the brain needs and could not see. A block placed, a mouthful eaten and a target
     * reached all leave a goal that is not running, and so did a goal that gave up — and the brain charged
     * both as seconds spent on nothing and cut the move short. A move that ends because it succeeded is
     * over the moment it succeeds, costs nothing for the seconds it did not use, and the tables get to
     * choose again at once. A goal that never finishes on its own — walking, watching — leaves this false.
     */
    default boolean isDone() {
        return false;
    }

    default void start(MobBody body) {
    }

    default void tick(MobBody body) {
    }

    default void stop(MobBody body) {
    }

    /** Name used in logs and in the goal listing. */
    default String name() {
        return getClass().getSimpleName();
    }
}
