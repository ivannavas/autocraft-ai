package io.github.ivannavas.autocraftai.mob.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
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

import io.github.ivannavas.autocraftai.mob.DeathNotice;
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
import io.github.ivannavas.autocraftai.mob.ai.skill.Readings;
import io.github.ivannavas.autocraftai.mob.ai.skill.Skill;
import io.github.ivannavas.autocraftai.mob.ai.skill.Skills;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.PlannerLog;
import io.github.ivannavas.autocraftai.mob.ai.objective.StepContext;
import io.github.ivannavas.autocraftai.mob.ai.objective.Terrain;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import io.github.ivannavas.autocraftai.mob.ai.objective.Travel;
import io.github.ivannavas.autocraftai.mob.ai.objective.Way;
import io.github.ivannavas.autocraftai.mob.goal.ApproachSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.CraftAtTableGoal;
import io.github.ivannavas.autocraftai.mob.goal.CraftGoal;
import io.github.ivannavas.autocraftai.mob.goal.CraftingGoal;
import io.github.ivannavas.autocraftai.mob.goal.MineSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.PlaceBlockGoal;
import io.github.ivannavas.autocraftai.mob.goal.SkillGoal;
import io.github.ivannavas.autocraftai.mob.goal.SmeltGoal;
import io.github.ivannavas.autocraftai.mob.goal.TravelGoal;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
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
     * A choice made lately, for tracing a death back to what led to it.
     *
     * <p>The terminal penalty lands on the one claim open when the body died, and a night that kills
     * spread its decisions over two hundred rows: the one that took the hit was rarely the one that
     * walked out under the sky at dusk. So every choice of the last two minutes is kept, and a death
     * takes value off each of them, less the older it is — half as much every thirty seconds.
     */
    private record Trace(Table table, String state, int column, long at) {
    }

    private static final java.util.Deque<Trace> TRACES = new java.util.ArrayDeque<>();
    private static final long TRACE_KEEP_MILLIS = 120_000;
    private static final double DEATH_TRACE = 8.0;
    private static final double DEATH_TRACE_HALF_LIFE_MILLIS = 30_000;

    /**
     * A cost per second of standing under the open sky at night with no shelter running. Not a rule:
     * a signal, the same shape as the readiness cost, so the tables can learn that dusk in the open is
     * where the deaths come from before a death has to teach it.
     */
    private static final double EXPOSED_COST = 0.15;
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
    /**
     * Above everything but the water: a body being shot at is not chopping a tree, and not drowning
     * still beats not being shot. Level with the swim goal, so whichever of the two has the body keeps
     * it — and the tactics layer steps aside for a wet body anyway.
     */
    private static final int TACTIC_PRIORITY = 0;
    /** Per hostile that died over a second the tactics layer was in charge of. About a log and a half. */
    private static final double KILL_BONUS = 6.0;
    /** Per block of height gained while trapped under a roof or down a pit: the way out is up. */
    private static final double TACTIC_HEIGHT_WEIGHT = 0.6;
    /** Paid once, for getting the sky back over the head. */
    private static final double DAYLIGHT_BONUS = 4.0;
    /**
     * Seconds the objective may go without getting nearer, under a roof or down a pit, before the
     * surroundings are worth a decision: trapped is a minute of nothing, not ten seconds of it.
     */
    private static final int STUCK_UNDER_COVER_STEPS = 60;

    private final MobEngine engine;
    private final Perception perception = new Perception();
    private final Territory territory = new Territory();
    private final Progression progression;
    /** Asked, and only on a real block, to teach the local policy the way out and keep it. */
    private final Mentor mentor;
    /** How many decisions in a row the body has been pinned on the same pursuit. A block is asked about at the third. */
    private int pinnedStreak;
    /** The pursuit the streak was counted on: standing still waiting for the first plan is not being stuck on it. */
    private String pinnedPursuit = "";
    /** Decisions made this session, for timing a lesson's outcome. */
    private long decisionsMade;
    /** The last lesson applied and when, until its outcome — free again, or still pinned — is known. */
    private Rescue lastRescue;
    private long rescueDecision;
    /** How often the objective had got nearer when the last stall lesson landed, for judging it. */
    private long rescueProgress;
    /** Drops the body gave up walking to, and until when each is left out of its sight. */
    private final Map<Entity, Long> shunned = new HashMap<>();
    /** Told what killed the body, once per death. No-op until something wants it. */
    private Consumer<String> onDeath = cause -> {
    };
    /**
     * The way to what the plan is after, when the loaded map shows it and the eyes do not — a fact rather
     * than a choice, worked out once per decision and read by both the legality mask and the heading.
     */
    private OptionalDouble toldHeading = OptionalDouble.empty();
    /**
     * Whether the way is settled by facts this decision: the map shows the thing, or the body is in the
     * wrong kind of place and holding a line out of it. Either way the position table is not asked and
     * the goal table is not offered a stroll.
     */
    private boolean headingIsFact;
    /** Whether the move in flight is a journey the map pointed, which ends the moment it arrives. */
    private boolean followingTheMap;
    /** How many decisions after a lesson the body has to be free of the block for the lesson to count. */
    private static final int RESCUE_WINDOW = 30;
    /**
     * How many decisions after a lesson for a stall the objective has to get nearer for the lesson to
     * count. Longer than a block's window: getting free is a matter of seconds, getting a log is a walk.
     */
    private static final int STALL_RESCUE_WINDOW = 120;
    /** How many decisions pinned before the mentor is asked: a stumble is not a block. */
    private static final int PINNED_BEFORE_MENTOR = 3;
    /**
     * Seconds the objective may go without getting any nearer before the mentor is asked about it.
     *
     * <p>Eight minutes. A body that is not pinned never reached the mentor before, and a body circling
     * a forest it cannot find the trees in, or walking past stone without the pickaxe to break it, is
     * not pinned. The planner's review runs on its own clock and reads the same record; this is the
     * mentor's turn, which comes first because a lesson is cheaper than a new plan and usually the fix.
     */
    private static final int STALLED_BEFORE_MENTOR = 480;
    /**
     * How long a drop the body could not get to is left out of its sight, so the eyes move on to the
     * next thing rather than reporting the same log in the canopy for the rest of the objective.
     */
    private static final long SHUN_MILLIS = 90_000L;
    /** How long a craft that gave up stays off the table, so the same trek is not started straight back. */
    private static final long CRAFT_BACKOFF_MILLIS = 60_000L;
    /** Crafts that gave up recently, and until when they are not to be tried again. */
    private final Map<Resource, Long> craftBackoff = new EnumMap<>(Resource.class);
    /** When each craft skill may be tried again after failing, by name: its failure is its own, not its product's. */
    private final Map<String, Long> skillBackoff = new HashMap<>();
    /** When the craft skill in flight was handed to the engine, for giving up on one that never starts. */
    private long craftSkillAddedAt;
    /** How long a craft skill may wait for the body before the choice is given back. */
    private static final long CRAFT_SKILL_PENDING_MILLIS = 15000;

    private final Path directory;
    private final Table crafting;
    private final Table water;
    private final Table passage;
    /** What to do about the surroundings as a whole — hostiles, night, a roof — keyed by {@link Surroundings}. */
    private final Table tactics;
    /** One set of the four objective-bound tables per pursuit name, opened the first time it comes up. */
    private final Map<String, Suite> suites = new LinkedHashMap<>();
    /** The folder the move in flight was chosen from, whose tables hold the claims on its reward. */
    private Suite active;
    /** What the tables were working on at the last look. */
    private Pursuit pursuit = Pursuit.PLANNING;

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
    /** The skill in flight when the move is one the mentor wrote rather than a built-in move. */
    private Skill installedSkill;
    private MobGoal installedGoal;
    private Object installedTarget;
    private CraftingGoal craftGoal;
    /**
     * A craft skill in flight — a move the planner or the mentor wrote for the crafting layer, such as
     * loading a furnace. It holds the body the way a table craft does, and is settled the way a skill is.
     */
    private SkillGoal craftSkill;
    /** What the craft skill in flight makes, in the plan's vocabulary, for the mask and the backoff. */
    private Resource craftSkillMakes;
    private MobGoal swimGoal;
    private Swim swimChoice = Swim.CARRY_ON;
    private MobGoal passageGoal;
    /** The passage column in flight: a built-in move's ordinal, or past those a skill. */
    private int passageColumn = Passage.CARRY_ON.ordinal();
    private MobGoal tacticGoal;
    /** The tactic column in flight, and the built-in tactic it is, or null when it is a skill. */
    private int tacticColumn = Tactic.CARRY_ON.ordinal();
    private Tactic tacticChoice = Tactic.CARRY_ON;
    /** The body as it was when the tactics table last chose, or null while the surroundings are quiet. */
    private Moment tacticSince;
    /** The surroundings as they were then: what was alive, and whether the body was under a roof. */
    private Surroundings tacticSeen;
    /** The last reason the objective's own craft was illegal, so it is logged once and not every second. */
    private String lastIllegalCraft = "";

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
    /** When the line was last turned, so a trail that still reads as a loop does not turn it again at once. */
    private long exploringTurnedAt;
    /**
     * The least time between two turns of the line. A quarter turn takes the trail half a minute to
     * stop reading as a loop, and a line turned every decision while it did was a body spinning on the
     * spot — a journey re-pointed a quarter round each second never got four blocks along any of them.
     */
    private static final long EXPLORING_TURN_MILLIS = 30_000L;
    /** The body as it was when the passage table last chose, or null while nothing is in its way. */
    private Moment stuckSince;
    /** The surroundings as last read by the tactics layer, for the exposure cost. */
    private Surroundings lastSurroundings;
    /** When the passage layer first took the body this time, and from where, for its patience. */
    private long passageHeldAt;
    private Vec3 passageHeldFrom;
    /** Until when the passage layer stands aside after running out of patience. */
    private long passageCooldownUntil;
    /** How long the layer may keep the body without making way before it stands aside. */
    private static final long PASSAGE_PATIENCE_MILLIS = 20_000;
    private static final long PASSAGE_COOLDOWN_MILLIS = 10_000;
    /** How much way, in blocks the wanted direction, counts as making some. */
    private static final double PASSAGE_WAY_MADE = 1.5;
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
        synchronized (TRACES) {
            TRACES.clear();
        }
        // The skills first: they are columns, and the tables have to open with them.
        Skills.get().load(directory);
        this.crafting = new Table(columnsFor(Skill.Layer.CRAFT), directory.resolve("crafting.txt"));
        this.water = new Table(names(Swim.values()), directory.resolve("water.txt"));
        this.passage = new Table(columnsFor(Skill.Layer.PASSAGE), directory.resolve("passage.txt"));
        this.tactics = new Table(columnsFor(Skill.Layer.TACTIC), directory.resolve("tactics.txt"));
        crafting.load();
        water.load();
        passage.load();
        tactics.load();
        // A skill written while the run is up becomes a column of its layer's table the moment it is
        // taken in — every folder's goal table for a goal skill — so the lesson that comes with it has
        // somewhere to land.
        Skills.get().onAdded(skill -> {
            switch (skill.layer()) {
                case GOAL -> suites.values().forEach(suite -> suite.goals.addColumn(skill.name()));
                case PASSAGE -> passage.addColumn(skill.name());
                case TACTIC -> tactics.addColumn(skill.name());
                case CRAFT -> crafting.addColumn(skill.name());
            }
        });
    }

    /** The columns of a layer's table: the built-in moves, then the live skills, in the order written. */
    private static List<String> columnsFor(Skill.Layer layer) {
        List<String> columns = new java.util.ArrayList<>(switch (layer) {
            case GOAL -> names(GoalAction.values());
            case PASSAGE -> names(Passage.values());
            case TACTIC -> names(Tactic.values());
            case CRAFT -> names(CraftChoice.values());
        });
        Skills.get().live(layer).forEach(skill -> columns.add(skill.name()));
        return columns;
    }

    /** The live skills of a layer, in column order: column {@code enumLength + i} is the i-th. */
    private static List<Skill> skillsOf(Skill.Layer layer) {
        return Skills.get().live(layer);
    }

    /** Every table there is right now: the four shared ones and the four of each folder opened so far. */
    private Stream<Table> tables() {
        return Stream.concat(Stream.of(crafting, water, passage, tactics),
                suites.values().stream().flatMap(Suite::tables));
    }

    /**
     * The surroundings as a skill's conditions read them, fresh. Built on demand: a skill goal asks
     * while it is idle and once every few ticks while it runs, and each asking is an entity query.
     */
    private Readings readings(LocalPlayer player) {
        boolean stuck = progression.current().isPresent()
                && progression.stepsWithoutProgress() >= STUCK_UNDER_COVER_STEPS;
        return Readings.of(player, Surroundings.around(player, progression.reserved(), stuck), wet != null,
                progression.needs());
    }

    /** A skill's goal, wired to read the surroundings through this brain. */
    private SkillGoal skillGoal(Skill skill) {
        return new SkillGoal(skill, progression.reserved(), () -> {
            LocalPlayer player = Minecraft.getInstance().player;
            return player == null ? null : readings(player);
        });
    }

    /**
     * Counts a skill goal's outcome, for the skill's record, when it is taken out. Done or failed is
     * counted; a goal taken out mid-way was interrupted, which says nothing about the skill.
     */
    private static void settleSkill(MobGoal goal) {
        if (goal instanceof SkillGoal run) {
            if (run.isDone()) {
                log.info("Skill {} finished", run.skill().name());
                Skills.get().completed(run.skill().name());
            } else if (run.failed()) {
                log.info("Skill {} failed: {}", run.skill().name(), run.failure());
                Skills.get().failed(run.skill().name(), run.failure());
            }
        }
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
        return columnsFor(Skill.Layer.GOAL);
    }

    /** Hands every later snapshot to {@code listener}, and one now so a watcher starts with something. */
    public void onSnapshot(Consumer<QTableSnapshot> listener) {
        this.snapshotListener = listener;
        publish(null, null, null, null);
    }

    public Progression progression() {
        return progression;
    }

    /**
     * Hands every later death to {@code listener}, with the server's sentence on what did it. Called on
     * the game thread, so whatever listens has to get out of the way quickly.
     */
    public void onDeath(Consumer<String> listener) {
        this.onDeath = listener;
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
        // Nor does a skeleton, or nightfall, or a roof the objective is on the other side of.
        tendTactics(player);
        // And the terrain, on the same footing: a wall does not wait for a decision either.
        tendPassage(player);
        // Booked before the hold is tested, because whether the move has anything to show for the second
        // just gone is exactly what decides whether it keeps the body for the next one.
        // A goal that has done what it was for is not stuck, and the move is over the moment it says so:
        // no second charged for the ones it did not use, and the tables choose again now. A journey the
        // map pointed is done the moment what it was pointed at is in view, whatever the journey thinks.
        doneNow = (installedGoal != null && installedGoal.isDone() && !engine.isRunning(installedGoal))
                || arrived(player);
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
        if (displaced()) {
            return false;
        }
        return installedGoal == null || !engine.isRunning(installedGoal)
                || installedGoal.stalledTicks() >= STALL_TICKS;
    }

    /** Whether something with a higher claim — water, a tactic, a craft, the terrain — has the body off the primary. */
    private boolean displaced() {
        return busy(swimGoal) || busy(tacticGoal) || busy(craftGoal) || busy(craftSkill) || busy(passageGoal)
                || crafting();
    }

    /**
     * Whether a tactic is holding the body still on purpose: sealed in a hole, or standing on a tower.
     *
     * <p>Not moving is what those are for, and everything that reads "not moving" as trouble — the
     * pinned count, the planner's self-rescue, the mentor — has to be told so, or the body climbs out of
     * its own shelter to satisfy a rule about being stuck.
     */
    private boolean sheltering() {
        return tacticGoal != null && engine.isRunning(tacticGoal)
                && (tacticChoice == Tactic.HOLE_UP || tacticChoice == Tactic.TOWER);
    }

    /**
     * Whether the running tactic is one to see through: a shelter, or a climb back to daylight that is
     * still getting somewhere. The layer woke for DAYLIGHT because the body was stuck under a roof; the
     * first block gained cleared "stuck", the surroundings read as quiet, and the climb was dropped ten
     * seconds in with the body still underground and starving.
     */
    private boolean committed() {
        if (sheltering()) {
            return true;
        }
        return tacticGoal != null && engine.isRunning(tacticGoal) && tacticChoice == Tactic.DAYLIGHT
                && !tacticGoal.isDone() && tacticGoal.stalledTicks() < STALL_TICKS;
    }

    /**
     * Takes in the skills the planner wrote beside its last answer. Each becomes a column of its table,
     * seeded with its prior in the row the run is in right now — the planner wrote it for the objective
     * in hand, and this is where the objective is being worked.
     */
    private void takeOfferedSkills(String stateKey, String craftKey, LocalPlayer player) {
        for (Skill skill : Skills.get().takeOffered()) {
            Optional<String> refused = Skills.get().add(skill);
            if (refused.isPresent()) {
                log.info("Planner's skill {} not taken: {}", skill.name(), refused.get());
                PlannerLog.get().plannerNoted("skill " + skill.name() + " refused: " + refused.get());
                continue;
            }
            switch (skill.layer()) {
                case GOAL -> {
                    if (active != null) {
                        active.goals.seed(stateKey, skill.name(), skill.prior());
                    }
                }
                case CRAFT -> crafting.seed(craftKey, skill.name(), skill.prior());
                case PASSAGE -> passage.seed(ground(player).key(), skill.name(), skill.prior());
                case TACTIC -> tactics.seed(Surroundings.around(player, progression.reserved(), false).key(),
                        skill.name(), skill.prior());
            }
        }
    }

    /**
     * Plants any lesson the mentor has sent into the folder and state it was taught for. The block that
     * prompted it is thereby resolved in the table itself, so it holds across the rest of the run and no
     * mentor is called for it again.
     */
    private void applyLessons() {
        Optional<Rescue> taken = mentor.take();
        if (taken.isEmpty()) {
            return;
        }
        Rescue rescue = taken.get();
        if (rescue.skill() != null) {
            // A move the mentor wrote. Taken in first, so it is a column by the time the lessons — which
            // may name it — are planted; then seeded in the row it was written for at the value given.
            Skill skill = rescue.skill();
            Optional<String> refused = Skills.get().add(skill);
            if (refused.isPresent()) {
                log.info("Skill {} not taken: {}", skill.name(), refused.get());
                PlannerLog.get().mentorNoted("skill " + skill.name() + " refused: " + refused.get());
            } else {
                PlannerLog.get().mentorNoted("new skill " + skill.describe());
                switch (skill.layer()) {
                    case GOAL -> {
                        Suite folder = suites.get(rescue.pursuit());
                        if (folder != null) {
                            folder.goals.seed(rescue.state(), skill.name(), skill.prior());
                        }
                    }
                    case PASSAGE -> passage.seed(rescue.terrain(), skill.name(), skill.prior());
                    case TACTIC -> {
                        if (!rescue.tacticKey().isEmpty()) {
                            tactics.seed(rescue.tacticKey(), skill.name(), skill.prior());
                        }
                    }
                    case CRAFT -> {
                        if (!rescue.craftKey().isEmpty()) {
                            crafting.seed(rescue.craftKey(), skill.name(), skill.prior());
                        }
                    }
                }
            }
        }
        Suite suite = suites.get(rescue.pursuit());
        if (suite != null) {
            rescue.lessons().forEach(lesson -> suite.goals.seed(rescue.state(), lesson.action(), lesson.value()));
        }
        // The passage table is shared, and its row is the ground, so this lands wherever the body is
        // next stuck on the same kind of terrain — not only here.
        rescue.passageLessons().forEach(lesson -> passage.seed(rescue.terrain(), lesson.action(), lesson.value()));
        // And the crafting table, for the block that is not terrain at all: a craft holding the body.
        rescue.craftLessons().forEach(lesson -> crafting.seed(rescue.craftKey(), lesson.action(), lesson.value()));
        // And the tactics table, for the block that is the surroundings: a night, a roof, a mob.
        if (!rescue.tacticKey().isEmpty()) {
            rescue.tacticLessons().forEach(lesson ->
                    tactics.seed(rescue.tacticKey(), lesson.action(), lesson.value()));
        }
        if (rescue.asksToReplan()) {
            // The one lesson no table can hold: the objective itself is the problem. The plan goes and
            // the planner is asked again with the mentor's sentence in the question; there is nothing to
            // settle afterwards, because there is no objective left to have got nearer.
            progression.replan(rescue.replan());
            PlannerLog.get().mentorNoted("gave the objective up and asked the planner for another: "
                    + rescue.replan());
            log.info("Mentor gave up on the objective ({}) in {}", rescue.replan(), rescue.pursuit());
            lastRescue = null;
            return;
        }
        lastRescue = rescue;
        rescueDecision = decisionsMade;
        rescueProgress = progression.progressCount();
        log.info("Applied lessons ({}) to {} at {} / {}", rescue.summary(), rescue.pursuit(),
                rescue.state(), rescue.terrain());
    }

    /**
     * Says what became of the last lesson, once: the body came unpinned within the window, or it did not.
     * For a stall the question is the other one — did the objective get any nearer — and the window is
     * longer, because a log is further off than a way out. Written to the mentor's log so the page shows
     * the coaching's record beside the coaching.
     */
    private void settleRescue() {
        if (lastRescue == null) {
            return;
        }
        long since = decisionsMade - rescueDecision;
        boolean stall = lastRescue.stalled();
        boolean worked = stall ? progression.progressCount() > rescueProgress : !territory.pinned();
        long window = stall ? STALL_RESCUE_WINDOW : RESCUE_WINDOW;
        String what = stall ? "the objective got nearer" : "free";
        if (worked) {
            log.info("Mentor lesson worked: {} after {} decisions ({})", what, since, lastRescue.summary());
            PlannerLog.get().mentorNoted("worked: " + what + " after " + since + " decisions ("
                    + lastRescue.summary() + ")");
            lastRescue = null;
        } else if (since >= window) {
            String still = stall ? "no nearer" : "still pinned";
            log.info("Mentor lesson did not work: {} after {} decisions ({})", still, since,
                    lastRescue.summary());
            PlannerLog.get().mentorNoted("did not work: " + still + " after " + since + " decisions ("
                    + lastRescue.summary() + ")");
            lastRescue = null;
        }
    }

    /**
     * The ground under the block, as the passage layer would read it, for telling the mentor. Read
     * whatever the passage layer's own gate says, because the mentor is asked about a body that is not
     * moving at all, which is a state that gate does not always call an obstruction.
     */
    /**
     * What is actually moving the body right now, for the mentor. The stuck state says what the goal table
     * chose; it does not say that a table craft outranked that choice and has had the body for a minute,
     * which is the one fact that explained the longest block seen so far.
     */
    private String driver() {
        if (tacticGoal != null && engine.isRunning(tacticGoal)) {
            return tacticGoal.name() + " (a tactic: the surroundings layer has the body)";
        }
        if (crafting()) {
            MobGoal making = craftGoal != null && engine.isRunning(craftGoal) ? craftGoal : craftSkill;
            return making.name() + " — a craft that walks to a table or furnace and holds the body,"
                    + " outranking every goal move, until it finishes or gives up";
        }
        if (passageGoal != null && engine.isRunning(passageGoal)) {
            return passageGoal.name() + " (a passage move)";
        }
        if (swimGoal != null && engine.isRunning(swimGoal)) {
            return swimGoal.name() + " (swimming)";
        }
        if (installedGoal != null && engine.isRunning(installedGoal)) {
            return installedGoal.name() + " (the chosen goal move)";
        }
        return "nothing is running";
    }

    /**
     * The same as {@link #driver()} in a few words, for the overlay's status line: which goal has the body
     * and which layer it belongs to. The answer to "it is deciding, so why is nothing happening" — a
     * tactic sealed in a hole, a craft standing at a table, a passage move in a wall.
     */
    private String driverName() {
        if (tacticGoal != null && engine.isRunning(tacticGoal)) {
            return tacticGoal.name() + " (tactic)";
        }
        if (craftSkill != null && engine.isRunning(craftSkill)) {
            return craftSkill.name() + " (craft)";
        }
        if (craftGoal != null && engine.isRunning(craftGoal) && !craftGoal.controls().isEmpty()) {
            return craftGoal.name() + " (craft)";
        }
        if (passageGoal != null && engine.isRunning(passageGoal)) {
            return passageGoal.name() + " (passage)";
        }
        if (swimGoal != null && engine.isRunning(swimGoal)) {
            return swimGoal.name() + " (water)";
        }
        if (installedGoal != null && engine.isRunning(installedGoal)) {
            return installedGoal.name();
        }
        return "";
    }

    private Obstruction ground(LocalPlayer player) {
        boolean hasBlocks = PlaceBlockGoal.hotbarSlotWithBlock(player, progression.reserved()) >= 0;
        if (installedGoal instanceof MineSightingGoal mine && mine.occluder() != null) {
            return Obstruction.toward(player, mine.target(), hasBlocks);
        }
        MobBody body = engine.body();
        Obstruction.Wanted wanted = wanted(player, body);
        Vec3 target = body.moveControl().hasDestination() ? body.moveControl().destination() : null;
        return Obstruction.around(player, wanted == null ? Obstruction.Wanted.FLAT : wanted, hasBlocks, target);
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
        // A craft skill counts from the moment it is handed to the engine, not from when it gets the body:
        // the passage layer's stroll ran at the same priority, so a skill that waited for it to end was
        // pulled off by the next stroll for thirteen seconds before it ever started.
        if (craftSkill != null && !craftSkill.isDone() && !craftSkill.failed()
                && (engine.isRunning(craftSkill)
                    || System.currentTimeMillis() - craftSkillAddedAt <= CRAFT_SKILL_PENDING_MILLIS)) {
            return true;
        }
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
        double reward = score(step) + climbed + exposure(step.steps());

        // What the move that just ended did, before anything replaces it. The planner reads these when an
        // objective drags on: a run of them is what a rut looks like from outside.
        DecisionLog.get().record(lastState, installedName(), step.steps(), reward);

        // Looking is also what settles which folder of tables this decision is made in.
        ActionContext context = surroundings(client, player);
        // And where the map says to go, which is a fact the mask and the heading both read. Being in the
        // wrong kind of place settles the question only when walking is the way there: stone is under
        // the forest floor as well as in the mountain, and a plan that allows a shaft has left the choice
        // between the walk and the shaft to the table.
        toldHeading = wayThere(player, context);
        headingIsFact = toldHeading.isPresent()
                || (inTheWrongKindOfPlace(player) && !pursuit.where().allows(Way.DIG));
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
        // The bag as the plan counts it: a workbench standing within reach is a table held.
        InventoryCensus held = progression.effective(step.after(), player);
        boolean[] legalCrafts = legalCrafts(context, held, player);

        // All three learn from the same reward over the same move: each one's share of the credit is
        // whatever its own column was doing while that reward was earned.
        String craftKey = CraftSituation.key(progression.needs(), held);
        active.goals.learn(observation.key(), reward, step.steps(), legalGoals);
        crafting.learn(craftKey, reward, step.steps(), legalCrafts);

        // A body pinned for several decisions on something it cannot simply craft its way out of is a
        // block, and a block is the mentor's to teach. Asked before choosing so a lesson that has just
        // arrived can change this very decision; the mentor paces and dedups, so asking while the block
        // holds costs nothing extra. The outcome of the last lesson is settled first, so the page can say
        // whether the coaching is working.
        decisionsMade++;
        boolean samePursuit = pursuit.name().equals(pinnedPursuit);
        pinnedPursuit = pursuit.name();
        pinnedStreak = territory.pinned() ? (samePursuit ? pinnedStreak + 1 : 1) : 0;
        settleRescue();
        applyLessons();
        takeOfferedSkills(observation.key(), craftKey, player);
        // Not while a table craft or a smelt has the body and is getting on with it: standing at a table
        // is work, not a block, and a craft whose walk has stalled lets go on its own within seconds.
        //
        // Two things bring the mentor in. A body pinned for several decisions is a block. A body that
        // moves and has got the objective no nearer for eight minutes is a stall, which no amount of
        // being unpinned ever caught: the mentor is asked about that too, with the objective's record,
        // and may answer by giving the objective up rather than by teaching a way to it.
        boolean pinned = pinnedStreak >= PINNED_BEFORE_MENTOR;
        boolean stalled = progression.stepsWithoutProgress() >= STALLED_BEFORE_MENTOR;
        // Nor while a tactic has the body: a body sealed in a hole for the night is pinned on purpose,
        // and a coach asked about it would teach it the way out of its own shelter.
        if ((pinned || stalled) && progression.current().isPresent()
                && !objectiveCraftable(legalCrafts) && !crafting() && !sheltering()) {
            MentorAsk.Reason reason = pinned ? MentorAsk.Reason.BLOCK : MentorAsk.Reason.STALL;
            String stuck = observation.key();
            String folder = pursuit.name();
            // The moves the body may actually make here, not every column: a lesson about a move the
            // mask has taken away is a lesson nobody consults.
            List<String> moves = legalNames(active.goals.columns, legalGoals);
            List<String> craftMoves = legalNames(crafting.columns, legalCrafts);
            int y = player.getBlockY();
            boolean stuckUnderCover = stalled;
            mentor.consider(() -> {
                Obstruction ground = ground(player);
                Surroundings around = Surroundings.around(player, progression.reserved(), stuckUnderCover);
                List<String> passageMoves = legalNames(passage.columns, legalPassages(ground, player));
                List<String> tacticMoves = legalNames(tactics.columns, legalTactics(around, player));
                return new MentorAsk(reason, progression.blockSituation(player, obtained), folder, stuck,
                        moves, ground.key(), ground.words(), passageMoves, y, driver(), craftKey,
                        craftMoves, around.key(), around.words(), tacticMoves,
                        Skills.get().catalogue(), "");
            });
        }

        int goalIndex = active.goals.choose(observation.key(), legalGoals);
        if (goalIndex < 0) {
            // WANDER is always legal, so this cannot happen; bail rather than index nothing.
            return;
        }
        // A column past the built-in moves is a skill the mentor wrote.
        GoalAction action = goalIndex < GoalAction.values().length ? GoalAction.values()[goalIndex] : null;
        Skill goalSkill = action == null ? skillAt(Skill.Layer.GOAL, goalIndex) : null;
        if (action == null && goalSkill == null) {
            return;
        }
        String chosenName = action != null ? action.name() : goalSkill.name();

        // Timing is keyed by the goal as well as the state: the question is not "how long to commit" but
        // "how long to commit to this".
        String timingKey = observation.key() + '/' + chosenName;
        active.timing.learn(timingKey, reward, step.steps(), active.timing.everything);
        Commitment chosen = Commitment.values()[active.timing.choose(timingKey, active.timing.everything)];

        int craft = chooseCraft(craftKey, legalCrafts);
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

        install(action, goalSkill, context, aim, player);
        installCraft(craft, player);
        followingTheMap = headingIsFact && action != null && action.usesGround();

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

        publish(observation.key(), chosenName, chosen.name(), crafting.columns.get(craft));
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
        return waysToMakeTheObjective(legalCrafts).length > 0;
    }

    /**
     * The legal columns that make the very thing the objective scores: the built-in choice for it and
     * any craft skill that says it makes it. Empty when there is nothing to make, or no way to.
     */
    private int[] waysToMakeTheObjective(boolean[] legalCrafts) {
        Resource after = progression.current().flatMap(Phase::scores).orElse(null);
        if (after == null) {
            return new int[0];
        }
        List<Integer> ways = new java.util.ArrayList<>();
        for (CraftChoice choice : CraftChoice.values()) {
            if (choice.resource() == after && legalCrafts[choice.ordinal()]) {
                ways.add(choice.ordinal());
            }
        }
        List<Skill> skills = skillsOf(Skill.Layer.CRAFT);
        for (int i = 0; i < skills.size(); i++) {
            int column = CraftChoice.values().length + i;
            if (column < legalCrafts.length && legalCrafts[column]
                    && Readings.resource(skills.get(i).makes()) == after) {
                ways.add(column);
            }
        }
        return ways.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * What to make: the thing the plan is after when it can be made right now, else what the table has
     * learned. With one way to make it, the way is forced and the table's claim is dropped; with several —
     * the built-in craft and a skill that makes the same thing — the table chooses among them and learns
     * which way pays, which is the whole point of letting a skill make what a built-in already makes.
     */
    private int chooseCraft(String craftKey, boolean[] legalCrafts) {
        int[] ways = waysToMakeTheObjective(legalCrafts);
        if (ways.length == 1) {
            crafting.forget();
            return ways[0];
        }
        if (ways.length > 1) {
            boolean[] among = new boolean[legalCrafts.length];
            for (int way : ways) {
                among[way] = true;
            }
            int column = crafting.choose(craftKey, among);
            return column < 0 ? ways[0] : column;
        }
        int column = crafting.choose(craftKey, legalCrafts);
        return column < 0 ? CraftChoice.NOTHING.ordinal() : column;
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
        if (action == null || !action.usesSpot()) {
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
        if (action == null || !action.usesGround()) {
            // The move now being made goes nowhere, so whatever this table last chose has no continuation.
            active.position.learnTerminal(reward);
            active.position.forget();
            return OptionalDouble.empty();
        }
        // When the loaded map shows the thing — the kind of place the planner named, or the very block
        // the plan is after — the way there is a fact and not a choice: the body is pointed at it, and
        // the table is not credited for a heading it did not pick. It keeps the question for everywhere
        // the map cannot answer. See wayThere() for how the fact is read.
        if (toldHeading.isPresent()) {
            active.position.learnTerminal(reward);
            active.position.forget();
            exploring = Double.NaN;
            return toldHeading;
        }
        if (inTheWrongKindOfPlace(player)) {
            active.position.learnTerminal(reward);
            active.position.forget();
            // Nothing of the kind in the loaded map. Hold a line across what there is, and turn a quarter
            // only when the trail says the line has stopped getting anywhere — and then not again until
            // the trail has had time to say so about the new line.
            long now = System.currentTimeMillis();
            if (Double.isNaN(exploring)) {
                exploring = Math.toRadians(player.getYRot());
                exploringTurnedAt = now;
            } else if ((territory.pinned() || territory.circling())
                    && now - exploringTurnedAt >= EXPLORING_TURN_MILLIS) {
                exploring += Math.PI / 2.0;
                exploringTurnedAt = now;
            }
            return OptionalDouble.of(exploring);
        }
        exploring = Double.NaN;
        String key = pursuit.source()
                + '|' + pursuit.where().band().where(player.getBlockY())
                + '|' + pursuit.where().terrainKey(Travel.biomeAt(player))
                + '|' + territory.state()
                + '|' + context.flags();
        active.position.learn(key, reward, steps, active.position.everything);

        int column = active.position.choose(key, active.position.everything);
        return column < 0 ? OptionalDouble.empty()
                : Ground.values()[column].headingFor(player, territory);
    }

    /**
     * The way to what the plan is after, as far as the loaded map can say, or empty when it cannot.
     *
     * <p>Two readings, the nearer thing first. The block the plan is after, when the map shows one at
     * the surface and the eyes do not: a tree on the map is somewhere to walk, not something to learn.
     * Failing that, the kind of place the planner said the thing is common in, when the body is not in
     * one and the map has one in range. Both are facts about the map rather than lessons, and they are
     * read once a decision here so the legality mask and the heading agree about them.
     */
    private OptionalDouble wayThere(LocalPlayer player, ActionContext context) {
        // Only for what is broken on sight. Stone shows near the surface of most columns and is not
        // walked to but dug to — see Gather#minesWhatItSees — and a body pointed at every exposed face
        // the map showed would zigzag between them instead of sinking the shaft the plan allowed.
        if (context.sighting().kind() != FocusKind.RESOURCE && progression.minesWhatItSees()
                && progression.wanted().isPresent()) {
            OptionalDouble seen = Perception.bearingToBlock(player, progression.wanted().get());
            if (seen.isPresent()) {
                return seen;
            }
        }
        if (inTheWrongKindOfPlace(player)) {
            return Perception.bearingTo(player, pursuit.where().terrain());
        }
        return OptionalDouble.empty();
    }

    /** Whether the planner named the kind of place the thing is found in, and the body is not in one. */
    private boolean inTheWrongKindOfPlace(LocalPlayer player) {
        List<Terrain> terrain = pursuit.where().terrain();
        return !terrain.isEmpty() && "OUT".equals(pursuit.where().terrainKey(Travel.biomeAt(player)));
    }

    /**
     * Whether a journey the map pointed has got where it was pointed: the block the plan is after is in
     * the eyes' reach, or the body is in the kind of place it was sent to find.
     *
     * <p>Only for a move made on the map's word. A body already in the forest that chose to travel
     * anyway has not "arrived" every second it is still in it; that move runs its commitment like any
     * other. What this ends is the walk towards a tree eighty blocks off, which a commitment of ten
     * seconds would otherwise carry straight past the tree — the eyes reach eight blocks, and at four
     * blocks a second the whole window of seeing it is two seconds long.
     */
    private boolean arrived(LocalPlayer player) {
        if (!followingTheMap || installedGoal == null) {
            return false;
        }
        if (progression.wanted().isPresent() && perception.canSee(player, progression.wanted())) {
            return true;
        }
        List<Terrain> terrain = pursuit.where().terrain();
        return !terrain.isEmpty() && "IN".equals(pursuit.where().terrainKey(Travel.biomeAt(player)));
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
        if (passageGoal != null && !engine.isRunning(passageGoal)
                && passageColumn != Passage.CARRY_ON.ordinal()) {
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
        boolean[] legal = legalPassages(here, player);
        String key = here.key();
        if (stuckSince != null) {
            passage.learn(key, passageReward(player), 1, legal);
        }
        // A passage move running counts as stuck, so this layer feeds itself once it has the body: a
        // move a second, forever, while the goal underneath never gets a tick. Twenty seconds without
        // making way in the direction wanted is the end of its turn; it stands aside for a while and
        // the goal has the body again.
        long now = System.currentTimeMillis();
        if (stuckSince == null) {
            passageHeldAt = now;
            passageHeldFrom = player.position();
        } else if (now - passageHeldAt >= PASSAGE_PATIENCE_MILLIS) {
            if (wayMade(passageHeldFrom, player.position(), here.wanted()) < PASSAGE_WAY_MADE) {
                log.debug("Passage layer standing aside: {} s on {} and no way made",
                        (now - passageHeldAt) / 1000, key);
                passage.forget();
                stuckSince = null;
                removePassage();
                passageCooldownUntil = now + PASSAGE_COOLDOWN_MILLIS;
                return;
            }
            passageHeldAt = now;
            passageHeldFrom = player.position();
        }
        stuckSince = Moment.of(player);
        stuckWanting = here.wanted();
        stuckTarget = here.target();
        stuckSought = here.sought();
        stuckBetween = here.aheadBlocks().size();

        int column = passage.choose(key, legal);
        installPassage(column, here);
    }

    /**
     * The terrain in the body's way, or null when there is no such question to ask.
     *
     * <p>Stuck means the committed goal has stopped running, has had nothing to show for a second, or is
     * pushing at something with somewhere to be. A body already being got through a wall by this layer
     * counts as stuck too, so the table keeps being asked — and keeps learning — until the way is open.
     */
    /**
     * Whether the plan itself wants the body lower than it is: a named height below, or a band whose
     * ceiling is below. The plan's word, not the self-rescue's, which alternates up and down when the
     * body is pinned and had DAYLIGHT off the table every other decision in a hole with no sky.
     */
    private boolean plannedDown(LocalPlayer player) {
        int y = player.getBlockY();
        Optional<Phase> current = progression.current();
        if (current.isPresent() && current.get().height().isPresent()) {
            return current.get().height().getAsInt() < y - 1;
        }
        return progression.bounds().ceiling() < y - 1;
    }

    /** Blocks made in the direction wanted since a position: up, down, or across the ground. */
    private static double wayMade(Vec3 from, Vec3 to, Obstruction.Wanted wanted) {
        if (from == null || wanted == null) {
            return 0.0;
        }
        return switch (wanted) {
            case UP -> to.y - from.y;
            case DOWN -> from.y - to.y;
            case FLAT, TOWARD -> Math.hypot(to.x - from.x, to.z - from.z);
        };
    }

    private Obstruction obstruction(LocalPlayer player) {
        // Standing aside after running out of patience: see tendPassage.
        if (System.currentTimeMillis() < passageCooldownUntil) {
            return null;
        }
        // A tactic that has the body keeps it, stalled or not: a tower being refused is not a wall in
        // the way, and a passage move that dug out the block the tower had just laid was the loop the
        // body sat in for a night.
        if (wet != null || installedGoal == null || busy(swimGoal) || busy(craftGoal) || crafting()
                || (tacticGoal != null && engine.isRunning(tacticGoal))
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
    private boolean[] legalPassages(Obstruction here, LocalPlayer player) {
        Passage[] options = Passage.values();
        boolean[] allowed = new boolean[passage.columns.size()];
        for (int i = 0; i < options.length; i++) {
            allowed[i] = options[i].isApplicable(here);
        }
        legalSkills(Skill.Layer.PASSAGE, allowed, player);
        // A skill's own condition says nothing about which way the body wants to go; its steps do. A
        // tower up to the sky, written for a body trapped underground, was legal under a tree with the
        // stone the plan wanted fourteen blocks below.
        List<Skill> skills = skillsOf(Skill.Layer.PASSAGE);
        for (int i = 0; i < skills.size(); i++) {
            int column = options.length + i;
            if (column >= allowed.length || !allowed[column]) {
                continue;
            }
            Skill skill = skills.get(i);
            boolean wantsDown = here.wanted() == Obstruction.Wanted.DOWN;
            boolean wantsUp = here.wanted() == Obstruction.Wanted.UP
                    || here.wanted() == Obstruction.Wanted.TOWARD;
            if ((wantsDown && skill.climbs()) || (wantsUp && skill.digs())) {
                allowed[column] = false;
            }
        }
        return allowed;
    }

    /**
     * Puts the passage choice in place over the committed goal, left alone while unchanged and running.
     *
     * @param column the chosen column: a built-in move, or past those a skill
     */
    private void installPassage(int column, Obstruction here) {
        if (column < 0) {
            column = Passage.CARRY_ON.ordinal();
        }
        if (passageGoal != null && column == passageColumn && engine.isRunning(passageGoal)) {
            return;
        }
        removePassage();
        passageColumn = column;
        MobGoal goal;
        if (column < Passage.values().length) {
            goal = Passage.values()[column].create(here, progression.reserved());
        } else {
            Skill skill = skillAt(Skill.Layer.PASSAGE, column);
            goal = skill == null ? null : skillGoal(skill);
        }
        if (goal != null) {
            passageGoal = goal;
            engine.addGoal(PASSAGE_PRIORITY, goal);
        }
    }

    private void removePassage() {
        if (passageGoal != null) {
            settleSkill(passageGoal);
            engine.removeGoal(passageGoal);
            passageGoal = null;
        }
        passageColumn = Passage.CARRY_ON.ordinal();
    }

    /**
     * Keeps the tactics layer in step with the surroundings, once a second, whatever the commitment is
     * doing.
     *
     * <p>The third layer on its own clock, and the one that sees the whole picture: what is hostile and
     * how much of it, night or day, sky or roof, what is in hand — see {@link Surroundings}. It is asked
     * only while the surroundings call for it, learns every second from what the last second earned,
     * and is settled the moment they go quiet. What it installs sits over everything but the water,
     * because a body being shot at is not chopping a tree however committed it is.
     *
     * <p>Two rewards on top of the ordinary second: a hostile that died, and height gained by a body
     * that was under a roof or down a pit. The death penalty reaches this table like every other, and
     * it is the main lesson here: a night survived is a night that did not end in minus twenty.
     */
    private void tendTactics(LocalPlayer player) {
        if (wet != null) {
            // The water layer owns a wet body. Whatever was being done about the surroundings is
            // settled on what it earned; the water is the surroundings now.
            settleTactics(player, null);
            return;
        }
        boolean stuck = progression.current().isPresent()
                && progression.stepsWithoutProgress() >= STUCK_UNDER_COVER_STEPS;
        Surroundings here = Surroundings.around(player, progression.reserved(), stuck);
        lastSurroundings = here;
        // A shelter is not undone by working: the second the cap goes on, the surroundings read as a
        // roof with nothing hostile in range and stopped calling for anything — and the hole-up was
        // dropped, the goal underneath broke back out, and the night was spent digging the same hole.
        if (!here.demanding() && !committed()) {
            settleTactics(player, here);
            return;
        }
        boolean[] legal = legalTactics(here, player);
        String key = here.key();
        if (tacticSince != null) {
            tactics.learn(key, tacticReward(player, here), 1, legal);
        }
        boolean hold = holdsTactic(here);
        tacticSince = Moment.of(player);
        tacticSeen = here;

        if (hold) {
            // A tactic under way and getting somewhere keeps the body. Choosing again every second
            // tore a six-second hole and a three-block tower down after one second each, over and over,
            // as a fresh table's exploration picked something else — and nothing ever got built. The
            // claim is re-staked on the same column so the next second's reward is still its own.
            tactics.hold(key, tacticColumn);
            return;
        }
        int column = tactics.choose(key, legal);
        installTactic(column, here);
    }

    /**
     * Whether the tactic in flight should be left to finish rather than reconsidered this second.
     *
     * <p>Kept while its goal is running, not done, and not stalled, and while what is hostile has not
     * changed — a skeleton turning up is a new question whatever the tower was doing. A tactic that
     * has finished, given up or stalled hands the second back to the table.
     */
    private boolean holdsTactic(Surroundings here) {
        if (tacticGoal == null || tacticColumn == Tactic.CARRY_ON.ordinal()) {
            return false;
        }
        if (!engine.isRunning(tacticGoal) || tacticGoal.isDone()
                || tacticGoal.stalledTicks() >= STALL_TICKS) {
            return false;
        }
        // A shelter is for whatever turns up: something hostile arriving is not a reason to leave it.
        return committed() || tacticSeen == null || tacticSeen.threat().equals(here.threat());
    }

    /** The surroundings have gone quiet: the last choice is credited with no continuation and let go. */
    private void settleTactics(LocalPlayer player, Surroundings now) {
        if (tacticSince != null) {
            tactics.learnTerminal(tacticReward(player, now));
            tactics.forget();
            tacticSince = null;
            tacticSeen = null;
            if (tacticGoal != null) {
                log.debug("Tactic {} let go: {}", tacticChoice,
                        wet != null ? "in water" : now == null ? "no surroundings" : "quiet: " + now.key());
            }
        }
        removeTactic();
    }

    /**
     * What the last second of dealing with the surroundings was worth: the ordinary reward for the
     * second, plus a lump per hostile that is no longer alive, plus something per block of height a
     * trapped body gained, plus a lump for getting the sky back.
     */
    /** The exposure cost over some seconds: night, open sky, and nothing sheltering the body. */
    private double exposure(int steps) {
        Surroundings here = lastSurroundings;
        if (here == null || here.light() != Surroundings.Light.NIGHT
                || here.cover() != Surroundings.Cover.SKY || sheltering()) {
            return 0.0;
        }
        return -EXPOSED_COST * Math.max(1, steps);
    }

    /** Keeps a choice for tracing a death back. Called by every table as it chooses. */
    private static void remember(Table table, String state, int column) {
        if (column < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (TRACES) {
            TRACES.addLast(new Trace(table, state, column, now));
            while (!TRACES.isEmpty() && now - TRACES.peekFirst().at() > TRACE_KEEP_MILLIS) {
                TRACES.pollFirst();
            }
        }
    }

    /** The death, traced back: each distinct choice of the last two minutes loses value by its age. */
    private void traceDeath() {
        long now = System.currentTimeMillis();
        Map<String, Trace> latest = new LinkedHashMap<>();
        synchronized (TRACES) {
            for (Trace trace : TRACES) {
                latest.put(System.identityHashCode(trace.table()) + "|" + trace.state() + "|" + trace.column(),
                        trace);
            }
            TRACES.clear();
        }
        for (Trace trace : latest.values()) {
            double age = now - trace.at();
            double delta = -DEATH_TRACE * Math.pow(0.5, age / DEATH_TRACE_HALF_LIFE_MILLIS);
            trace.table().table.nudge(trace.state(), trace.column(), delta);
        }
        if (!latest.isEmpty()) {
            log.info("Death traced back to {} choices of the last two minutes", latest.size());
        }
    }

    private double tacticReward(LocalPlayer player, Surroundings now) {
        double reward = score(stepSince(tacticSince, player, 1, 0, 0, List.of(), placedThisStep,
                reclaimedThisStep)) + exposure(1);
        if (tacticSeen == null) {
            return reward;
        }
        long dead = tacticSeen.hostiles().stream().filter(hostile -> !hostile.isAlive()).count();
        reward += KILL_BONUS * dead;
        if (tacticSeen.cover() != Surroundings.Cover.SKY) {
            reward += TACTIC_HEIGHT_WEIGHT * Math.max(0.0, player.getY() - tacticSince.position().y);
            if (now != null && now.cover() == Surroundings.Cover.SKY) {
                reward += DAYLIGHT_BONUS;
            }
        }
        return reward;
    }

    /**
     * Which tactics the surroundings allow, and one rule over them.
     *
     * <p>The rule: at night, with nothing to fight with and something hostile in view, standing about
     * and fighting are off the table. Thirteen deaths in one night were thirteen bodies that fled
     * across a beach with empty hands, and nothing about that is worth a fourteenth to learn. What is
     * left — dig in, tower up, wall off, run — is a real question and a survivable one, and the table
     * keeps it. By day, or armed, or with nothing in view, the table keeps every column.
     */
    private boolean[] legalTactics(Surroundings here, LocalPlayer player) {
        Tactic[] options = Tactic.values();
        boolean[] allowed = new boolean[tactics.columns.size()];
        for (int i = 0; i < options.length; i++) {
            allowed[i] = options[i].isApplicable(here);
        }
        legalSkills(Skill.Layer.TACTIC, allowed, player);
        // Shelter is for something: hostiles in range, or a night in the open, or a night with nothing to
        // fight with. A body that is armed, underground and alone woke this layer only because its
        // objective had stopped getting anywhere, and answered by sealing itself in a hole for two
        // minutes — decisions ticking, nothing moving — which is the opposite of the way out.
        // A hostile that is far off in daylight is not a reason either: a creeper on the horizon had the
        // body sealed in for two minutes with the pickaxe half made.
        boolean night = here.light() == Surroundings.Light.NIGHT;
        boolean threatened = (here.count() > 0 && (night || here.nearest() != Perception.Distance.FAR))
                || (night && (here.cover() == Surroundings.Cover.SKY || !here.armed()));
        if (!threatened) {
            allowed[Tactic.HOLE_UP.ordinal()] = false;
            allowed[Tactic.TOWER.ordinal()] = false;
            allowed[Tactic.WALL_OFF.ordinal()] = false;
        }
        // Getting back to the sky is only a way out when the sky is where the plan wants the body. With
        // iron at Y -10..50 and the surface at 64, a body stuck under a roof was offered DAYLIGHT every
        // second and climbed away from its own objective; the mentor taught against it every time.
        if (here.surface() > progression.bounds().ceiling() || plannedDown(player)) {
            // Nor when the plan wants the body lower than it is: under a canopy the cover reads as a
            // roof, and a body meant to be digging for stone was breaking leaves and stacking dirt.
            allowed[Tactic.DAYLIGHT.ordinal()] = false;
        }
        if (here.light() == Surroundings.Light.NIGHT && !here.armed() && here.count() > 0) {
            allowed[Tactic.CARRY_ON.ordinal()] = false;
            allowed[Tactic.FIGHT.ordinal()] = false;
            boolean any = false;
            for (boolean legal : allowed) {
                any |= legal;
            }
            if (!any) {
                allowed[Tactic.RETREAT.ordinal()] = true;
            }
        }
        return allowed;
    }

    /**
     * Puts the tactic in place over everything but the water, left alone while unchanged and running.
     *
     * <p>A tactic that has finished — the tower is built and held, the hole is capped and waited in —
     * is made again when chosen again, which for a tower is a taller tower and for a wall is a wall on
     * whichever side the nearest hostile is on now. {@link Tactic#CARRY_ON} takes whatever was there
     * away and gives the body back to the commitment.
     */
    private void installTactic(int column, Surroundings here) {
        if (column < 0) {
            column = Tactic.CARRY_ON.ordinal();
        }
        if (tacticGoal != null && column == tacticColumn && engine.isRunning(tacticGoal)) {
            return;
        }
        String name = tactics.columns.get(column);
        if (tacticGoal != null) {
            log.debug("Tactic {} replaced by {} in {}", tactics.columns.get(tacticColumn), name, here.key());
        }
        removeTactic();
        tacticColumn = column;
        MobGoal goal;
        if (column < Tactic.values().length) {
            tacticChoice = Tactic.values()[column];
            goal = tacticChoice.create(here, progression.reserved());
        } else {
            tacticChoice = null;
            Skill skill = skillAt(Skill.Layer.TACTIC, column);
            goal = skill == null ? null : skillGoal(skill);
        }
        if (goal != null) {
            tacticGoal = goal;
            engine.addGoal(TACTIC_PRIORITY, goal);
            log.debug("Tactic {} in {}", name, here.key());
        }
    }

    private void removeTactic() {
        if (tacticGoal != null) {
            settleSkill(tacticGoal);
            engine.removeGoal(tacticGoal);
            tacticGoal = null;
        }
        tacticColumn = Tactic.CARRY_ON.ordinal();
        tacticChoice = Tactic.CARRY_ON;
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
        // A drop the body gave up walking to is left out of its sight for a while: reported again it
        // would be walked to again, and the tree behind it never got chopped.
        long now = System.currentTimeMillis();
        shunned.values().removeIf(until -> until <= now);
        Sighting sighting = perception.look(client, player, progression.wanted(),
                item -> !shunned.containsKey(item), item -> prized(item.getItem()));
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
                // A body sheltering is still, not stuck: nobody should be climbing it out of its hole.
                territory.pinned() && !sheltering(),
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
        boolean[] allowed = new boolean[active.goals.columns.size()];
        for (int i = 0; i < actions.length; i++) {
            allowed[i] = actions[i].isApplicable(context);
        }
        legalSkills(Skill.Layer.GOAL, allowed, Minecraft.getInstance().player);
        underThreat(context, allowed);
        if (fetchesWhatItSees(context) && allowed[GoalAction.MINE.ordinal()]) {
            // The plan named the blocks its resource comes off and the eyes have found one. There is
            // nothing left in the question, so there is nothing left to choose between: everything but
            // breaking it comes off the table. Eating stays, because a body that starves in front of the
            // tree has not gathered anything, and it is only ever legal when the body is hungry with food
            // in hand.
            only(allowed, GoalAction.MINE);
            return allowed;
        }
        if (fetchesWhatItDropped(context) && allowed[GoalAction.APPROACH.ordinal()]) {
            // The log it just cut is lying at its feet. Walking over to it is the rest of the same move,
            // and a table left to decide it strolled off with the log on the ground — then came back for
            // it, then strolled off again. Same rule as the block, one second later.
            only(allowed, GoalAction.APPROACH);
            return allowed;
        }
        goesWhereItIsTold(context, allowed);
        return allowed;
    }

    /**
     * Whether a drop is one the plan is after: the objective's own item, anything on its list, or food,
     * which is never not worth having. What outranks the block in view, and what walking to is a rule.
     */
    private boolean prized(net.minecraft.world.item.ItemStack stack) {
        Resource dropped = Resource.of(stack).orElse(null);
        if (dropped == null) {
            return false;
        }
        Resource own = progression.current().flatMap(Phase::scores).orElse(null);
        return dropped == own || dropped == Resource.FOOD || progression.needs().containsKey(dropped);
    }

    /** Leaves only the one move — and eating, when it was legal, for the reason given at the block rule. */
    private static void only(boolean[] allowed, GoalAction move) {
        GoalAction[] actions = GoalAction.values();
        for (int i = 0; i < allowed.length; i++) {
            allowed[i] = i < actions.length
                    && (actions[i] == move || (actions[i] == GoalAction.EAT && allowed[i]));
        }
    }

    /**
     * Whether the drop in view is one the plan is after, close enough to be worth the walk, and not one
     * the body has already given up on.
     *
     * <p>The rule yields the same way the block rule does: an approach that has spent its time getting
     * no nearer — the log is in the canopy, the cobblestone across a gap — is a drop the body cannot get
     * to, and it is shunned for a while so the eyes report the next thing instead. The first mentor was
     * asked three times about a cobblestone the body could see and not reach, and each time taught a
     * lesson about the terrain, when the answer was to stop looking at it.
     */
    private boolean fetchesWhatItDropped(ActionContext context) {
        Sighting sighting = context.sighting();
        if (sighting.kind() != FocusKind.ITEM || !(sighting.entity() instanceof ItemEntity item)
                || !prized(item.getItem())) {
            return false;
        }
        Resource dropped = Resource.of(item.getItem()).orElse(null);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || Perception.distanceTo(player, sighting) == Perception.Distance.FAR) {
            return false;
        }
        if (installedGoal instanceof ApproachSightingGoal approach && Objects.equals(installedTarget, item)
                && !approach.isDone() && !displaced()
                && (approach.stalledTicks() >= STALL_TICKS || !engine.isRunning(approach))) {
            shunned.put(item, System.currentTimeMillis() + SHUN_MILLIS);
            log.debug("Giving up on a dropped {} the body could not reach", dropped);
            return false;
        }
        return true;
    }

    /**
     * When the way is a fact, walking it is the move.
     *
     * <p>The planner said what kind of place the thing is found in and the body is not in one, or the
     * map shows the very block the plan is after: the position table has already been passed over for
     * the heading, and there is as little left for the goal table in "travel or stroll" as there is for
     * the position table in "which way". A stroll leans the way it is told and still rolls a spot within
     * ten blocks, which over a minute is a ring; a journey holds the line. The body kept choosing the
     * ring — a fresh folder explores at thirty per cent and a journey pays the same per block as a
     * stroll — and that is the whole of why it circled the same field while the forest sat on the map.
     *
     * <p>What stays: travelling, eating, heading for the height the plan wants, and sinking a shaft when
     * the plan allows one — stone is under the mountain as well as in it, and whether to walk to the one
     * or dig to the other is a real question the table keeps. A creature in view is left to the table
     * unless the body is hungry and the creature is dinner, and a hostile never reaches here at all.
     * Something the plan is after in view — a block or a drop — is the other two rules' business.
     */
    private void goesWhereItIsTold(ActionContext context, boolean[] allowed) {
        if (!headingIsFact) {
            return;
        }
        FocusKind kind = context.sighting().kind();
        if (kind == FocusKind.HOSTILE || kind == FocusKind.RESOURCE) {
            return;
        }
        boolean afterFood = progression.current().flatMap(Phase::scores).orElse(null) == Resource.FOOD;
        boolean dinner = kind == FocusKind.PASSIVE && (context.hungry() || afterFood);
        // A drop the plan is not after — a stick out of the canopy — is a freebie, and whether it is
        // worth the steps is the table's to weigh against getting on. The drops the plan is after never
        // reach here; walking to those is the rule above.
        boolean freebie = kind == FocusKind.ITEM;
        GoalAction[] actions = GoalAction.values();
        for (int i = 0; i < allowed.length; i++) {
            // A skill is not a way there: when the way is a fact, walking it is the move.
            boolean kept = i < actions.length && switch (actions[i]) {
                case TRAVEL, EAT, REACH_BAND, DIG_DOWN -> true;
                case ATTACK -> dinner;
                case APPROACH -> dinner || freebie;
                default -> false;
            };
            allowed[i] = allowed[i] && kept;
        }
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
    private boolean[] legalCrafts(ActionContext context, InventoryCensus held, LocalPlayer player) {
        CraftChoice[] choices = CraftChoice.values();
        boolean[] allowed = new boolean[crafting.columns.size()];
        Map<Resource, Integer> needs = progression.needs();
        Resource after = progression.current().flatMap(Phase::scores).orElse(null);
        boolean tableInSight = CraftAtTableGoal.tableInSight(player);
        long now = System.currentTimeMillis();
        String why = "";
        for (int i = 0; i < choices.length; i++) {
            Resource made = choices[i].resource();
            String reason = null;
            if (made == null) {
                allowed[i] = true;
            } else if (craftBackoff.getOrDefault(made, 0L) > now) {
                // It gave up on this a moment ago; the same walk to the same table would give up the same way.
                allowed[i] = false;
                reason = "it gave up on that craft less than a minute ago";
            } else if (needs.containsKey(made) && held.count(made) >= needs.get(made)) {
                // The list already has enough of it. A second wooden pickaxe was crafted at a table forty
                // blocks away because "PICKAXE=1" stayed on the list after the first one was in the bag.
                allowed[i] = false;
                reason = "the list already has enough of it";
            } else if (!choices[i].handheld() && !choices[i].isSmelted() && !tableInSight) {
                // A three-wide recipe needs a table, in the hotbar or standing within reach. Without one
                // it used to fall through to the two-by-two grid and run there forever, never making
                // anything; now it is simply not on offer, and the reason is said, so the list's own
                // CRAFTING_TABLE craft is what gets chosen instead.
                allowed[i] = false;
                reason = "no crafting table in the hotbar or in sight to make it at";
            } else if (choices[i].isSmelted()) {
                // Smelting is not on the recipe book: it is legal when the ore is in the bag and there is
                // something to burn. The goal finds or places the furnace itself.
                allowed[i] = held.count(choices[i].input()) > 0 && hasFuel(held)
                        && context.reserve().allowsMaking(made, held)
                        && keepsTheList(made, held, needs);
                // No ore in the bag is not a craft being refused, it is the mining not done yet: an
                // objective for iron logged "not legal: no RAW_IRON to smelt" from its first second.
                if (!allowed[i] && held.count(choices[i].input()) > 0) {
                    reason = !hasFuel(held) ? "nothing to burn"
                            : !context.reserve().allowsMaking(made, held) ? "the reserve holds the ore back"
                            : "making it would eat into the shopping list";
                }
            } else {
                allowed[i] = context.craftable().contains(made)
                        && context.reserve().allowsMaking(made, held)
                        && keepsTheList(made, held, needs)
                        && !(made == Resource.CRAFTING_TABLE && held.count(made) > 0);
                if (!allowed[i]) {
                    reason = !context.craftable().contains(made)
                            ? "the recipe book cannot make it from the bag (" + ingredientsHeld(made, held) + ")"
                            : !context.reserve().allowsMaking(made, held)
                            ? "the reserve holds its ingredients back (" + context.reserve().kept() + ")"
                            : !keepsTheList(made, held, needs) ? "making it would eat into the shopping list"
                            : "a table is already held or in sight";
                }
            }
            if (made != null && made == after && reason != null) {
                why = reason;
            }
        }
        noteIllegalCraft(after, why);
        // The craft skills past the built-in choices: each applies when its own condition says so, and
        // not when what it makes is already had, or it gave up on it a moment ago.
        legalSkills(Skill.Layer.CRAFT, allowed, player);
        List<Skill> craftSkills = skillsOf(Skill.Layer.CRAFT);
        for (int i = 0; i < craftSkills.size(); i++) {
            int column = choices.length + i;
            if (column >= allowed.length || !allowed[column]) {
                continue;
            }
            Resource made = Readings.resource(craftSkills.get(i).makes());
            if (skillBackoff.getOrDefault(craftSkills.get(i).name(), 0L) > now
                    || (made != null && needs.containsKey(made) && held.count(made) >= needs.get(made))) {
                allowed[column] = false;
            }
        }
        return allowed;
    }

    /** The bag's count of each ingredient, for saying in one line why a recipe would not take. */
    private static String ingredientsHeld(Resource made, InventoryCensus held) {
        StringBuilder out = new StringBuilder();
        made.ingredients().forEach((ingredient, amount) -> out.append(out.isEmpty() ? "" : ", ")
                .append(ingredient).append(' ').append(held.count(ingredient)).append('/').append(amount));
        return out.isEmpty() ? "no recipe known here" : out.toString();
    }

    /**
     * Says, once per reason, why the very thing the objective is after cannot be made right now.
     *
     * <p>The sword the run died without was never attempted, and nothing in the log said why: the
     * craft was simply not among the legal ones, decision after decision. This is that line.
     */
    private void noteIllegalCraft(Resource after, String why) {
        String note = after == null || why.isEmpty() ? "" : after + ": " + why;
        if (note.equals(lastIllegalCraft)) {
            return;
        }
        lastIllegalCraft = note;
        if (!note.isEmpty()) {
            log.info("The objective's own craft is not legal — {}", note);
        }
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
    private void install(GoalAction action, Skill skill, ActionContext context, Aim aim, LocalPlayer player) {
        if (action == null) {
            // A skill: the same skill still running is left to run; anything else is replaced.
            if (installedGoal != null && installedSkill == skill && engine.isRunning(installedGoal)) {
                return;
            }
            uninstall();
            installedSkill = skill;
            installedTarget = null;
            installedGoal = skillGoal(skill);
            engine.addGoal(GOAL_PRIORITY, installedGoal);
            log.debug("Chose skill {} on {} while on {}", skill.name(), context.sighting().kind(),
                    progression.stateKey());
            return;
        }
        // For a move that acts on a block, the block is what identity means: the same verb aimed somewhere
        // else is a different move and has to replace what is running. A heading is not part of it — a
        // journey re-aimed every decision would never get anywhere, which is the thing this is here to fix.
        Object target = action.usesSpot() ? aim.spot()
                : action.usesSighting() ? context.sighting().target() : null;
        if (installedGoal != null && action == installedAction && Objects.equals(target, installedTarget)) {
            // With one exception, and it is not the table's heading. When the way is a fact — the map
            // shows the forest, or the tree — it is read afresh from wherever the body has got to, and a
            // journey kept on the line it set off on walks past what it was sent to. Steering the
            // running journey keeps its stall clock and its detours; only the line changes.
            if (headingIsFact && installedGoal instanceof TravelGoal journey) {
                journey.steer(aim.heading(), player.position());
            }
            return;
        }
        uninstall();
        installedAction = action;
        installedTarget = target;
        installedGoal = action.create(context, aim);
        engine.addGoal(GOAL_PRIORITY, installedGoal);
        log.debug("Chose {} on {} while on {}", action, context.sighting().kind(), progression.stateKey());
    }

    /** The names of the legal columns, for telling the mentor what may actually be chosen. */
    private static List<String> legalNames(List<String> columns, boolean[] legal) {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < columns.size() && i < legal.length; i++) {
            if (legal[i]) {
                names.add(columns.get(i));
            }
        }
        return names;
    }

    /** The name of the move in flight, built in or skill, or null between moves. */
    private String installedName() {
        if (installedAction != null) {
            return installedAction.name();
        }
        return installedSkill == null ? null : installedSkill.name();
    }

    /** The skill behind a column past the built-in moves of a layer, or null when there is none. */
    private static Skill skillAt(Skill.Layer layer, int column) {
        int builtIn = switch (layer) {
            case GOAL -> GoalAction.values().length;
            case PASSAGE -> Passage.values().length;
            case TACTIC -> Tactic.values().length;
            case CRAFT -> CraftChoice.values().length;
        };
        List<Skill> skills = skillsOf(layer);
        int index = column - builtIn;
        return index >= 0 && index < skills.size() ? skills.get(index) : null;
    }

    /**
     * Whether each of a layer's skills applies right now, written into the columns past the built-in
     * moves. Read once per asking from one set of readings, so every skill sees the same second.
     */
    private void legalSkills(Skill.Layer layer, boolean[] allowed, LocalPlayer player) {
        List<Skill> skills = skillsOf(layer);
        if (skills.isEmpty()) {
            return;
        }
        int builtIn = allowed.length - skills.size();
        Readings readings = readings(player);
        for (int i = 0; i < skills.size(); i++) {
            int column = builtIn + i;
            if (column >= 0 && column < allowed.length) {
                boolean applies;
                try {
                    applies = skills.get(i).when().test(readings);
                } catch (RuntimeException e) {
                    applies = false;
                }
                allowed[column] = applies;
            }
        }
    }

    /**
     * The craft the table asked for, made whichever way it can be.
     *
     * <p>A table nearby or in the hotbar means using it, which is the only way the three-wide recipes get
     * made at all; otherwise it is the body's own two-by-two, which needs no limbs and so runs in the
     * background alongside whatever else is going on.
     */
    private void installCraft(int column, LocalPlayer player) {
        // A craft skill that has run its course is settled and taken out; one still going keeps the body,
        // the way a table craft does, and the new choice waits its turn.
        if (craftSkill != null) {
            boolean started = engine.isRunning(craftSkill) || craftSkill.isDone() || craftSkill.failed();
            boolean waitedTooLong = !started
                    && System.currentTimeMillis() - craftSkillAddedAt > CRAFT_SKILL_PENDING_MILLIS;
            if (craftSkill.isDone() || craftSkill.failed() || waitedTooLong) {
                if (craftSkill.failed()) {
                    log.info("Gave up on craft skill {} ({}); not trying again for a minute",
                            craftSkill.skill().name(), craftSkill.failure());
                    skillBackoff.put(craftSkill.skill().name(), System.currentTimeMillis() + CRAFT_BACKOFF_MILLIS);
                }
                removeCraftSkill();
            } else {
                // Running, or still waiting for the body: either way the choice stands.
                return;
            }
        }
        if (column >= CraftChoice.values().length) {
            Skill skill = skillAt(Skill.Layer.CRAFT, column);
            if (skill == null) {
                return;
            }
            if ((craftGoal instanceof CraftAtTableGoal || craftGoal instanceof SmeltGoal)
                    && engine.isRunning(craftGoal) && !craftGoal.isFinished()) {
                return;
            }
            removeCraft();
            craftSkill = skillGoal(skill);
            craftSkillMakes = Readings.resource(skill.makes());
            craftSkillAddedAt = System.currentTimeMillis();
            log.info("Trying craft skill {}", skill.name());
            engine.addGoal(CRAFT_AT_TABLE_PRIORITY, craftSkill);
            return;
        }
        CraftChoice choice = CraftChoice.values()[column];
        if (craftGoal != null && craftGoal.gaveUp()) {
            // It ran and let go without finishing. Putting it straight back is how a sword craft held the
            // body in a shaft for seven minutes; it sits out a while and the plan's own work gets the body.
            log.info("Gave up crafting {}; not trying again for a minute", craftGoal.target());
            craftBackoff.put(craftGoal.target(), System.currentTimeMillis() + CRAFT_BACKOFF_MILLIS);
            removeCraft();
        }
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
        } else if (!choice.handheld()) {
            // Three wide and no table to make it at: the mask should have ruled it out, and the grid in
            // the inventory would only pretend to try.
            return;
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
        if (craftSkill != null && craftSkill.isDone()) {
            removeCraftSkill();
        }
    }

    private void removeCraftSkill() {
        if (craftSkill != null) {
            settleSkill(craftSkill);
            engine.removeGoal(craftSkill);
            craftSkill = null;
            craftSkillMakes = null;
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
            settleSkill(installedGoal);
            engine.removeGoal(installedGoal);
        }
        installedGoal = null;
        installedAction = null;
        installedSkill = null;
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
                state, action, timingChoice, craftChoice, driverName(), stalls,
                crafting.columns, crafting.table.rows(), CraftLog.get().recent(),
                water.columns, water.table.rows(),
                passage.columns, passage.table.rows(),
                tactics.columns, tactics.table.rows()));
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
            traceDeath();
            forget();
        }
        if (ticksDead == 0) {
            // The first tick of being dead, and the one that says so to everyone who wants to know: the
            // run's record, which the planner reads, and the clip that keeps the minute it went wrong in.
            // The server's sentence arrives with the death screen, a tick before the health does, so it is
            // there to be taken; when it is not, the client's own tracker gives a plainer one.
            String cause = DeathNotice.take();
            if (cause.isBlank()) {
                cause = player.getCombatTracker().getDeathMessage().getString();
            }
            log.info("Died: {}", cause);
            progression.died(cause);
            onDeath.accept(cause);
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
        removeTactic();
        tacticSince = null;
        tacticSeen = null;
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
        toldHeading = OptionalDouble.empty();
        headingIsFact = false;
        followingTheMap = false;
        shunned.clear();
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
            this.goals = new Table(columnsFor(Skill.Layer.GOAL), folder.resolve("goals.txt"));
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

        /** The column names, in order. Grows when a skill is added; never shrinks while the run is up. */
        private final List<String> columns;
        private final Path path;
        private final QTable table;
        /** Every column legal, for the tables whose choices are never ruled out. */
        private boolean[] everything;

        private String pendingState;
        private int pendingColumn = -1;

        private Table(List<String> columns, Path path) {
            this.columns = new java.util.ArrayList<>(columns);
            this.path = path;
            this.table = new QTable(columns.size());
            this.everything = new boolean[columns.size()];
            Arrays.fill(this.everything, true);
        }

        /** Another column, at nothing known in every row: a skill the mentor has just written. */
        private void addColumn(String name) {
            if (columns.contains(name)) {
                return;
            }
            columns.add(name);
            table.resize(columns.size());
            everything = new boolean[columns.size()];
            Arrays.fill(everything, true);
        }

        private void load() {
            table.load(path, columns);
        }

        private void save() {
            table.save(path, columns);
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
            remember(this, state, column);
            return column;
        }

        /**
         * Stakes the claim on a column without choosing: the choice was made earlier and is being held
         * to, and the next reward is still its own. No exploration, no decision counted.
         */
        private void hold(String state, int column) {
            pendingState = state;
            pendingColumn = column;
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
