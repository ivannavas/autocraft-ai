package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import io.github.ivannavas.autocraftai.mob.ai.objective.planner.ClaudePlanner;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.ObjectivePlanner;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.Situation;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the run is after, and what that is worth.
 *
 * <p>One objective at a time. Reaching it pays a lump sum on top of whatever it was paying along the way,
 * and that lump is what makes the difference between an aimless body and one with a direction: it is the
 * only reward large enough to be worth a long detour for.
 *
 * <h2>The objectives are decided as the run goes</h2>
 * There is no fixed list any more. When the run has no objective — at the start, and every time one is
 * reached — the {@link ObjectivePlanner} is asked for the next one, and it is asked with the situation the
 * body is actually in: the biome, the bag, the time of day, what the run has already got hold of. That is
 * the whole point of asking rather than scripting. A script cannot know it is midnight in a cave with two
 * hearts left, and so it asks for stone anyway; and a script that only knows how to want <em>things</em>
 * cannot tell a body standing in a desert that the problem is the desert.
 *
 * <p>Asking goes over the network and the game thread cannot wait for it, so between the question and the
 * answer the run has no objective and says so: {@link #stateKey()} reads {@code PLANNING}, nothing is
 * wanted from the landscape, and the tables get a state of their own for being between orders. It lasts a
 * few seconds.
 *
 * <h2>And a ladder underneath, for when nobody answers</h2>
 * With no key, no network, or a reply that will not parse, the planner stops claiming an answer is coming
 * and the run climbs {@link Rung} instead — the fixed wood-and-stone opening this used to be. The cursor
 * into it only moves when one of its own rungs is reached, so a planner that comes back later finds the
 * ladder where it left it.
 *
 * <h2>And an objective that drags on gets looked at again</h2>
 * An objective is a guess about what is worth doing, made from a situation that has since moved on. Most
 * of them are fine; the ones that are not tend to be spectacular. A body that falls into a hole while it is
 * after wood will stay after wood at the bottom of that hole for ever, because there is no tree down there
 * and nothing about "get three logs" says anything about climbing out.
 *
 * <p>So after {@link #REVIEW_AFTER_STEPS} on the same objective the planner is asked again — this time
 * with the objective it set and the last few moves the body made, which is where a rut is visible — and it
 * either replaces the objective or says to keep it. Not more often than that: it is a call over the
 * network, and one every couple of minutes is a diagnosis, while one every ten seconds is a nervous tic.
 *
 * <p>Completion is read from what the run has obtained rather than remembered, so a session that starts
 * with wood already in the bag climbs straight past the objectives it has covered — several at once, which
 * is why advancing is a loop and not an if.
 */
@Slf4j
public final class Progression {

    /** Paid once, on reaching an objective. Deliberately large: this is the point of the whole run. */
    private static final double ADVANCE_BONUS = 25.0;

    /** Key used while the planner is being waited on. */
    private static final String PLANNING = "PLANNING";

    /** Key used when there is nothing left to want and nobody left to ask. */
    private static final String FINISHED = "DONE";

    /**
     * How long an objective may run before the planner is asked whether it is still the right one.
     *
     * <p>Two minutes. Long enough that an objective which is simply taking a while — crossing a desert to
     * find trees — is left to get on with it, and short enough that a body which has fallen down a hole is
     * not still down there when the session ends.
     */
    private static final int REVIEW_AFTER_STEPS = 120;

    private final ObjectivePlanner planner;
    private final List<Phase> fallback;
    private final List<String> achieved = new ArrayList<>();

    private Phase current;
    private int fallbackIndex;
    private int stepsOnCurrent;

    public Progression(ObjectivePlanner planner, List<Phase> fallback) {
        this.planner = planner;
        this.fallback = List.copyOf(fallback);
    }

    /** Objectives from Claude, with the fixed ladder underneath for when it cannot be reached. */
    public static Progression planned(Path directory) {
        return new Progression(ClaudePlanner.create(directory), Rung.ladder());
    }

    /** The ladder and nothing else, which is what a run with nobody to ask gets. */
    public static Progression standard() {
        return new Progression(ObjectivePlanner.none(), Rung.ladder());
    }

    /** Whether there is nothing left to want and nobody left to ask for more. */
    public boolean isFinished() {
        return current == null && !planner.pending() && fallbackIndex >= fallback.size();
    }

    /** What the run is after, or empty while it is between orders. */
    public Optional<Phase> current() {
        return Optional.ofNullable(current);
    }

    /**
     * Which objective we are on, as it appears in the observation key. It belongs in the state because the
     * right move depends on it: the same tree is worth chopping while the run is after wood and worth
     * walking past while it is after stone.
     */
    public String stateKey() {
        if (current != null) {
            return current.name();
        }
        return planner.pending() ? PLANNING : FINISHED;
    }

    /** Why the run is after this, in a sentence, or empty when nobody said. For the overlay only. */
    public String reason() {
        return current == null ? "" : current.reason();
    }

    /** What the current objective wants noticed in the landscape, if anything. */
    public Optional<Predicate<BlockState>> wanted() {
        return current().flatMap(Phase::wanted);
    }

    /**
     * What to break this block with: what the objective said, or what the game says when it said nothing.
     *
     * <p>Never empty, so no caller has to decide what to do without an answer — the worst case is
     * {@link Tool#HAND}, which is what the body was doing anyway.
     */
    public Tool toolFor(BlockState state) {
        return current().flatMap(phase -> phase.toolFor(state)).orElseGet(() -> Tool.bestFor(state));
    }

    /** What the step was worth towards the current objective. */
    public double score(StepContext context) {
        return current == null ? 0.0 : current.score(context);
    }

    /**
     * Banks every objective the inventory says is done, takes the next one, and returns what that earned.
     *
     * <p>Taking and completing are the same loop because they feed each other: an objective adopted here
     * may already be satisfied — the planner asked for three logs and the run has five — and that is a
     * completion like any other, not a state to sit in until the next step notices.
     *
     * @return the lump sum for every objective reached on this step, or zero if none were
     */
    public double advanceIfComplete(StepContext context) {
        double bonus = 0.0;
        while (true) {
            adopt(context);
            if (current == null || !current.isComplete(context)) {
                break;
            }
            log.info("Reached {}", current.name());
            achieved.add(current.name());
            if (fallbackIndex < fallback.size() && current == fallback.get(fallbackIndex)) {
                fallbackIndex++;
            }
            current = null;
            stepsOnCurrent = 0;
            bonus += ADVANCE_BONUS;
        }
        reviewIfStale(context);
        return bonus;
    }

    /**
     * Asks the planner whether an objective that has gone on a long time is still the right one.
     *
     * <p>The clock is reset by the asking rather than by the answer, so a planner that says to keep going
     * is not asked again until the next whole window has passed. Without that, an objective it had just
     * approved would be put to it again on the very next decision.
     */
    private void reviewIfStale(StepContext context) {
        if (current == null) {
            return;
        }
        stepsOnCurrent += Math.max(1, context.steps());
        if (stepsOnCurrent < REVIEW_AFTER_STEPS) {
            return;
        }
        stepsOnCurrent = 0;
        log.info("{} is taking a while; asking the planner to look at it", current.name());
        String objective = current.toString();
        planner.consider(() ->
                Situation.of(context.player(), context.obtained(), achieved, objective));
    }

    /**
     * Finds the run something to do: the planner's answer if it has arrived, a question if it has not been
     * asked, and the next rung of the ladder if no answer is coming at all.
     *
     * <p>Bounded, which is what lets the caller loop on it: the planner hands over at most one answer per
     * question and asks at most one question at a time, and the ladder is finite.
     */
    private void adopt(StepContext context) {
        // Taken before the early return, because an answer may be a review's: the planner was asked about
        // the objective in hand and came back with a different one, and that is meant to take effect.
        Optional<Phase> planned = planner.take();
        if (planned.isPresent()) {
            if (current != null) {
                log.info("Planner swapped {} for {}", current.name(), planned.get().name());
            }
            current = planned.get();
            stepsOnCurrent = 0;
            return;
        }
        if (current != null) {
            return;
        }
        // A supplier rather than a situation: reading the world costs an inventory walk and an entity
        // query, and there is no sense paying for either when the planner is going to ignore the question.
        planner.consider(() -> Situation.of(context.player(), context.obtained(), achieved, ""));
        // Still nothing coming means nobody is going to answer, so climb the ladder rather than stand
        // about waiting for a reply that was never sent.
        if (!planner.pending()) {
            current = fallbackIndex < fallback.size() ? fallback.get(fallbackIndex) : null;
            stepsOnCurrent = 0;
        }
    }

    /** Lets go of the planner's thread. Called on the way out of the game. */
    public void close() {
        planner.close();
    }
}
