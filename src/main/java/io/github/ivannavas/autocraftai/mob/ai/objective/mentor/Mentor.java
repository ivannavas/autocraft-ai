package io.github.ivannavas.autocraftai.mob.ai.objective.mentor;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Whatever gets the run unstuck by teaching it, rather than by thinking for it.
 *
 * <p>The planner decides what to do next; the mentor is asked only when the run is already trying to do
 * the right thing and cannot — pinned in a state its own table has no good answer for. It answers not with
 * an objective but with lessons the local policy keeps (see {@link Rescue}), so the block is resolved
 * once, remembered, and never sent again. Asking and answering are two calls for the same reason the
 * planner's are: the network cannot be waited on from the game thread.
 */
public interface Mentor {

    /**
     * Sets a request going, unless one is already in flight or the block was taught too recently to be
     * worth teaching again. Never blocks. The ask is built on the caller's thread.
     */
    void consider(Supplier<MentorAsk> ask);

    /** The lessons for the last block, if they have arrived. Handed over once. */
    Optional<Rescue> take();

    /** Whether an answer is still expected. */
    boolean pending();

    /**
     * How a lesson turned out, once the run has judged it: free (or nearer) after so many decisions, or
     * not. Quoted back with the next question about the same pursuit, so an answer knows what has been
     * tried and how it went, rather than only what was said about this one state.
     */
    default void judged(Rescue rescue, String verdict) {
    }

    /** Forgets which blocks it has taught, for a body that has just arrived in a fresh world. */
    default void reset() {
    }

    default void close() {
    }

    /** A mentor with nothing to teach, for a run with no key to ask with. */
    static Mentor none() {
        return new Mentor() {
            @Override
            public void consider(Supplier<MentorAsk> ask) {
            }

            @Override
            public Optional<Rescue> take() {
                return Optional.empty();
            }

            @Override
            public boolean pending() {
                return false;
            }
        };
    }
}
