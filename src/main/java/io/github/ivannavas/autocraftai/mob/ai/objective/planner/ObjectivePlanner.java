package io.github.ivannavas.autocraftai.mob.ai.objective.planner;

import java.util.Optional;
import java.util.function.Supplier;

import io.github.ivannavas.autocraftai.mob.ai.objective.Plan;

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
     *
     * <p>A question that is merely too soon after the last is not dropped but owed: {@link #pending()}
     * stays true and a later call makes it. The run has no other source of objectives, so "not now" must
     * never be mistaken for "never".
     */
    void consider(Supplier<Situation> situation);

    /** The answer to the last request, if one has arrived. Handed over once and then forgotten. */
    Optional<Plan> take();

    /** Whether an answer is still expected, so a caller can tell "not yet" from "never". */
    boolean pending();

    /**
     * Why no answer is coming, while that is the case: no key, a failed request, a reply that was not an
     * objective. Empty while a question is out or could be put. Shown on the overlay, because a run with
     * no objective and no word on why looks like a run that has hung.
     */
    default Optional<String> trouble() {
        return Optional.empty();
    }

    /**
     * Forgets the run so far, for a body that has just arrived in a world.
     *
     * <p>A planner remembers what it asked for and why, and that memory is what keeps it from going round
     * in circles within a run. Across runs it is a liability: a new world starts with an empty bag and no
     * objectives behind it, and a planner still holding the last world's transcript would reason from a
     * history that never happened here. Any answer still on its way from the old world is dropped rather
     * than adopted by the new one.
     */
    /**
     * The next objective the last answer queued behind itself, if there is one. Asked only when the body
     * has nothing to do, so a chain is worked through in order rather than skipped to the end.
     */
    default Optional<Plan> takeQueued() {
        return Optional.empty();
    }

    /** Throws away objectives queued behind the one in hand: a death, a new world, a fresh question. */
    default void forgetQueued() {
    }

    default void reset() {
    }

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
            public Optional<Plan> take() {
                return Optional.empty();
            }

            @Override
            public boolean pending() {
                return false;
            }

            @Override
            public Optional<String> trouble() {
                return Optional.of("no API key");
            }
        };
    }
}
