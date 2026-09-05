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
