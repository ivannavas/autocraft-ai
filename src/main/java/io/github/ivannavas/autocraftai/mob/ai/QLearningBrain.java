package io.github.ivannavas.autocraftai.mob.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobEngine;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.GeneralObjectives;
import io.github.ivannavas.autocraftai.mob.ai.objective.InventoryCensus;
import io.github.ivannavas.autocraftai.mob.ai.objective.Objective;
import io.github.ivannavas.autocraftai.mob.ai.objective.Phase;
import io.github.ivannavas.autocraftai.mob.ai.objective.Progression;
import io.github.ivannavas.autocraftai.mob.ai.objective.Pursuit;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import io.github.ivannavas.autocraftai.mob.ai.objective.mentor.ClaudeMentor;
import io.github.ivannavas.autocraftai.mob.ai.objective.mentor.Mentor;
import io.github.ivannavas.autocraftai.mob.ai.objective.mentor.MentorAsk;
import io.github.ivannavas.autocraftai.mob.ai.objective.mentor.Rescue;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.PlannerLog;
import io.github.ivannavas.autocraftai.mob.ai.objective.StepContext;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import io.github.ivannavas.autocraftai.mob.ai.objective.Travel;
import io.github.ivannavas.autocraftai.mob.goal.CraftAtTableGoal;
import io.github.ivannavas.autocraftai.mob.goal.CraftGoal;
import io.github.ivannavas.autocraftai.mob.goal.CraftingGoal;
import io.github.ivannavas.autocraftai.mob.goal.MineSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.PlaceBlockGoal;
import io.github.ivannavas.autocraftai.mob.goal.SmeltGoal;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Decides what the body should be doing and installs the goals that do it.
 *
 * <h2>Three tables, not one</h2>
 * A decision has three parts — which goal, how long to hold it, and what to be making meanwhile — and each
 * gets its own table:
 *
 * <ul>
 *   <li><b>goals</b>: which {@link GoalAction} the body commits its limbs to.</li>
 *   <li><b>timing</b>: which {@link Commitment} to hold it for, keyed by the state <em>and</em> the goal
 *       just chosen, since how long is worth fleeing for has nothing to do with how long is worth mining
 *       for.</li>
 *   <li><b>crafting</b>: which {@link CraftChoice} to work on in the background, or none — keyed by
 *       {@link CraftSituation}, not by what is in view, since what is worth making depends on the rung and
 *       the bag rather than on which block happens to be under the crosshair.</li>
 *   <li><b>placement</b>: for the two moves that act on a block, <em>which</em> block — the one in view,
 *       the one above it, the one under the feet. See {@link Spot}.</li>
 *   <li><b>position</b>: for the two moves that take the body somewhere, <em>which way</em> — onward,
 *       back, or towards ground it has not covered. See {@link Ground}.</li>
 *   <li><b>water</b>: what to do about being in water, asked once a second for as long as the body is in
 *       some — the one table that runs on its own clock rather than the commitment's. See {@link Swim},
 *       and {@link Water} for the state it is keyed by.</li>
 * </ul>
 *
 * <p>One table over the cross-product would be {@code 6 x 3 x 6 x 4} columns, and every new goal would
 * multiply the lot again. Split, a new goal is one column and a new commitment level is one column. The three learn
 * from the same reward over the same move, which does mean none of them can represent an interaction the
 * others cannot see — the price of the split, and the reason it scales.
 *
 * <h2>A folder of tables per pursuit</h2>
 * The four tables that depend on what the run is after — goals, timing, placement, position — are not one
 * file each but one file each <em>per pursuit</em>: {@code pursuits/LOG/goals.txt}, {@code pursuits/THREAT/
 * goals.txt}, and so on, opened the first time a pursuit comes up. The objective used to be the first field
 * of every key instead, and the planner inventing objectives was the end of that: every new wording was a
 * row visited once. What the body learns about wood does not depend on how much wood was asked for, so
 * the folder is the resource and the source within it is a field of the key — see {@link Pursuit}.
 * Crafting and water stay single files, because what they key on never depended on the objective.
 *
 * <p>A move is credited to the folder it was chosen from. When the next decision lands in a different
 * folder — a zombie walks into view, the plan moves on to stone — the old folder's claims are settled with
 * no continuation rather than bootstrapped from a table that knows nothing about them.
 *
 * <h2>A commitment, and the layers on top of it</h2>
 * The goal table picks something that needs the body and the timing table says how long. That is the
 * commitment, and it is the one decision the brain holds still; everything else is a layer laid over it,
 * each on its own clock and each answering a question the commitment cannot see. Crafting is chosen with
 * the commitment and runs alongside it, because a two-by-two grid needs neither legs nor eyes — which is
 * how the body can be fleeing a creeper and turning logs into planks at once. The water layer is asked
 * every second the body is wet, whatever the commitment is doing, and what it installs outranks the
 * commitment's goal, because a body that is drowning is not travelling however committed it is.
 *
 * <p>Nothing here decides who gets the legs when two of them want them. The engine does, by priority and
 * by control claim, and that is deliberate: the arbitration has one right answer in every case there is —
 * not drowning beats everything, a pickaxe is worth walking to a table for — and a table asked to learn it
 * would spend the run getting it wrong on the way to getting it right, inside every other table's move.
 * That was the interrupt table's mistake, and it is not one worth making twice. What a layer learns is
 * <em>what</em> to do about its own question; <em>whether</em> it gets the body to do it is not a matter
 * of opinion.
 *
 * <h2>When a move stops paying its way</h2>
 * A commitment is an estimate, and estimates are wrong. There used to be a table for that too — a short
 * list of rescues, asked what to do about a body that was not moving — and it was a mistake twice over.
 * It answered a question the goal table already answers, in a state the goal table already keys on
 * ({@code W} for walled in, {@code B} for carrying blocks), so the two spent the run learning the same
 * lesson separately; and it spent its own exploration inside every other table's move, so a wall could
 * turn a perfectly good mining decision into a random stroll while it was still finding its feet.
 *
 * <p>What replaces it is the goals saying so themselves. Every goal can be asked how long it has had
 * nothing to show for itself — see {@link MobGoal#stalledTicks()} — and each one measures that in the
 * terms its own job makes sense in: blows landed, ground covered, a mouthful being eaten, a journey
 * beating its own record along a bearing. A move whose goal has been getting nowhere for
 * {@link #STALL_TICKS} spends that second stalled; {@link #STALL_LIMIT_STEPS} of those and the move is cut
 * short and every table chooses again, with the goal torn down so the same choice comes back as a fresh
 * attempt.
 *
 * <p>Nobody is charged a special penalty for it. The seconds spent going nowhere are counted into the
 * step and priced by {@link GeneralObjectives}, so they reach every table through the ordinary reward —
 * and reach the timing table hardest, which is right, because the longer a move is held the more of them
 * it collects.
 */
@Slf4j
public final class QLearningBrain {

    /** Ticks in one step. Twenty is one second, and a step is the unit every commitment is counted in. */
    private static final int STEP_TICKS = 20;
    /**
     * Ticks of a goal having nothing to show for itself before that second counts as spent on nothing.
     *
     * <p>Two seconds rather than one. Every goal's own measure is coarse — a journey books its progress
     * four blocks at a time, a fight one swing at a time — and a bar set at a single second would call
     * ordinary work stalling every time the grain of the measure fell the wrong way.
     */
    private static final int STALL_TICKS = 40;
    /**
     * Seconds of a move spent on nothing before it is cut short and every table chooses again.
     *
     * <p>Counted over the whole move rather than in a row: a goal that alternates a good second with a
     * dead one is halfway to being stuck, and waiting for two dead ones to land together would let it
     * run out a ten-second commitment at half pay.
     */
    private static final int STALL_LIMIT_STEPS = 2;
    /** Priority the chosen goal is installed at. */
    private static final int GOAL_PRIORITY = 2;
    /** A two-by-two craft claims no controls, so its priority only orders it against other free goals. */
    private static final int CRAFT_PRIORITY = 5;
    /**
     * A craft at a table walks and stands, so it has to outrank the primary to get the body at all. That
     * is the engine's arbitration doing its job: a pickaxe is worth interrupting a stroll for.
     */
    private static final int CRAFT_AT_TABLE_PRIORITY = 1;
    private static final int SAVE_EVERY_DECISIONS = 100;
    private static final int REPORT_EVERY_DECISIONS = 300;
    private static final double DEATH_PENALTY = -20.0;
    /**
     * How far past its commitment a goal that refuses interruption may run before it is cut short anyway.
     *
     * <p>Long enough for a block to come apart and no longer. See {@link #stillHolding()} for what went
     * wrong without it.
     */
    private static final int COMMITTED_GRACE_STEPS = 5;
    /**
     * Above everything, including a craft at a table: not drowning outranks making a pickaxe.
     *
     * <p>The water table's answer is not a rival to the goal table's, it is a veto over it, and the way a
     * veto is expressed here is a control claim the primary cannot outrank. The moment the body is out of
     * the water the swim goal stops wanting the body and the primary gets it back, without either of them
     * having to know about the other.
     */
    private static final int SWIM_PRIORITY = 0;
    /** Ticks spent on the death screen before asking to come back. Long enough to see what killed you. */
    private static final int RESPAWN_DELAY_TICKS = 40;
    /** Where the per-pursuit folders live, under the directory the shared tables are in. */
    private static final String PURSUITS = "pursuits";
    /**
     * Above the committed goal and below the water: a wall is worth breaking through, drowning is not
     * worth anything. Level with a craft at a table, so whichever of the two has the body keeps it.
     */
    private static final int PASSAGE_PRIORITY = 1;
    /** Ticks of the committed goal getting nowhere before the terrain is asked about. One second. */
    private static final int OBSTRUCTED_TICKS = 20;
    /**
     * Per block the body got towards where it wanted to go, paid to the passage table on top of the
     * ordinary reward. What a descent pays per block, and for the same reason: a table that can only
     * see the standing costs of a second learns nothing from a second that opened the way.
     */
    private static final double PASSAGE_PROGRESS_WEIGHT = 0.6;

    private final MobEngine engine;
    private final Perception perception = new Perception();
    private final Territory territory = new Territory();
    private final Progression progression;
    /** Asked, and only on a real block, to teach the local policy the way out and keep it. */
    private final Mentor mentor;

    private final Path directory;
    private final Table crafting;
    private final Table water;
    private final Table passage;
    /** One set of the four objective-bound tables per pursuit name, opened the first time it comes up. */
    private final Map<String, Suite> suites = new LinkedHashMap<>();
    /** The folder the move in flight was chosen from, whose tables hold the claims on its reward. */
    private Suite active;
    /** What the tables were working on at the last look. */
    private Pursuit pursuit = Pursuit.DONE;

    /** Where a copy of what the brain knows goes after every decision. No-op until something wants it. */
    private Consumer<QTableSnapshot> snapshotListener = snapshot -> {
    };

    private Observation lastObservation;
    /** The state the move in flight was chosen in, folder and all, as the planner's log reads it. */
    private String lastState;
    private Commitment commitment;
    private int stepsRun;
    /** Seconds of the move in flight its goal has had nothing to show for. Reset with every decision. */
    private int stalledSteps;
    /** Whether the second just gone was one of them, which is what tells a stall from a recovery. */
    private boolean stalledNow;
    /** Whether the committed goal has finished what it was for, which ends the move at once and for free. */
    private boolean doneNow;

    private GoalAction installedAction;
    private MobGoal installedGoal;
    private Object installedTarget;
    private CraftingGoal craftGoal;
    private MobGoal swimGoal;
    private Swim swimChoice = Swim.CARRY_ON;
    private MobGoal passageGoal;
    private Passage passageChoice = Passage.CARRY_ON;

    /** How many moves have been cut short for going nowhere. Shown on the overlay, learned from nowhere. */
    private long stalls;

    /** The body as it was when the move in flight was chosen, which is what the move is scored against. */
    private Moment since;
    /**
     * The body as it was when the water table last chose, or null on dry land. The water layer keeps its
     * own clock and so its own mark: a second in the water is scored from the last second in the water,
     * not from wherever the commitment happens to have started.
     */
    private Moment wet;
    /**
     * The line the body is walking to find a kind of place the loaded map does not yet show, as a yaw in
     * radians, or NaN when it is not looking for one. Held until the place turns up or the line stops
     * paying: a body that re-rolled its heading every decision covered fifty blocks of the same plain in
     * eight minutes, and a straight line is the way to cross a biome you cannot see the end of.
     */
    private double exploring = Double.NaN;
    /** The body as it was when the passage table last chose, or null while nothing is in its way. */
    private Moment stuckSince;
    /** Which way it wanted, and where, when it last chose: what its progress is measured against. */
    private Obstruction.Wanted stuckWanting;
    private Vec3 stuckTarget;
    /** The block it was trying to get at, and how many were on the line to it, when it last chose. */
    private BlockPos stuckSought;
    private int stuckBetween;
    private InventoryCensus obtained = InventoryCensus.empty();
    private InventoryCensus previousStepCensus;
    private int wastedTicks;
    private List<Resource> craftedThisStep = List.of();
    /**
     * Blocks put down and own blocks taken back up: over the move in flight, for scoring the decision, and
     * over the last step alone, for the layers that score a second at a time.
     */
    private Map<Resource, Integer> placedSinceDecision = Map.of();
    private Map<Resource, Integer> reclaimedSinceDecision = Map.of();
    private Map<Resource, Integer> placedThisStep = Map.of();
    private Map<Resource, Integer> reclaimedThisStep = Map.of();
    private boolean sawWhatItNeeds;
    private int ticksSinceStep;
    private int decisionsSinceSave;
    private int decisionsSinceReport;
    private int ticksDead;
    /**
     * Whether the body was in a world at the last tick. The edge into one is when a run begins, and it
     * is the only place that can say so: nothing else in the brain can tell "the same body, a tick later"
     * from "a different world with the same objective still written down".
     */
    private boolean inWorld;

    public QLearningBrain(MobEngine engine, Path directory) {
        this.engine = engine;
        // The objectives are planned rather than scripted: the ladder that used to be the plan is now only
        // what the run climbs when there is nobody to ask.
        this.progression = Progression.planned(directory);
        this.mentor = ClaudeMentor.create(directory);
        this.directory = directory;
        this.crafting = new Table(names(CraftChoice.values()), directory.resolve("crafting.txt"));
        this.water = new Table(names(Swim.values()), directory.resolve("water.txt"));
        this.passage = new Table(names(Passage.values()), directory.resolve("passage.txt"));
        crafting.load();
        water.load();
        passage.load();
    }

    /** Every table there is right now: the three shared ones and the four of each folder opened so far. */
    private Stream<Table> tables() {
        return Stream.concat(Stream.of(crafting, water, passage),
                suites.values().stream().flatMap(Suite::tables));
    }

    /** The folder for a pursuit, opened and read from disk the first time it is asked for. */
    private Suite suiteFor(Pursuit pursuit) {
        return suites.computeIfAbsent(pursuit.name(),
                name -> new Suite(directory.resolve(PURSUITS).resolve(name)));
    }

    /** Two lists as one, without either of them having to be growable. */
    private static <T> List<T> concat(List<T> first, List<T> second) {
        if (second.isEmpty()) {
            return first;
        }
        if (first.isEmpty()) {
            return second;
        }
        List<T> both = new java.util.ArrayList<>(first);
        both.addAll(second);
        return List.copyOf(both);
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    public List<String> actionNames() {
        return names(GoalAction.values());
    }

    /** Hands every later snapshot to {@code listener}, and one now so a watcher starts with something. */
    public void onSnapshot(Consumer<QTableSnapshot> listener) {
        this.snapshotListener = listener;
        publish(null, null, null, null);
    }

    public Progression progression() {
        return progression;
    }

    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            inWorld = false;
            leaveWorld();
            return;
        }
        // Death is checked before removal, and that order is the whole of it. Twenty ticks into dying the
        // client marks the body removed while the death screen is still up and client.player still points
        // at it, so a removed-first test reads a corpse as "no body here" and quietly hands the episode to
        // leaveWorld — which never respawns anything.
        if (player.isDeadOrDying()) {
            endEpisode(client, player);
            return;
        }
        if (player.isRemoved()) {
            inWorld = false;
            leaveWorld();
            return;
        }
        ticksDead = 0;
        if (!inWorld) {
            // A new world, or the same one entered again: either way a new run, and the planner is asked
            // what it should be about before the first step is taken. A respawn does not come through
            // here — the body never stopped being in the world — and keeps its run, as it should.
            inWorld = true;
            progression.arrive(player);
            mentor.reset();
        }
        if (++ticksSinceStep < STEP_TICKS) {
            return;
        }
        ticksSinceStep = 0;
        stepsRun++;
        // Placements first, because the gains are counted against them: a block of its own taken back up
        // is not a gain, and the sweep is what says which blocks are its own.
        Placed.get().sweep(player);
        placedThisStep = Placed.get().drainPlaced();
        reclaimedThisStep = Placed.get().drainReclaimed();
        placedSinceDecision = sum(placedSinceDecision, placedThisStep);
        reclaimedSinceDecision = sum(reclaimedSinceDecision, reclaimedThisStep);
        // Counted once a step and nowhere else. A move can be held for ten of them, so a running total
        // built where the decision reads it would miss the nine in between.
        tallyGains(player);
        // Where the body has been, noted once a step: the position table's whole state is this trail.
        territory.mark(player.position());
        // Taken here for the same reason as the gains: the goals count it as it happens, and reading it
        // anywhere but once a step would either drop it or charge for it twice.
        wastedTicks += WastedEffort.get().drain();
        // Same once-a-step rule, same reason: a craft counted twice would be charged twice, and one never
        // drained would be charged to whatever decision happened to look next.
        craftedThisStep = concat(craftedThisStep, CraftLog.get().drainCrafted());
        retireFinishedCraft();
        // On its own clock, whatever the commitment is doing: the water does not wait for a decision.
        tendWater(player);
        // And the terrain, on the same footing: a wall does not wait for a decision either.
        tendPassage(player);
        // Booked before the hold is tested, because whether the move has anything to show for the second
        // just gone is exactly what decides whether it keeps the body for the next one.
        // A goal that has done what it was for is not stuck, and the move is over the moment it says so:
        // no second charged for the ones it did not use, and the tables choose again now.
        doneNow = installedGoal != null && installedGoal.isDone() && !engine.isRunning(installedGoal);
        stalledNow = !doneNow && gettingNowhere();
        if (stalledNow) {
            stalledSteps++;
        }
        if (!doneNow && stillHolding()) {
            return;
        }
        decide(client, player);
    }

    /**
     * Whether the second just gone was spent on nothing.
     *
     * <p>The goal answers for itself — see {@link MobGoal#stalledTicks()} — and the two cases that are not
     * the goal's to answer are handled here. A goal that has finished, or that will not start, has nothing
     * to show for the second by definition: a block that broke ten seconds ago leaves a mining goal that
     * cannot run and a body standing in the hole, which was the plainest way a commitment used to be spent
     * on nothing at all.
     *
     * <p>And a primary that has had the body taken off it is not the one failing. Not drowning outranks
     * every errand and a pickaxe is worth walking to a table for; while either of those is happening the
     * move it displaced is not going anywhere and should not be charged for it.
     */
    private boolean gettingNowhere() {
        if (busy(swimGoal) || busy(craftGoal) || busy(passageGoal) || crafting()) {
            return false;
        }
        return installedGoal == null || !engine.isRunning(installedGoal)
                || installedGoal.stalledTicks() >= STALL_TICKS;
    }

    /**
     * Plants any lesson the mentor has sent into the folder and state it was taught for. The block that
     * prompted it is thereby resolved in the table itself, so it holds across the rest of the run and no
     * mentor is called for it again.
     */
    private void applyLessons() {
        Optional<Rescue> rescue = mentor.take();
        if (rescue.isEmpty()) {
            return;
        }
        Suite suite = suites.get(rescue.get().pursuit());
        if (suite == null) {
            return;
        }
        rescue.get().lessons().forEach(lesson ->
                suite.goals.seed(rescue.get().state(), lesson.action(), lesson.value()));
        log.info("Applied {} lessons to {} at {}", rescue.get().lessons().size(),
                rescue.get().pursuit(), rescue.get().state());
    }

    /** The best a folder's goal table thinks any legal move in this state is worth; zero when it knows none. */
    private static double bestLegal(Table goals, String state, boolean[] legal) {
        double[] values = goals.table.valuesFor(state);
        double best = 0.0;
        for (int i = 0; i < values.length; i++) {
            if (legal[i]) {
                best = Math.max(best, values[i]);
            }
        }
        return best;
    }

    /** Whether this goal has taken the body off the primary and is getting somewhere with it. */
    private boolean busy(MobGoal goal) {
        return goal != null && !goal.controls().isEmpty() && engine.isRunning(goal)
                && goal.stalledTicks() < STALL_TICKS;
    }

    /**
     * Whether a craft at a table or a smelt is holding the body right now.
     *
     * <p>Kept apart from {@link #busy} because those two are the one kind of work that is <em>meant</em> to
     * stand still for many seconds — walk to the table, open it, wait for the bar — and their own progress
     * meter reads that stillness as stalling. Treating them like a stalled walk let the passage layer
     * decide the body was stuck in terrain and pull it off the table every second, so the craft restarted
     * forever and never finished. They limit themselves with their own give-up; nothing else should
     * second-guess them while they run.
     */
    private boolean crafting() {
        return (craftGoal instanceof CraftAtTableGoal || craftGoal instanceof SmeltGoal)
                && engine.isRunning(craftGoal) && !craftGoal.isFinished();
    }

    /** Writes every table out. Called on the way out of the game as well as periodically. */
    public void save() {
        tables().forEach(Table::save);
    }

    /** Writes everything out and lets go of the planner's thread. The last thing the mod does. */
    public void close() {
        save();
        progression.close();
        mentor.close();
    }

    /**
     * Whether the move in flight keeps the body for another step.
     *
     * <p>It runs out its committed length, with two exceptions. A goal that cannot be abandoned half way —
     * a block coming apart — holds on past the end of its commitment rather than losing the work, but only
     * for {@link #COMMITTED_GRACE_STEPS} beyond it. And a move that has spent {@link #STALL_LIMIT_STEPS}
     * seconds getting nowhere gives the rest of the time back, because sitting out the other eight would
     * be eight more seconds of the same nothing — a goal that has finished, a wall that will not move, a
     * block that cannot be reached.
     *
     * <h2>Why the grace is bounded</h2>
     * It was not, and digging down exposed what that meant. A goal is uninterruptable while a block is
     * coming apart, and digging down is never not breaking a block: it finishes one and starts the next
     * before the step is over. So one choice of {@code DIG_DOWN} held the body until it hit bedrock or
     * lava, no matter which commitment the timing table had picked, and no table saw another decision the
     * whole way down. The exception is for finishing a block, so it lasts about as long as finishing a
     * block takes.
     *
     * <p>Being cut short is not the same as losing the work. The move ends and the brain chooses again,
     * and choosing the same thing on the same block leaves the goal exactly where it was — {@code install}
     * only tears a goal down when the choice has actually changed, or when what ended the move was the
     * body going nowhere, which is the one case where leaving it standing would settle nothing.
     */
    private boolean stillHolding() {
        if (commitment == null) {
            return false;
        }
        if (installedGoal != null && engine.isCommitted(installedGoal)
                && stepsRun < commitment.steps() + COMMITTED_GRACE_STEPS) {
            return true;
        }
        if (cutShort()) {
            return false;
        }
        return stepsRun < commitment.steps();
    }

    /**
     * Whether the move has spent long enough on nothing to be worth ending early.
     *
     * <p>Two questions, and it takes both. How much of the move has gone on nothing, counted over the
     * whole of it rather than in a row, because a goal that wastes every other second is wasting half the
     * move. And whether it is still going on now, because a journey that squeezed past a tree for two
     * seconds and then got going again has answered the question itself — ending it there would throw a
     * working move away over ground it has already made up.
     */
    private boolean cutShort() {
        return stalledNow && stalledSteps >= STALL_LIMIT_STEPS;
    }

    private void decide(Minecraft client, LocalPlayer player) {
        StepContext step = stepSince(since, player, Math.max(1, stepsRun), wastedTicks, stalledSteps,
                craftedThisStep, placedSinceDecision, reclaimedSinceDecision);

        // Score before looking: reaching a rung changes what the body is after, and the sighting that
        // follows should already be taken with the new rung's eyes.
        double climbed = progression.advanceIfComplete(step);
        double reward = score(step) + climbed;

        // What the move that just ended did, before anything replaces it. The planner reads these when an
        // objective drags on: a run of them is what a rut looks like from outside.
        DecisionLog.get().record(lastState, installedAction == null ? null : installedAction.name(),
                step.steps(), reward);

        // Looking is also what settles which folder of tables this decision is made in.
        ActionContext context = surroundings(client, player);
        Suite suite = suiteFor(pursuit);
        if (suite != active) {
            // The move just ended belongs to another folder. Its claims are settled with no continuation,
            // because a table that has never seen the state the body is in now has nothing to bootstrap
            // from — and the reward itself is what it earned, whichever folder came next.
            if (active != null) {
                active.settle(reward);
            }
            active = suite;
        }
        Observation observation = Observation.of(player, context, pursuit.source());

        boolean[] legalGoals = legalGoals(context);
        boolean[] legalCrafts = legalCrafts(context, step.after());

        // All three learn from the same reward over the same move: each one's share of the credit is
        // whatever its own column was doing while that reward was earned.
        String craftKey = CraftSituation.key(progression.needs(), step.after());
        active.goals.learn(observation.key(), reward, step.steps(), legalGoals);
        crafting.learn(craftKey, reward, step.steps(), legalCrafts);

        // A block the body is pinned in and its own table has no good answer for is the mentor's to
        // teach. Asked before choosing so a lesson that has just arrived can change this very decision;
        // the mentor dedups and throttles, so calling whenever the block holds costs nothing extra.
        applyLessons();
        if (territory.pinned() && !objectiveCraftable(legalCrafts)
                && bestLegal(active.goals, observation.key(), legalGoals) <= 1.0E-4) {
            String stuck = observation.key();
            String folder = pursuit.name();
            List<String> moves = names(GoalAction.values());
            mentor.consider(() -> new MentorAsk(
                    progression.blockSituation(player, obtained), folder, stuck, moves));
        }

        int goalIndex = active.goals.choose(observation.key(), legalGoals);
        if (goalIndex < 0) {
            // WANDER is always legal, so this cannot happen; bail rather than index nothing.
            return;
        }
        GoalAction action = GoalAction.values()[goalIndex];

        // Timing is keyed by the goal as well as the state: the question is not "how long to commit" but
        // "how long to commit to this".
        String timingKey = observation.key() + '/' + action.name();
        active.timing.learn(timingKey, reward, step.steps(), active.timing.everything);
        Commitment chosen = Commitment.values()[active.timing.choose(timingKey, active.timing.everything)];

        CraftChoice craft = chooseCraft(craftKey, legalCrafts);
        Aim aim = new Aim(
                chooseSpot(player, action, context, reward, step.steps()),
                chooseHeading(player, action, context, reward, step.steps()));

        // A move that ended with the body going nowhere leaves nothing worth keeping. Tearing the goal
        // down is what makes choosing again a real answer: without it, a table that picks the same move on
        // the same block gets the stalled goal left exactly where it was — still not running, still not
        // getting anywhere — and the decision changes nothing at all.
        //
        // Never a goal the engine says is mid-break, however. That one is not stalling now whatever the
        // move as a whole has wasted, and pulling it would throw away the block it is halfway through.
        if (cutShort() && !engine.isCommitted(installedGoal)) {
            stalls++;
            log.debug("Cutting {} short: {} seconds spent going nowhere", installedAction, stalledSteps);
            uninstall();
        } else if (doneNow) {
            // Finished, so the same choice again is a new one of the same thing, not the old one idling.
            uninstall();
        }

        install(action, context, aim);
        installCraft(craft, player);

        commitment = chosen;
        stepsRun = 0;
        stalledSteps = 0;
        stalledNow = false;
        doneNow = false;
        lastObservation = observation;
        lastState = pursuit.label() + ' ' + observation.key();
        since = Moment.of(player);
        wastedTicks = 0;
        craftedThisStep = List.of();
        placedSinceDecision = Map.of();
        reclaimedSinceDecision = Map.of();

        publish(observation.key(), action.name(), chosen.name(), craft.name());
        maintain(climbed > 0.0);
    }

    /**
     * What to make: the thing the plan is after, when it can be made right now, and otherwise whatever
     * the crafting table has learned.
     *
     * <p>The same rule as mining what the plan named, for the same reason. When the objective is a
     * pickaxe and a pickaxe is craftable, there is nothing left in the question for a table to learn an
     * answer to — and a table learning it from scratch made sticks, a sword and a second crafting table
     * first, each of them charged against the objective, until the planks were gone. What the table keeps
     * learning is everything on the way there: when planks are worth making, when sticks are.
     *
     * <p>The table's pending claim is credited before the rule takes over, so a forced craft is never
     * mistaken for the table's own choice.
     */
    /**
     * Whether the very thing this objective scores can be crafted right this decision. When it can, a block
     * the body is standing at is not what is holding it up — the craft is — so the mentor is not troubled
     * for it: the craft layer makes the thing and the objective moves on.
     */
    private boolean objectiveCraftable(boolean[] legalCrafts) {
        Resource after = progression.current().flatMap(Phase::scores).orElse(null);
        if (after == null) {
            return false;
        }
        for (CraftChoice choice : CraftChoice.values()) {
            if (choice.resource() == after && legalCrafts[choice.ordinal()]) {
                return true;
            }
        }
        return false;
    }

    private CraftChoice chooseCraft(String craftKey, boolean[] legalCrafts) {
        Resource after = progression.current().flatMap(Phase::scores).orElse(null);
        if (after != null) {
            for (CraftChoice choice : CraftChoice.values()) {
                if (choice.resource() == after && legalCrafts[choice.ordinal()]) {
                    crafting.forget();
                    return choice;
                }
            }
        }
        return CraftChoice.values()[crafting.choose(craftKey, legalCrafts)];
    }

    /**
     * Where the move about to be made should act, for the two moves that act on a block.
     *
     * <p>Asked after the goal is chosen and not before, because the question only exists once there is a
     * verb: "which block" means nothing until something is going to be done to one. That is also why this
     * table is credited here rather than up with the others — its claim is settled against the move that
     * followed it, and when the next move is not one it has an opinion about, that claim simply ends.
     *
     * @return where to act, or null when nothing is possible or the move does not act on a block
     */
    private BlockPos chooseSpot(LocalPlayer player, GoalAction action, ActionContext context,
                                double reward, int steps) {
        if (!action.usesSpot()) {
            // Nothing this table chose is going to matter to the move now being made, so its last choice
            // is settled with no continuation rather than left hanging.
            active.placement.learnTerminal(reward);
            active.placement.forget();
            return null;
        }
        BlockPos sighted = context.sighting().isBlock() ? context.sighting().blockPos() : null;
        if (action == GoalAction.MINE && fetchesWhatItSees(context)) {
            // The rule says which block as well as which verb: "go and break that one". Leaving the spot
            // to the placement table would let it answer with the block above it or the one under the
            // feet, which is the same learning the rule exists to skip.
            active.placement.learnTerminal(reward);
            active.placement.forget();
            return sighted;
        }
        boolean[] legal = legalSpots(player, action, sighted);
        String key = Placement.key(pursuit.source(), action, context.flags());
        active.placement.learn(key, reward, steps, legal);

        int column = active.placement.choose(key, legal);
        return column < 0 ? null : Spot.values()[column].resolve(player, sighted);
    }

    /**
     * Which way to take the body, for the moves that take it somewhere.
     *
     * <p>Keyed on the source being looked for, where the body is relative to the band the planner said it
     * lives in, whether the body is in the kind of place the planner said it is common in, and how its
     * recent trail reads — which is the whole of what makes this answerable. The terrain is the new word:
     * it is what lets "which way" mean "away from here" in the wrong biome and "keep going" in the right
     * one. A body with no memory of where it has been cannot prefer somewhere else, so {@link Territory}
     * is not decoration here, it is the state.
     *
     * <p>Every direction is always legal. There is no geometry to rule any of them out: a heading is a
     * suggestion the goal is free to turn away from when it meets a wall, and pretending otherwise would
     * be this table doing the walking goal's job for it.
     */
    private OptionalDouble chooseHeading(LocalPlayer player, GoalAction action, ActionContext context,
                                         double reward, int steps) {
        if (!action.usesGround()) {
            // The move now being made goes nowhere, so whatever this table last chose has no continuation.
            active.position.learnTerminal(reward);
            active.position.forget();
            return OptionalDouble.empty();
        }
        // When the planner named the kind of place and the loaded map has one in range, the way there is
        // a fact and not a choice: the body is pointed at it, and the table is not credited for a heading
        // it did not pick. It keeps the question for everywhere the map cannot answer.
        // The block the plan is after, when the map shows one and the eyes do not: the same rule as the
        // kind of place, one level down. A tree on the map is somewhere to walk, not something to learn.
        if (context.sighting().kind() != FocusKind.RESOURCE && progression.wanted().isPresent()) {
            OptionalDouble seen = Perception.bearingToBlock(player, progression.wanted().get());
            if (seen.isPresent()) {
                active.position.learnTerminal(reward);
                active.position.forget();
                exploring = Double.NaN;
                return seen;
            }
        }
        String biome = Travel.biomeAt(player);
        if (!pursuit.where().terrain().isEmpty()
                && "OUT".equals(pursuit.where().terrainKey(biome))) {
            active.position.learnTerminal(reward);
            active.position.forget();
            OptionalDouble told = Perception.bearingTo(player, pursuit.where().terrain());
            if (told.isPresent()) {
                exploring = Double.NaN;
                return told;
            }
            // Nothing of the kind in the loaded map. Hold a line across what there is, and turn a quarter
            // only when the trail says the line has stopped getting anywhere.
            if (Double.isNaN(exploring)) {
                exploring = Math.toRadians(player.getYRot());
            } else if (territory.pinned() || territory.circling()) {
                exploring += Math.PI / 2.0;
            }
            return OptionalDouble.of(exploring);
        }
        exploring = Double.NaN;
        String key = pursuit.source()
                + '|' + pursuit.where().band().where(player.getBlockY())
                + '|' + pursuit.where().terrainKey(biome)
                + '|' + territory.state()
                + '|' + context.flags();
        active.position.learn(key, reward, steps, active.position.everything);

        int column = active.position.choose(key, active.position.everything);
        return column < 0 ? OptionalDouble.empty()
                : Ground.values()[column].headingFor(player, territory);
    }

    /**
     * Keeps the water layer in step with the water, once a second, whatever the commitment is doing.
     *
     * <p>This is the layer that runs on its own clock, and the reason it has to. The commitment is held for
     * up to ten seconds and reconsidered at the end; the water is a fact of the next second. A body that
     * walked into a lake three seconds into a travel used to get no answer about the lake until the travel
     * was over, and spent the time floating: the goal it was committed to had nowhere it was willing to
     * aim, and the one table that knew what to do about water was not going to be asked until the clock
     * ran out.
     *
     * <p>So the table is asked every step the body is wet, learns every step from what the second in the
     * water earned, and is settled the moment the body is dry. Keyed on the water alone, how deep and how
     * much breath and where the ways out are, and on nothing about the objective: drowning is drowning
     * whether the run was after wood or after iron, and keying this on the rung would split one short
     * lesson across every plan the run ever has.
     *
     * <p>Nothing here touches the commitment. The swim goal sits on top of the committed one at a priority
     * it cannot outrank, holds the body for as long as the water choice needs it, and the engine hands the
     * body straight back when it lets go. The two are in the same body at once, and the engine's control
     * claims are what keep them out of the same legs at once.
     */
    private void tendWater(LocalPlayer player) {
        Water around = Water.around(player);
        if (!around.present()) {
            if (wet != null) {
                // Out. The last choice is settled against the second it bought, with no continuation:
                // dry land is not a state this table has, and the question will not come round again
                // until the next water does.
                water.learnTerminal(score(stepSince(wet, player, 1, 0, 0, List.of(),
                        placedThisStep, reclaimedThisStep)));
                water.forget();
                wet = null;
                removeSwim();
            }
            return;
        }
        Reserve reserve = progression.reserved();
        boolean hasBlocks = PlaceBlockGoal.hotbarSlotWithBlock(player, reserve) >= 0;
        boolean[] legal = legalSwims(around, hasBlocks);
        String key = around.key();
        if (wet != null) {
            water.learn(key, score(stepSince(wet, player, 1, 0, 0, List.of(), placedThisStep,
                    reclaimedThisStep)), 1, legal);
        }
        wet = Moment.of(player);

        int column = water.choose(key, legal);
        installSwim(column < 0 ? Swim.CARRY_ON : Swim.values()[column], around, reserve);
    }

    /** Doing nothing always works; the rest need somewhere to swim to or something to stand on. */
    private boolean[] legalSwims(Water around, boolean hasBlocks) {
        Swim[] options = Swim.values();
        boolean[] allowed = new boolean[options.length];
        for (int i = 0; i < options.length; i++) {
            allowed[i] = options[i].isApplicable(around, hasBlocks);
        }
        return allowed;
    }

    /**
     * Puts the water choice in place, on top of whatever the goal table is doing.
     *
     * <p>Left alone while the choice is unchanged and still running, for the same reason a craft is: a
     * swim to the surface restarted every second is a body treading water. A choice that has changed
     * replaces it, one whose goal has finished (arrived, or placed its block) is made again, and
     * {@link Swim#CARRY_ON} takes whatever was there away and gives the body back to the commitment.
     */
    private void installSwim(Swim choice, Water around, Reserve reserve) {
        if (swimGoal != null && choice == swimChoice && engine.isRunning(swimGoal)) {
            return;
        }
        removeSwim();
        swimChoice = choice;
        MobGoal goal = choice.create(around, reserve);
        if (goal != null) {
            swimGoal = goal;
            engine.addGoal(SWIM_PRIORITY, goal);
        }
    }

    private void removeSwim() {
        if (swimGoal != null) {
            engine.removeGoal(swimGoal);
            swimGoal = null;
        }
        swimChoice = Swim.CARRY_ON;
    }

    /**
     * Keeps the passage layer in step with the terrain, once a second, whatever the commitment is doing.
     *
     * <p>The question only arises when the committed goal is getting nowhere and the body wants to be
     * somewhere it is not: higher, lower, or further on. Then the terrain is read — see
     * {@link Obstruction} — and the table asked what to do about it, learns every second from what the
     * last second got it towards where it wanted, and is settled the moment the way is clear. The fix it
     * installs sits over the committed goal at a priority the goal cannot outrank, does its one block or
     * its few steps, and hands the body straight back.
     *
     * <p>Never in water, which has a layer of its own, and never while a block is coming apart under the
     * body's swings: that goal is not stuck, it is working.
     */
    private void tendPassage(LocalPlayer player) {
        if (passageGoal != null && !engine.isRunning(passageGoal) && passageChoice != Passage.CARRY_ON) {
            // The fix has done what it could. The goal underneath gets another go at what it was doing,
            // because the world it gave up on is not the world it is in now: the leaves are gone.
            removePassage();
            if (installedGoal instanceof MineSightingGoal mine) {
                mine.retry();
            }
        }
        Obstruction here = obstruction(player);
        if (here == null) {
            if (stuckSince != null) {
                passage.learnTerminal(passageReward(player));
                passage.forget();
                stuckSince = null;
                removePassage();
            }
            return;
        }
        boolean[] legal = legalPassages(here);
        String key = here.key();
        if (stuckSince != null) {
            passage.learn(key, passageReward(player), 1, legal);
        }
        stuckSince = Moment.of(player);
        stuckWanting = here.wanted();
        stuckTarget = here.target();
        stuckSought = here.sought();
        stuckBetween = here.aheadBlocks().size();

        int column = passage.choose(key, legal);
        installPassage(column < 0 ? Passage.CARRY_ON : Passage.values()[column], here);
    }

    /**
     * The terrain in the body's way, or null when there is no such question to ask.
     *
     * <p>Stuck means the committed goal has stopped running, has had nothing to show for a second, or is
     * pushing at something with somewhere to be. A body already being got through a wall by this layer
     * counts as stuck too, so the table keeps being asked — and keeps learning — until the way is open.
     */
    private Obstruction obstruction(LocalPlayer player) {
        if (wet != null || installedGoal == null || busy(swimGoal) || busy(craftGoal) || crafting()
                || engine.isCommitted(installedGoal)) {
            return null;
        }
        MobBody body = engine.body();
        boolean displaced = passageGoal != null && engine.isRunning(passageGoal);
        boolean stuck = displaced
                || !engine.isRunning(installedGoal)
                || installedGoal.stalledTicks() >= OBSTRUCTED_TICKS
                || (body.againstWall() && body.moveControl().hasDestination());
        if (!stuck) {
            return null;
        }
        boolean hasBlocks = PlaceBlockGoal.hotbarSlotWithBlock(player, progression.reserved()) >= 0;
        // A block in reach with something in the way of it comes first: the body is not trying to go
        // anywhere, it is trying to get at something, and the terrain that matters is the line to it.
        if (installedGoal instanceof MineSightingGoal mine && mine.occluder() != null) {
            Obstruction here = Obstruction.toward(player, mine.target(), hasBlocks);
            return here.matters() ? here : null;
        }
        Obstruction.Wanted wanted = wanted(player, body);
        if (wanted == null) {
            return null;
        }
        Vec3 target = body.moveControl().hasDestination() ? body.moveControl().destination() : null;
        Obstruction here = Obstruction.around(player, wanted, hasBlocks, target);
        // A goal that has stopped altogether is a question even with nothing in front: a walker that
        // found nowhere at all to aim — a ridge, a pit, a ledge — stands with a clear view and no way on,
        // and the first desert run stood like that for five minutes. Going round, back, down or up is
        // what this table is for.
        return here.matters() || !engine.isRunning(installedGoal) ? here : null;
    }

    /**
     * Which way the body is trying to go, or null when it is not trying to go anywhere.
     *
     * <p>Height first, from the plan: a band above or below, or a climb or descent asked for outright.
     * Failing that, the flat, when the legs have a destination or the committed move is one that walks —
     * a stroll that has given up against a wall has no destination left, and is still a stroll.
     */
    private Obstruction.Wanted wanted(LocalPlayer player, MobBody body) {
        OptionalInt height = progression.heightWanted(player.getBlockY());
        if (height.isPresent()) {
            int rise = height.getAsInt() - player.getBlockY();
            if (rise > 1) {
                return Obstruction.Wanted.UP;
            }
            if (rise < -1) {
                return Obstruction.Wanted.DOWN;
            }
        }
        if (body.moveControl().hasDestination()
                || (installedAction != null && installedAction.usesGround())) {
            return Obstruction.Wanted.FLAT;
        }
        return null;
    }

    /**
     * What the last second of dealing with the terrain was worth: the ordinary reward for the second,
     * plus something per block the body got towards where it wanted to be. Up is height gained, down is
     * height lost, on the flat it is ground closed on wherever the legs were headed, and towards a block
     * it is blocks cleared off the line to it.
     */
    private double passageReward(LocalPlayer player) {
        Vec3 before = stuckSince.position();
        Vec3 now = player.position();
        double progress = switch (stuckWanting) {
            case UP -> now.y - before.y;
            case DOWN -> before.y - now.y;
            case FLAT -> stuckTarget == null ? 0.0 : flat(before, stuckTarget) - flat(now, stuckTarget);
            // Blocks taken off the line to the one it is after. The block itself, once it comes away,
            // pays through the ordinary reward like any other gain.
            case TOWARD -> stuckSought == null ? 0.0
                    : stuckBetween - Obstruction.occluders(player, stuckSought).size();
        };
        return score(stepSince(stuckSince, player, 1, 0, 0, List.of(), placedThisStep, reclaimedThisStep))
                + PASSAGE_PROGRESS_WEIGHT * progress;
    }

    private static double flat(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Doing nothing and going round always work; the rest need something to break, build or dig. */
    private boolean[] legalPassages(Obstruction here) {
        Passage[] options = Passage.values();
        boolean[] allowed = new boolean[options.length];
        for (int i = 0; i < options.length; i++) {
            allowed[i] = options[i].isApplicable(here);
        }
        return allowed;
    }

    /** Puts the passage choice in place over the committed goal, left alone while unchanged and running. */
    private void installPassage(Passage choice, Obstruction here) {
        if (passageGoal != null && choice == passageChoice && engine.isRunning(passageGoal)) {
            return;
        }
        removePassage();
        passageChoice = choice;
        MobGoal goal = choice.create(here, progression.reserved());
        if (goal != null) {
            passageGoal = goal;
            engine.addGoal(PASSAGE_PRIORITY, goal);
        }
    }

    private void removePassage() {
        if (passageGoal != null) {
            engine.removeGoal(passageGoal);
            passageGoal = null;
        }
        passageChoice = Passage.CARRY_ON;
    }

    /** Which spots the world allows: you cannot mine air, and a block needs something to rest against. */
    private boolean[] legalSpots(LocalPlayer player, GoalAction action, BlockPos sighted) {
        Spot[] spots = Spot.values();
        boolean[] allowed = new boolean[spots.length];
        for (int i = 0; i < spots.length; i++) {
            allowed[i] = action == GoalAction.MINE
                    ? spots[i].canMine(player, sighted)
                    : spots[i].canPlace(player, sighted);
            // Mining the block under the feet is digging down by another name, and the same rule applies:
            // without it the shaft the goal table is not allowed to start could be started here instead.
            if (spots[i] == Spot.UNDER_FOOT && !progression.worthDigging(player.getBlockY())) {
                allowed[i] = false;
            }
        }
        return allowed;
    }

    /** Everything around the body, gathered once so the sighting and the map agree with each other. */
    private ActionContext surroundings(Minecraft client, LocalPlayer player) {
        Sighting sighting = perception.look(client, player, progression.wanted());
        // Noted here because this is where the eyes are: arriving somewhere with what the plan is after
        // in view is the thing the position table exists to learn, and it cannot see it any other way.
        sawWhatItNeeds = sighting.kind() == FocusKind.RESOURCE;
        // And what is in view is what decides which folder the tables are read from: the source seen, the
        // mob seen, or the source the plan thinks likeliest from here. Everything asked of the progression
        // below — ways, band, tool — is answered for that.
        pursuit = progression.focus(player, sighting);
        Reserve reserve = progression.reserved();
        return new ActionContext(
                sighting,
                Recipes.craftableNow(player),
                Perception.wallAhead(player),
                // Asked with the reserve, so a body whose only blocks are being held back has nothing to
                // build with as far as every table is concerned. Which is the truth of it.
                PlaceBlockGoal.hotbarSlotWithBlock(player, reserve) >= 0,
                Perception.canDigDown(player),
                toolFor(player, sighting.blockPos()),
                Perception.isHungry(player),
                Perception.canEat(player),
                Perception.wellFed(player),
                progression.worthDigging(player.getBlockY()),
                progression.heightWanted(player.getBlockY()),
                reserve,
                progression.minesWhatItSees() && sighting.kind() == FocusKind.RESOURCE);
    }

    /**
     * What to break a block with: the objective's answer when it named this block, the game's otherwise.
     * Worked out here rather than inside the goal so the goal is handed a decision instead of a lookup.
     */
    private Tool toolFor(LocalPlayer player, BlockPos pos) {
        if (pos == null || !player.level().isLoaded(pos)) {
            return Tool.HAND;
        }
        return progression.toolFor(player.level().getBlockState(pos));
    }

    /**
     * What changed since a moment, which is all the objectives are allowed to see.
     *
     * <p>Two clocks read through the one method. The commitment asks about the moment it was chosen and
     * brings the effort and the crafts it has been counting; the water layer asks about the last second in
     * the water and brings none of that, because swings and crafts belong to the move that made them and a
     * second of swimming should be scored on the swimming.
     *
     * @param from where to measure from, or null when there is nothing earlier than now
     */
    private StepContext stepSince(Moment from, LocalPlayer player, int steps, int wasted, int stalled,
                                  List<Resource> crafted, Map<Resource, Integer> placed,
                                  Map<Resource, Integer> reclaimed) {
        Moment now = Moment.of(player);
        Moment then = from == null ? now : from;
        return new StepContext(
                player,
                then.health(),
                now.health(),
                then.position(),
                now.position(),
                then.census(),
                now.census(),
                obtained,
                steps,
                wasted,
                stalled,
                then.food(),
                now.food(),
                then.air(),
                now.air(),
                crafted,
                placed,
                reclaimed,
                territory.pinned(),
                sawWhatItNeeds);
    }

    /** Two tallies as one. */
    private static Map<Resource, Integer> sum(Map<Resource, Integer> first, Map<Resource, Integer> second) {
        if (second.isEmpty()) {
            return first;
        }
        Map<Resource, Integer> both = new EnumMap<>(Resource.class);
        both.putAll(first);
        second.forEach((resource, amount) -> both.merge(resource, amount, Integer::sum));
        return both;
    }

    /** The body at one instant, kept so a later one can be scored against it. */
    private record Moment(float health, int food, int air, Vec3 position, InventoryCensus census) {

        static Moment of(LocalPlayer player) {
            return new Moment(player.getHealth(), player.getFoodData().getFoodLevel(),
                    player.getAirSupply(), player.position(), InventoryCensus.of(player.getInventory()));
        }
    }

    /**
     * Adds this step's gains to the running total the rungs are measured against.
     *
     * <p>Seeded from the first census rather than from zero, so a body that joins a world already carrying
     * a pickaxe starts partway up the ladder instead of being told to go and make one it has.
     */
    private void tallyGains(LocalPlayer player) {
        InventoryCensus now = InventoryCensus.of(player.getInventory());
        if (previousStepCensus == null) {
            obtained = obtained.plusGains(InventoryCensus.empty(), now);
        } else {
            // Less what it only took back: a block of its own picked up again was got the first time.
            obtained = obtained.plusGains(previousStepCensus, now).less(reclaimedThisStep);
        }
        previousStepCensus = now;
    }

    /** The step's worth against the general objectives and the rung being climbed. */
    private double score(StepContext step) {
        return GeneralObjectives.all().stream()
                .mapToDouble(objective -> objective.score(step))
                .sum()
                + progression.score(step);
    }

    private boolean[] legalGoals(ActionContext context) {
        GoalAction[] actions = GoalAction.values();
        boolean[] allowed = new boolean[actions.length];
        for (int i = 0; i < actions.length; i++) {
            allowed[i] = actions[i].isApplicable(context);
        }
        underThreat(context, allowed);
        if (!fetchesWhatItSees(context) || !allowed[GoalAction.MINE.ordinal()]) {
            return allowed;
        }
        // The plan named the blocks its resource comes off and the eyes have found one. There is nothing
        // left in the question, so there is nothing left to choose between: everything but breaking it
        // comes off the table. Eating stays, because a body that starves in front of the tree has not
        // gathered anything, and it is only ever legal when the body is hungry with food in hand.
        for (int i = 0; i < actions.length; i++) {
            allowed[i] = actions[i] == GoalAction.MINE
                    || (actions[i] == GoalAction.EAT && allowed[i]);
        }
        return allowed;
    }

    /**
     * What a body with something hostile in view may not do, as rules rather than lessons.
     *
     * <p>The threat folder is learned from scratch and explores at thirty per cent while it does, and the
     * first night of the first run was six deaths in a row spent finding out that walking up to a
     * creeper, standing to watch a skeleton, punching it and stopping for a snack are all bad ideas.
     * Nothing about those is worth a death to learn: the reward for each is the same every time and the
     * body does not get to keep what it learned across the death. So with a hostile in view there is no
     * approaching, watching, mining, digging or eating; fists only go up against something that is not
     * hostile, or with a sword in the hotbar; and a body on low health does not fight at all.
     */
    private void underThreat(ActionContext context, boolean[] allowed) {
        if (context.sighting().kind() != FocusKind.HOSTILE) {
            return;
        }
        for (GoalAction barred : List.of(GoalAction.APPROACH, GoalAction.WATCH, GoalAction.MINE,
                GoalAction.DIG_DOWN, GoalAction.EAT, GoalAction.REACH_BAND)) {
            allowed[barred.ordinal()] = false;
        }
        boolean armed = Tool.SWORD.hotbarSlot(Minecraft.getInstance().player.getInventory()) >= 0;
        boolean hurt = Perception.healthOf(Minecraft.getInstance().player) == Perception.Health.LOW;
        if (!armed || hurt) {
            // Unarmed, or too hurt to trade blows: get away from it or get above it, and nothing else.
            // Wandering and travelling with a zombie behind you are a chase in a random direction, and
            // the second trial's first night ended that way in a frozen river. What is left to learn is
            // whether to run or to build, which is a real question and a survivable one.
            allowed[GoalAction.ATTACK.ordinal()] = false;
            allowed[GoalAction.WANDER.ordinal()] = false;
            allowed[GoalAction.TRAVEL.ordinal()] = false;
        }
        if (!allowed[GoalAction.FLEE.ordinal()] && !allowed[GoalAction.PLACE.ordinal()]
                && !allowed[GoalAction.ATTACK.ordinal()]) {
            // Nothing left at all — the hostile is not something that can be fled from in the goal's
            // terms, or the mask has eaten everything. Running is always possible in principle.
            allowed[GoalAction.FLEE.ordinal()] = true;
        }
    }

    /**
     * Whether the rule applies right now, which is nearly the same question as {@link
     * ActionContext#mineOnSight()} and differs in one case that matters.
     *
     * <p>A rule with no way out of it would insist on the same block for the rest of the run. When the
     * mine already installed on that very block is getting nowhere the rule yields and the goal table
     * gets the decision back; the body walks off, the nearest block becomes a different one, and the rule
     * applies again to that.
     *
     * <p>Getting nowhere is two things and the rule has to yield to both, because the run has been stopped
     * by both. In reach and unable to land a blow is a log behind a wall, which is what
     * {@link MineSightingGoal#gaveUp()} was written for. Out of reach and unable to close the distance is
     * a log on the far side of a ravine, and that one used to be the rescue table's to answer — the only
     * thing it was ever genuinely needed for, since it was the one table this rule could not gag. What
     * answers it now is the goal's own count of the seconds it has spent achieving nothing.
     */
    private boolean fetchesWhatItSees(ActionContext context) {
        if (!context.mineOnSight()) {
            return false;
        }
        return !(installedGoal instanceof MineSightingGoal mine
                && (mine.gaveUp() || mine.stalledTicks() >= STALL_TICKS)
                && Objects.equals(installedTarget, context.sighting().blockPos()));
    }

    /**
     * Making nothing is always on the table; making a thing needs the ingredients for it, and needs them
     * to be ingredients the plan is willing to part with.
     *
     * <p>The reserve is a mask rather than a price, and this is the whole of what that means for crafting.
     * A run holding three planks back for a pickaxe is not offered the craft that would turn them into
     * sticks, so it cannot take it — no matter what the table thinks that craft is worth, and without
     * having to have learned anything first.
     */
    private boolean[] legalCrafts(ActionContext context, InventoryCensus held) {
        CraftChoice[] choices = CraftChoice.values();
        boolean[] allowed = new boolean[choices.length];
        Map<Resource, Integer> needs = progression.needs();
        for (int i = 0; i < choices.length; i++) {
            Resource made = choices[i].resource();
            if (made == null) {
                allowed[i] = true;
            } else if (choices[i].isSmelted()) {
                // Smelting is not on the recipe book: it is legal when the ore is in the bag and there is
                // something to burn. The goal finds or places the furnace itself.
                allowed[i] = held.count(choices[i].input()) > 0 && hasFuel(held)
                        && context.reserve().allowsMaking(made, held)
                        && keepsTheList(made, held, needs);
            } else {
                allowed[i] = context.craftable().contains(made)
                        && context.reserve().allowsMaking(made, held)
                        && keepsTheList(made, held, needs)
                        && !(made == Resource.CRAFTING_TABLE && held.count(made) > 0);
            }
        }
        return allowed;
    }

    /** Whether the bag holds anything a furnace will burn. The reserve is honoured where it is spent. */
    private static boolean hasFuel(InventoryCensus held) {
        return held.count(Resource.COAL) > 0 || held.count(Resource.PLANKS) > 0
                || held.count(Resource.LOG) > 0 || held.count(Resource.STICK) > 0;
    }

    /**
     * Whether making this leaves the bag holding at least what the plan says it needs of everything the
     * craft eats.
     *
     * <p>The shopping list is a price everywhere else, and a price can be outvoted: a table learning
     * from scratch made sticks three times while the plan wanted a pickaxe, and the third batch would
     * have eaten the planks the pickaxe was made of. So the list is a floor for crafting too. What the
     * plan is after is exempt — the pickaxe is allowed to consume the planks that were listed for it —
     * and so is anything the list does not mention.
     */
    private static boolean keepsTheList(Resource made, InventoryCensus held, Map<Resource, Integer> needs) {
        if (needs.containsKey(made)) {
            return true;
        }
        for (Map.Entry<Resource, Integer> eats : made.ingredients().entrySet()) {
            int wanted = needs.getOrDefault(eats.getKey(), 0);
            if (wanted > 0 && held.count(eats.getKey()) - eats.getValue() < wanted) {
                return false;
            }
        }
        return true;
    }

    /**
     * Swaps the engine's goal for the chosen one. Repeating the same action on the same thing leaves the
     * running goal alone: restarting it every decision would mean a tree never finishes being chopped.
     *
     * <h2>A committed goal is not swap-proof here</h2>
     * It used to be: a goal that declared itself uninterruptable was left alone whatever the tables had
     * just decided. That was the other half of the digging loop. Once the commitment was over the brain
     * did choose again — and published the new choice, so the overlay showed it — but this refused to
     * hand the body over, because a shaft is always mid-block. The body kept digging while the panel said
     * it was doing something else.
     *
     * <p>Keeping the break is {@link #stillHolding()}'s job and it does it there, by holding the move for
     * as long as the commitment plus a block's worth of grace. By the time a choice reaches here that time
     * is up, and a choice that has genuinely changed is meant to take effect. What still protects a break
     * is the line below: choosing the same thing on the same block changes nothing at all — unless the
     * move that just ended was going nowhere, in which case {@link #decide} has already taken the goal out
     * and what arrives here is a fresh attempt rather than the same stalled one.
     */
    private void install(GoalAction action, ActionContext context, Aim aim) {
        // For a move that acts on a block, the block is what identity means: the same verb aimed somewhere
        // else is a different move and has to replace what is running. A heading is not part of it — a
        // journey re-aimed every decision would never get anywhere, which is the thing this is here to fix.
        Object target = action.usesSpot() ? aim.spot()
                : action.usesSighting() ? context.sighting().target() : null;
        if (installedGoal != null && action == installedAction && Objects.equals(target, installedTarget)) {
            return;
        }
        uninstall();
        installedAction = action;
        installedTarget = target;
        installedGoal = action.create(context, aim);
        engine.addGoal(GOAL_PRIORITY, installedGoal);
        log.debug("Chose {} on {} while on {}", action, context.sighting().kind(), progression.stateKey());
    }

    /**
     * The craft the table asked for, made whichever way it can be.
     *
     * <p>A table nearby or in the hotbar means using it, which is the only way the three-wide recipes get
     * made at all; otherwise it is the body's own two-by-two, which needs no limbs and so runs in the
     * background alongside whatever else is going on.
     */
    private void installCraft(CraftChoice choice, LocalPlayer player) {
        if (craftGoal != null && choice.makesSomething() && craftGoal.target() == choice.resource()
                && !craftGoal.isFinished()) {
            return;
        }
        // A craft at a table is several seconds of work — put the table down, walk to it, open it — and a
        // decision comes every one to ten. Letting each decision's fresh draw from a table still learning
        // tear the goal down meant the pickaxe was started four times and finished never. One that is
        // under way finishes; the new choice waits its turn.
        if ((craftGoal instanceof CraftAtTableGoal || craftGoal instanceof SmeltGoal)
                && engine.isRunning(craftGoal) && !craftGoal.isFinished()) {
            return;
        }
        removeCraft();
        if (!choice.makesSomething()) {
            return;
        }
        if (choice.isSmelted()) {
            // Its own kind of work — a furnace, not a grid — and it claims the body like a table craft.
            craftGoal = new SmeltGoal(choice.resource(), choice.input());
            engine.addGoal(CRAFT_AT_TABLE_PRIORITY, craftGoal);
        } else if (!choice.handheld() && CraftAtTableGoal.tableAvailable(player)) {
            // Only a three-wide recipe is worth walking to a table for. A two-by-two one is made in the
            // inventory even when a table is right there: it claims no controls, so it gets made while the
            // body does whatever else it is doing — and it still gets made when the body is pinned somewhere
            // it cannot take the step to a table, which is exactly the spot a stuck planks craft died in.
            craftGoal = new CraftAtTableGoal(choice.resource());
            engine.addGoal(CRAFT_AT_TABLE_PRIORITY, craftGoal);
        } else {
            craftGoal = new CraftGoal(choice.resource());
            engine.addGoal(CRAFT_PRIORITY, craftGoal);
        }
    }

    /** A finished batch is taken out straight away, so the engine is not asked about a goal that is done. */
    private void retireFinishedCraft() {
        if (craftGoal != null && craftGoal.isFinished()) {
            removeCraft();
        }
    }

    private void removeCraft() {
        if (craftGoal != null) {
            engine.removeGoal(craftGoal);
            craftGoal = null;
        }
    }

    private void uninstall() {
        if (installedGoal != null) {
            engine.removeGoal(installedGoal);
        }
        installedGoal = null;
        installedAction = null;
        installedTarget = null;
    }

    private void maintain(boolean climbed) {
        if (climbed || ++decisionsSinceSave >= SAVE_EVERY_DECISIONS) {
            decisionsSinceSave = 0;
            save();
        }
        if (++decisionsSinceReport >= REPORT_EVERY_DECISIONS) {
            decisionsSinceReport = 0;
            log.info("Brain: on {} in {}, {} folders open with {} goal states, crafting {} states,"
                            + " {} moves cut short, epsilon {}, {} decisions",
                    progression.stateKey(), pursuit.label(), suites.size(),
                    suites.values().stream().mapToInt(suite -> suite.goals.table.states()).sum(),
                    crafting.table.states(), stalls,
                    String.format(Locale.ROOT, "%.3f", active == null ? 0.0 : active.goals.table.epsilon()),
                    decisions());
        }
    }

    /**
     * What the overlay sees: every folder opened so far, which one is in play, the plan as the run took
     * it, and the two shared tables.
     *
     * <p>Decisions are the total across every folder and the exploration rate is the active folder's own:
     * a folder opened this session explores like the beginner it is, however long the run has been going,
     * and the page should say so.
     */
    private void publish(String state, String action, String timingChoice, String craftChoice) {
        List<QTableSnapshot.Folder> folders = suites.entrySet().stream()
                .map(entry -> new QTableSnapshot.Folder(entry.getKey(),
                        entry.getValue().goals.table.epsilon(), entry.getValue().goals.table.decisions(),
                        entry.getValue().goals.table.rows(), entry.getValue().timing.table.rows(),
                        entry.getValue().placement.table.rows(), entry.getValue().position.table.rows()))
                .toList();
        snapshotListener.accept(new QTableSnapshot(
                names(GoalAction.values()), names(Commitment.values()), names(Spot.values()),
                names(Ground.values()), folders, active == null ? "" : pursuit.name(),
                active == null ? 0.0 : active.goals.table.epsilon(), decisions(),
                progression.stateKey(), progression.reason(), pursuit.label(),
                progression.plan().map(QTableSnapshot.PlanView::of).orElse(null),
                state, action, timingChoice, craftChoice, stalls,
                crafting.columns, crafting.table.rows(), CraftLog.get().recent(),
                water.columns, water.table.rows(),
                passage.columns, passage.table.rows()));
    }

    /** Goal decisions made across every folder opened so far. */
    private long decisions() {
        return suites.values().stream().mapToLong(suite -> suite.goals.table.decisions()).sum();
    }

    /**
     * Dying is the one transition with no successor: score it, then start a fresh episode.
     *
     * <p>And then get up. Nothing else is going to press the button, and a body left on the death screen
     * ends the run there for good.
     */
    private void endEpisode(Minecraft client, LocalPlayer player) {
        if (lastObservation != null) {
            tables().forEach(table -> table.learnTerminal(DEATH_PENALTY));
            forget();
        }
        // The plan goes with the life. A body that has just died is somewhere else with an empty bag, and
        // the objective it was chasing was chosen for a situation that no longer exists.
        progression.restart();
        if (++ticksDead == RESPAWN_DELAY_TICKS) {
            log.info("Died; respawning");
            player.respawn();
            client.setScreenAndShow(null);
        }
    }

    /**
     * Leaving a world is the last moment the learning is still in hand, and the client may well be closed
     * without a clean shutdown afterwards. Saves once on the way out rather than on every idle tick.
     */
    private void leaveWorld() {
        boolean wasPlaying = lastObservation != null;
        forget();
        if (wasPlaying) {
            save();
        }
    }

    /** Throws away everything learned and writes the wipe out, so it is what survives to the next session. */
    public void clearLearning() {
        tables().forEach(table -> table.table.clear());
        // The folders opened this session are wiped above and written out empty below. The ones on disk
        // from earlier sessions are not open, so their files go instead.
        Path folders = directory.resolve(PURSUITS);
        if (Files.isDirectory(folders)) {
            try (Stream<Path> files = Files.walk(folders)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        log.warn("Could not delete {}: {}", file, e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("Could not clear {}: {}", folders, e.getMessage());
            }
        }
        CraftLog.get().clear();
        PlannerLog.get().clear();
        forget();
        save();
    }

    /** Drops the episode without scoring it, for when the body simply is not there any more. */
    private void forget() {
        uninstall();
        removeCraft();
        removeSwim();
        removePassage();
        stuckSince = null;
        tables().forEach(Table::forget);
        active = null;
        lastObservation = null;
        lastState = null;
        since = null;
        wet = null;
        // Dying drops everything, and leaving takes the body with it. Either way the total is re-seeded
        // from whatever the next census finds, so the ladder never claims a pickaxe that is on the floor.
        obtained = InventoryCensus.empty();
        previousStepCensus = null;
        // A new life is not explained by the last one's wanderings.
        territory.clear();
        exploring = Double.NaN;
        // A tally of the moment, not a record of the run: an episode that ends takes it with it rather
        // than charging the next one for swings it never made.
        wastedTicks = 0;
        WastedEffort.get().clear();
        craftedThisStep = List.of();
        CraftLog.get().drainCrafted();
        Placed.get().clear();
        placedSinceDecision = Map.of();
        reclaimedSinceDecision = Map.of();
        placedThisStep = Map.of();
        reclaimedThisStep = Map.of();
        DecisionLog.get().clear();
        commitment = null;
        stepsRun = 0;
        stalledSteps = 0;
        stalledNow = false;
        doneNow = false;
        ticksSinceStep = 0;
        publish(null, null, null, null);
    }

    /**
     * The four objective-bound tables of one pursuit, read from and written to one folder.
     *
     * <p>Opened when a pursuit first comes up rather than all at once, because which pursuits a run will
     * have is the planner's to decide and there is no list to open from. The folder is made on the spot so
     * the first save has somewhere to go.
     */
    private static final class Suite {

        private final Table goals;
        private final Table timing;
        private final Table placement;
        private final Table position;

        private Suite(Path folder) {
            try {
                Files.createDirectories(folder);
            } catch (IOException e) {
                log.warn("Could not create {}: {}", folder, e.getMessage());
            }
            this.goals = new Table(names(GoalAction.values()), folder.resolve("goals.txt"));
            this.timing = new Table(names(Commitment.values()), folder.resolve("timing.txt"));
            this.placement = new Table(names(Spot.values()), folder.resolve("placement.txt"));
            this.position = new Table(names(Ground.values()), folder.resolve("position.txt"));
            tables().forEach(Table::load);
        }

        private Stream<Table> tables() {
            return Stream.of(goals, timing, placement, position);
        }

        /**
         * Credits every claim these tables hold with no continuation, and lets go of them. For a move
         * whose successor is another folder's: the reward is what it earned, and what came next is not
         * this folder's to value.
         */
        private void settle(double reward) {
            tables().forEach(table -> {
                table.learnTerminal(reward);
                table.forget();
            });
        }
    }

    /**
     * One learned dimension: a table, the names of its columns, where it lives, and the choice it is still
     * waiting to be told the worth of.
     *
     * <p>Having each one remember its own last state and column is what lets three tables be updated from
     * one reward without the brain keeping three copies of the same two fields.
     */
    private static final class Table {

        private final List<String> columns;
        private final Path path;
        private final QTable table;
        private final String signature;
        /** Every column legal, for the tables whose choices are never ruled out. */
        private final boolean[] everything;

        private String pendingState;
        private int pendingColumn = -1;

        private Table(List<String> columns, Path path) {
            this.columns = columns;
            this.path = path;
            this.table = new QTable(columns.size());
            this.signature = String.join(",", columns);
            this.everything = new boolean[columns.size()];
            Arrays.fill(this.everything, true);
        }

        private void load() {
            table.load(path, signature);
        }

        private void save() {
            table.save(path, signature);
        }

        /** Credits the choice this table is still waiting on, then leaves it waiting on the next one. */
        private void learn(String nextState, double reward, int steps, boolean[] nextLegal) {
            if (pendingState != null && pendingColumn >= 0) {
                table.update(pendingState, pendingColumn, reward, steps, nextState, nextLegal);
            }
        }

        private void learnTerminal(double reward) {
            if (pendingState != null && pendingColumn >= 0) {
                table.updateTerminal(pendingState, pendingColumn, reward);
            }
        }

        private int choose(String state, boolean[] legal) {
            int column = table.choose(state, legal);
            pendingState = state;
            pendingColumn = column;
            return column;
        }

        /** Plants a taught value at a state and a named column, ignoring a column this table does not have. */
        private void seed(String state, String action, double value) {
            int column = columns.indexOf(action);
            if (column >= 0) {
                table.seed(state, column, value);
            }
        }

        private void forget() {
            pendingState = null;
            pendingColumn = -1;
        }
    }
}
