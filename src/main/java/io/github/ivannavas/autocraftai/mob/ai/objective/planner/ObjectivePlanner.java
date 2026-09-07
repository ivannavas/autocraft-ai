package io.github.ivannavas.autocraftai.mob.ai.objective.planner;

import java.util.Optional;
import java.util.function.Supplier;

import io.github.ivannavas.autocraftai.mob.ai.objective.Phase;

/**
 * Whatever decides what the run should go after next.
 *
 * <p>Asking and answering are two calls rather than one, and that is the whole reason this is an interface
 * and not a method. The only planner worth having goes over the network, and the client thread cannot wait
 * on a network round trip — a second of stall is twenty ticks the body did not move. So
 * {@link #consider(Supplier)} starts a request and returns immediately, and {@link #take()} is polled
 * afterwards until the answer turns up. Between the two the run has no objective and says so.
 *
 * <p>The situation arrives as a supplier because building one reads the world, and a planner that is not
 * going to ask — because it has no key, or because it is already waiting on an answer — should not make
 * the game pay for a question nobody put.
 */
public interface ObjectivePlanner {

    /**
     * Sets a request going, unless one is already in flight or the last one failed too recently to be
     * worth repeating. Never blocks. The supplier is called on the caller's thread, and only if the
     * request is actually going to be made.
     */
    void consider(Supplier<Situation> situation);

    /** The answer to the last request, if one has arrived. Handed over once and then forgotten. */
    Optional<Phase> take();

    /** Whether an answer is still expected, so a caller can tell "not yet" from "never". */
    boolean pending();

    /** Stops whatever is running. Called on the way out of the game. */
    default void close() {
    }

    /** A planner with no opinions, for a run with no key to ask with. */
    static ObjectivePlanner none() {
        return new ObjectivePlanner() {
            @Override
            public void consider(Supplier<Situation> situation) {
            }

            @Override
            public Optional<Phase> take() {
                return Optional.empty();
            }

            @Override
            public boolean pending() {
                return false;
            }
        };
    }
}
