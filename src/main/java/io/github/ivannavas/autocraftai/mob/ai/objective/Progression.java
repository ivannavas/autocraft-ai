package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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

    /**
     * Paid once for getting somewhere that has what the plan needs, at a height the plan wants.
     *
     * <p>Worth about two logs: enough that crossing a valley to the right forest beats wandering, and not
     * so much that finding the same forest twice is a living.
     */
    private static final double ARRIVAL_BONUS = 8.0;

    /** Key used while the planner is being waited on. */
    private static final String PLANNING = "PLANNING";

    /** Key used when there is nothing left to want and nobody left to ask. */
    private static final String FINISHED = "DONE";

    /**
     * How long an objective may run before the planner is asked whether it is still the right one.
     *
     * <p>Five minutes. Long enough that an objective which is simply taking a while — crossing a desert to
     * find trees — is left to get on with it, and short enough that a body which has fallen down a hole is
     * not still down there when the session ends.
     */
    private static final int REVIEW_AFTER_STEPS = 520;

    /**
     * How long a body that has stopped moving at all waits before the planner is asked about it.
     *
     * <p>Much shorter than the ordinary window, because it is a different question. An objective that is
     * taking a while might still be working; a body that has not moved in a minute is not working on
     * anything, and the plan it is failing at is usually the reason. This is the hole case: the objective
     * is fine and unreachable from where the body is, and nothing about pursuing it says "climb out".
     */
    private static final int REVIEW_WHEN_STUCK_STEPS = 60;

    private final ObjectivePlanner planner;
    private final List<Phase> fallback;
    private final List<String> achieved = new ArrayList<>();

    /**
     * What a broken reserve costs, as a multiple of what the thing itself is worth.
     *
     * <p>The mask is what actually stops a reserved item being spent, so this is for the ways round it the
     * mask cannot see: an item dropped, burnt, or eaten by a craft the recipe book counts differently from
     * the plan. Priced at several times the item so that no chain of small gains adds up to a reason.
     */
    private static final double RESERVE_WEIGHT = 3.0;

    private Phase current;
    private Bounds bounds = Bounds.anywhere();
    private Map<Resource, Integer> needs = Map.of();
    private Reserve reserved = Reserve.none();
    private boolean wasSomewhereUseful;
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

    /**
     * The heights the plan says to stay between, or {@link Bounds#anywhere()} when it did not say.
     *
     * <p>Set with the objective and cleared with it: a band belongs to a plan, and the band that made
     * sense while sinking a shaft is nonsense the moment the plan is to find a forest.
     */
    public Bounds bounds() {
        return bounds;
    }

    /**
     * Everything the run has to be holding for the plan to come off, and how much of each.
     *
     * <p>What the crafting table is keyed by, and what the plan charges for losing.
     */
    public Map<Resource, Integer> needs() {
        return needs;
    }

    /**
     * What the plan will not let the body spend.
     *
     * <p>Read by the legality masks rather than by the rewards: this is the half of the plan that is not
     * up for negotiation. See {@link Reserve}.
     */
    public Reserve reserved() {
        return reserved;
    }

    /**
     * Whether seeing one of the blocks the plan is after settles what to do about it.
     *
     * <p>True while gathering and false otherwise. See {@link Phase#minesWhatItSees()} for why this is a
     * rule rather than something the goal table is left to work out.
     */
    public boolean minesWhatItSees() {
        return current != null && current.minesWhatItSees();
    }

    /**
     * The height the body ought to be heading for, or empty when it is already where it should be.
     *
     * <p>Two sources, in order. An objective that is <em>about</em> a height names one outright; otherwise
     * it is the nearest edge of the band, when there is a band and the body is outside it. Both come out
     * as one number so the move that answers them does not have to know which it was.
     */
    public OptionalInt heightWanted(int y) {
        if (current != null) {
            OptionalInt named = current.height();
            if (named.isPresent()) {
                return named.getAsInt() == y ? OptionalInt.empty() : named;
            }
        }
        return bounds.bind() && !bounds.contains(y)
                ? OptionalInt.of(bounds.nearestEdge(y)) : OptionalInt.empty();
    }

    /**
     * Drops the plan, so the next decision asks for a new one.
     *
     * <p>Called when an episode ends. A body that has just died is standing somewhere else with an empty
     * bag, and the objective it was pursuing was chosen for a situation that no longer exists — carrying it
     * over means the run spends its first minutes back working towards a plan made for a corpse.
     */
    public void restart() {
        current = null;
        wasSomewhereUseful = false;
        bounds = Bounds.anywhere();
        needs = Map.of();
        reserved = Reserve.none();
        stepsOnCurrent = 0;
    }

    /**
     * Whether sinking a shaft is a route to what the plan wants, or a way of leaving it behind.
     *
     * <p>This is a rule and not a lesson, and it is a rule because of what the mistake costs. Digging down
     * while after wood pays nothing, which the tables could in principle learn — but by the time they have
     * had the experience the body is at the bottom of a hole where nothing else works either, and the only
     * move that still does anything is the one that made it. A body cannot learn its way out of a place
     * its learning cannot reach.
     *
     * <p>Down is a route when what the plan wants is down there, or when the plan wants the body lower
     * than it is. Otherwise it is not offered.
     */
    public boolean worthDigging(int y) {
        return current != null && (current.wantsDepth() || y > bounds.ceiling());
    }

    /** Which way the run is pulling, in a word, for the tables that only need that much. */
    public String shape() {
        return current == null ? "NONE" : current.shape();
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

    /**
     * What the step was worth towards the plan: the objective, less whatever being at the wrong height
     * cost.
     *
     * <p>The charge is here rather than among the general objectives because the band is not a general
     * truth about Minecraft — it is this plan's opinion about where this objective should be pursued, and
     * it goes away when the plan does.
     */
    public double score(StepContext context) {
        double total = (current == null ? 0.0 : current.score(context))
                + shoppingList(context) + reserveBroken(context);

        // Height is the only part that needs a body to read it off. With no body the band cannot be
        // charged for, and "at the right height" is true exactly when there is no band to be at odds with.
        OptionalInt y = context.player() == null
                ? OptionalInt.empty() : OptionalInt.of(context.player().getBlockY());
        total += arrived(context, y.isPresent() ? bounds.contains(y.getAsInt()) : !bounds.bind());
        if (y.isPresent()) {
            total += bounds.charge(y.getAsInt(), context.steps());
        }
        return total;
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
        int window = context.pinned() ? REVIEW_WHEN_STUCK_STEPS : REVIEW_AFTER_STEPS;
        if (stepsOnCurrent < window) {
            return;
        }
        stepsOnCurrent = 0;
        log.info("{} has {}; asking the planner to look at it", current.name(),
                context.pinned() ? "the body stuck in one place" : "been going a while");
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
        Optional<Plan> planned = planner.take();
        if (planned.isPresent()) {
            if (current != null) {
                log.info("Planner swapped {} for {}", current.name(), planned.get().objective().name());
            }
            current = planned.get().objective();
            bounds = planned.get().bounds();
            needs = planned.get().needs();
            reserved = planned.get().reserved();
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
            // The ladder has no opinion about height: it was written before there was a way to have one,
            // and inventing a band for it would be charging the body against a rule nobody set. Its rungs
            // do know what they take, though, which is what the crafting table keys on.
            bounds = Bounds.anywhere();
            needs = current == null ? Map.of() : current.needs();
            // No planner means nothing put aside on purpose, but a rung is a Gather like any other and
            // still holds back what it is gathering: the two sources of objectives have to behave the
            // same or the run learns different lessons depending on whether the network was up.
            reserved = current == null ? Reserve.none() : Reserve.fromCrafts(current.reserved());
            stepsOnCurrent = 0;
        }
    }

    /**
     * What the rest of the shopping list did over the step: paid for what turned up, charged for what left.
     *
     * <p>The same arithmetic a gathering objective applies to its own resource, applied to everything else
     * the plan is going to need. It is what makes putting a needed block into a wall cost something, and
     * crafting one away too — neither of which the objective's own scoring can see, because neither is
     * about the thing the objective is named after.
     *
     * <p>What the objective already scores for itself is left out, so nothing is counted twice and a
     * {@link Build} is not charged for the very blocks it is trying to put down.
     */
    private double shoppingList(StepContext context) {
        if (needs.isEmpty()) {
            return 0.0;
        }
        Resource own = current == null ? null : current.scores().orElse(null);
        double total = 0.0;
        for (Resource needed : needs.keySet()) {
            if (needed != own) {
                total += context.netChange(needed) * needed.worth();
            }
        }
        return total;
    }

    /**
     * What eating into the reserve costs.
     *
     * <p>Only the part of a loss that falls below the line is charged. Spending the fourth of four logs
     * when three were being held back costs nothing here — that log was spare and spending it was allowed.
     * Spending the third is what this is about.
     *
     * <h2>One direction only</h2>
     * It charges for going down and pays nothing for coming back up, and the asymmetry is the point: a
     * reserve is a prohibition, not a bounty. Obeying it is the baseline rather than an achievement.
     *
     * <p>Paying both ways looked symmetrical and was a hole. A reserve does not have to be about something
     * the body is carrying — the usual case is the opposite, ten obsidian put aside before a single one
     * has been found — and a body starting ten short would have collected the reserve's whole weight for
     * every one it picked up, on top of what the thing is worth and what the shopping list already pays.
     * Three times the worth of an obsidian is more than finishing an objective pays. Gathering is already
     * rewarded for being gathering; this term has no business paying for it twice.
     *
     * <p>Something both needed and reserved is charged twice on a loss, once by each, and that is meant:
     * the plan is making two different statements about it and both of them are true.
     */
    private double reserveBroken(StepContext context) {
        if (reserved.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (Resource resource : reserved.kept().keySet()) {
            int deeper = reserved.shortfall(resource, context.after().count(resource))
                    - reserved.shortfall(resource, context.before().count(resource));
            total -= Math.max(0, deeper) * resource.worth() * RESERVE_WEIGHT;
        }
        return total;
    }

    /**
     * What being in the right place is worth, paid once on getting there.
     *
     * <p>The one thing the position table had no way to learn. Going somewhere paid only for the ground
     * covered, so every direction was worth the same and arriving was worth nothing at all — the body could
     * walk past the forest it had been sent to find and be no worse off for it. This pays for the arrival:
     * something the plan needs in view, at a height the plan approves of.
     *
     * <p>Paid on the transition rather than for every second of standing there, which is the difference
     * between a reason to travel and a reason to stare at a tree. Losing sight of it and finding it again
     * costs a walk, so the going rate for farming this is worse than the rate for doing the job.
     */
    private double arrived(StepContext context, boolean atTheRightHeight) {
        boolean somewhereUseful = context.resourceInSight() && atTheRightHeight;
        boolean paid = somewhereUseful && !wasSomewhereUseful;
        wasSomewhereUseful = somewhereUseful;
        return paid ? ARRIVAL_BONUS : 0.0;
    }

    /** Lets go of the planner's thread. Called on the way out of the game. */
    public void close() {
        planner.close();
    }
}
