package io.github.ivannavas.autocraftai.mob.ai;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import io.github.ivannavas.autocraftai.mob.MobEngine;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.GeneralObjectives;
import io.github.ivannavas.autocraftai.mob.ai.objective.InventoryCensus;
import io.github.ivannavas.autocraftai.mob.ai.objective.Objective;
import io.github.ivannavas.autocraftai.mob.ai.objective.Progression;
import io.github.ivannavas.autocraftai.mob.ai.objective.StepContext;
import io.github.ivannavas.autocraftai.mob.goal.CraftAtTableGoal;
import io.github.ivannavas.autocraftai.mob.goal.CraftGoal;
import io.github.ivannavas.autocraftai.mob.goal.CraftingGoal;
import io.github.ivannavas.autocraftai.mob.goal.MineSightingGoal;
import io.github.ivannavas.autocraftai.mob.goal.PlaceBlockGoal;
import io.github.ivannavas.autocraftai.mob.goal.RandomStrollGoal;
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
 *   <li><b>interrupts</b>: whether a move that is going nowhere should be cut short, and with what.</li>
 * </ul>
 *
 * <p>One table over the cross-product would be {@code 6 x 3 x 6 x 4} columns, and every new goal would
 * multiply the lot again. Split, a new goal is one column and a new commitment level is one column. The three learn
 * from the same reward over the same move, which does mean none of them can represent an interaction the
 * others cannot see — the price of the split, and the reason it scales.
 *
 * <h2>Primary and secondary</h2>
 * The goal table picks something that needs the body. Crafting needs neither legs nor eyes, so it is not a
 * rival to that choice: it is installed alongside and the engine runs both, which is how the body can be
 * fleeing a creeper and turning logs into planks at once. Anything that needs the body still and aimed
 * cannot be a secondary, and the engine's control claims are what enforce that rather than a rule here.
 *
 * <h2>Interruptions, and who pays for them</h2>
 * A commitment is an estimate, and estimates are wrong. When the body stops getting anywhere — walled in,
 * or simply not moving — the interrupt table gets asked whether to cut the move short and what to do
 * instead. If it does, the <em>timing</em> table is charged {@link #INTERRUPTION_PENALTY} on top of
 * whatever the move earned, because the thing that was wrong was the length: it committed fifteen seconds
 * to something that stopped paying after three.
 */
@Slf4j
public final class QLearningBrain {

    /** Ticks in one step. Twenty is one second, and a step is the unit every commitment is counted in. */
    private static final int STEP_TICKS = 20;
    /** Steps a move is allowed to sit with a finished goal before it is cut short. */
    private static final int IDLE_GRACE_STEPS = 2;
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
    /** Charged to the timing table when a move has to be cut short: its estimate is what failed. */
    private static final double INTERRUPTION_PENALTY = -3.0;
    /** Under this much ground covered in a step, with legs engaged, the body is going nowhere. */
    private static final double STUCK_DISTANCE = 0.5;
    /** Ticks spent on the death screen before asking to come back. Long enough to see what killed you. */
    private static final int RESPAWN_DELAY_TICKS = 40;

    private final MobEngine engine;
    private final Perception perception = new Perception();
    private final Progression progression = Progression.standard();

    private final Table goals;
    private final Table timing;
    private final Table crafting;
    private final Table interrupts;
    private final List<Table> tables;

    /** Where a copy of what the brain knows goes after every decision. No-op until something wants it. */
    private Consumer<QTableSnapshot> snapshotListener = snapshot -> {
    };

    private Observation lastObservation;
    private Commitment commitment;
    private int stepsRun;
    private int idleSteps;

    private GoalAction installedAction;
    private MobGoal installedGoal;
    private Object installedTarget;
    private CraftingGoal craftGoal;

    private Vec3 stepPosition;
    private long interruptionCount;

    private float lastHealth;
    private Vec3 lastPosition;
    private InventoryCensus lastCensus = InventoryCensus.empty();
    private InventoryCensus obtained = InventoryCensus.empty();
    private InventoryCensus previousStepCensus;
    private int ticksSinceStep;
    private int decisionsSinceSave;
    private int decisionsSinceReport;
    private int ticksDead;

    public QLearningBrain(MobEngine engine, Path directory) {
        this.engine = engine;
        this.goals = new Table(names(GoalAction.values()), directory.resolve("goals.txt"));
        this.timing = new Table(names(Commitment.values()), directory.resolve("timing.txt"));
        this.crafting = new Table(names(CraftChoice.values()), directory.resolve("crafting.txt"));
        this.interrupts = new Table(names(Interruption.values()), directory.resolve("interrupts.txt"));
        this.tables = List.of(goals, timing, crafting, interrupts);
        tables.forEach(Table::load);
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    public List<String> actionNames() {
        return goals.columns;
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
            leaveWorld();
            return;
        }
        ticksDead = 0;
        if (++ticksSinceStep < STEP_TICKS) {
            return;
        }
        ticksSinceStep = 0;
        stepsRun++;
        // Counted once per step and nowhere else: stepSince is called more than once in a step that ends
        // in an interruption, and accumulating there would count the same gain twice.
        tallyGains(player);
        retireFinishedCraft();
        boolean goingNowhere = goingNowhere(player);
        if (stillHolding()) {
            // A commitment only gets reconsidered when it has stopped paying its way. Asking every step
            // regardless would turn the interrupt table into a second goal table with worse information.
            //
            // A goal that has declared itself uninterruptable is never asked about at all. Without that
            // line the interrupt table walked straight through the one protection mining has: a block
            // half broken looks stationary, the rescue pulls the goal, and uninstalling it throws the
            // progress away — which is why wood almost never finished being chopped.
            if (goingNowhere && !engine.isCommitted(installedGoal)) {
                Interruption rescue = considerInterruption(client, player);
                if (rescue != Interruption.CONTINUE) {
                    interrupt(client, player, rescue);
                }
            }
            return;
        }
        decide(client, player);
    }

    /**
     * Whether something is asking the legs to move and the body is not moving. Walled in and shoving at the
     * wall looks exactly like this, and so does standing in a hole.
     *
     * <p>The test is a destination, not a stopped body. Plenty of goals stand still on purpose — mining
     * plants its feet to swing, crafting at a table stands at it — and reading that as being stuck was how
     * the interrupt table came to sit on top of every attempt to chop wood. A body with nowhere it is
     * trying to be cannot be failing to get there.
     */
    private boolean goingNowhere(LocalPlayer player) {
        Vec3 now = player.position();
        Vec3 before = stepPosition;
        stepPosition = now;
        if (before == null || installedGoal == null || !engine.body().moveControl().hasDestination()) {
            return false;
        }
        double dx = now.x - before.x;
        double dz = now.z - before.z;
        return Math.sqrt(dx * dx + dz * dz) < STUCK_DISTANCE;
    }

    /** Writes every table out. Called on the way out of the game as well as periodically. */
    public void save() {
        tables.forEach(Table::save);
    }

    /**
     * Whether the move in flight keeps the body for another step.
     *
     * <p>It runs out its committed length, with two exceptions. A goal that cannot be abandoned half way —
     * a block coming apart — holds on past the end of its commitment rather than losing the work. And a
     * goal that has finished with nothing left to restart gives the rest of the time back, because sitting
     * out ten idle seconds would teach the table that the length was the mistake.
     */
    private boolean stillHolding() {
        if (commitment == null) {
            return false;
        }
        if (installedGoal != null && engine.isCommitted(installedGoal)) {
            return true;
        }
        if (stepsRun >= commitment.steps()) {
            return false;
        }
        if (installedGoal != null && !engine.isRunning(installedGoal)) {
            return ++idleSteps < IDLE_GRACE_STEPS;
        }
        idleSteps = 0;
        return true;
    }

    private void decide(Minecraft client, LocalPlayer player) {
        StepContext step = stepSince(player, Math.max(1, stepsRun));

        // Score before looking: reaching a rung changes what the body is after, and the sighting that
        // follows should already be taken with the new rung's eyes.
        double climbed = progression.advanceIfComplete(step);
        double reward = score(step) + climbed;

        ActionContext context = surroundings(client, player);
        Observation observation = Observation.of(player, context, progression.stateKey());

        boolean[] legalGoals = legalGoals(context);
        boolean[] legalCrafts = legalCrafts(context);

        // All three learn from the same reward over the same move: each one's share of the credit is
        // whatever its own column was doing while that reward was earned.
        String craftKey = CraftSituation.key(progression.stateKey(), step.after());
        goals.learn(observation.key(), reward, step.steps(), legalGoals);
        crafting.learn(craftKey, reward, step.steps(), legalCrafts);

        int goalIndex = goals.choose(observation.key(), legalGoals);
        if (goalIndex < 0) {
            // WANDER is always legal, so this cannot happen; bail rather than index nothing.
            return;
        }
        GoalAction action = GoalAction.values()[goalIndex];

        // Timing is keyed by the goal as well as the state: the question is not "how long to commit" but
        // "how long to commit to this".
        String timingKey = observation.key() + '/' + action.name();
        timing.learn(timingKey, reward, step.steps(), timing.everything);
        Commitment chosen = Commitment.values()[timing.choose(timingKey, timing.everything)];

        CraftChoice craft = CraftChoice.values()[crafting.choose(craftKey, legalCrafts)];

        install(action, context);
        installCraft(craft, player);

        commitment = chosen;
        stepsRun = 0;
        idleSteps = 0;
        lastObservation = observation;
        lastHealth = step.healthAfter();
        lastPosition = step.positionAfter();
        lastCensus = step.after();

        publish(observation.key(), action.name(), chosen.name(), craft.name());
        maintain(climbed > 0.0);
    }

    /**
     * Asks the interrupt table what to do about a move that has stopped getting anywhere.
     *
     * <p>Keyed on the goal that is stuck and on what is around to do about it, not on the full observation:
     * being walled in while fleeing a zombie and being walled in while walking to a tree are the same
     * problem, and splitting them would make it learn the answer twice.
     */
    private Interruption considerInterruption(Minecraft client, LocalPlayer player) {
        ActionContext context = surroundings(client, player);
        boolean[] legal = legalInterruptions(context);
        String key = (installedAction == null ? "-" : installedAction.name()) + "|stuck|" + context.flags();

        // Credit the last call's choice before making a new one, with what the move has earned since. A
        // fixed zero here was the first version and it taught this table precisely nothing: every column
        // stayed at zero, so every pick was a coin toss dressed up as a policy.
        interrupts.learnTerminal(score(stepSince(player, Math.max(1, stepsRun))));

        int column = interrupts.choose(key, legal);
        return column < 0 ? Interruption.CONTINUE : Interruption.values()[column];
    }

    /** Rescues need something to act on: a wall to dig, blocks to build with. Wandering off always works. */
    private boolean[] legalInterruptions(ActionContext context) {
        Interruption[] options = Interruption.values();
        boolean[] allowed = new boolean[options.length];
        for (int i = 0; i < options.length; i++) {
            allowed[i] = switch (options[i]) {
                case CONTINUE, WANDER -> true;
                case MINE_WALL -> context.walled();
                case PLACE -> context.hasBlocks();
            };
        }
        return allowed;
    }

    /**
     * Cuts the move short and puts the rescue in its place.
     *
     * <p>The move is closed out the way any move is, with one difference: the timing table is charged for
     * having committed to a length that had to be abandoned. The goal table is not charged and not
     * credited — the rescue is not its choice, so it keeps no pending claim on this move.
     */
    private void interrupt(Minecraft client, LocalPlayer player, Interruption rescue) {
        interruptionCount++;
        StepContext step = stepSince(player, Math.max(1, stepsRun));
        double climbed = progression.advanceIfComplete(step);
        double reward = score(step) + climbed;

        ActionContext context = surroundings(client, player);
        Observation observation = Observation.of(player, context, progression.stateKey());

        goals.learn(observation.key(), reward, step.steps(), legalGoals(context));
        crafting.learn(CraftSituation.key(progression.stateKey(), step.after()), reward, step.steps(),
                legalCrafts(context));
        timing.learn(observation.key(), reward + INTERRUPTION_PENALTY, step.steps(), timing.everything);
        goals.forget();

        log.debug("Interrupting {} with {}", installedAction, rescue);
        installRescue(rescue, context);

        // A rescue gets the shortest commitment there is: it exists to unstick the body, and whether it
        // worked is a question worth asking again in a second rather than in ten.
        commitment = Commitment.SHORT;
        stepsRun = 0;
        idleSteps = 0;
        lastObservation = observation;
        lastHealth = step.healthAfter();
        lastPosition = step.positionAfter();
        lastCensus = step.after();
        publish(observation.key(), rescue.name(), commitment.name(), "NOTHING");
    }

    private void installRescue(Interruption rescue, ActionContext context) {
        uninstall();
        installedAction = null;
        installedTarget = null;
        installedGoal = switch (rescue) {
            case MINE_WALL -> new MineSightingGoal(Sighting.ofBlock(FocusKind.BLOCK, context.wall()));
            case PLACE -> new PlaceBlockGoal();
            default -> new RandomStrollGoal();
        };
        engine.addGoal(GOAL_PRIORITY, installedGoal);
    }

    /** Everything around the body, gathered once so the sighting and the map agree with each other. */
    private ActionContext surroundings(Minecraft client, LocalPlayer player) {
        return new ActionContext(
                perception.look(client, player, progression.wanted()),
                Recipes.craftableNow(player),
                Perception.wallAhead(player),
                PlaceBlockGoal.hotbarSlotWithBlock(player) >= 0);
    }

    /** What changed since the last decision, which is all the objectives are allowed to see. */
    private StepContext stepSince(LocalPlayer player, int steps) {
        InventoryCensus census = InventoryCensus.of(player.getInventory());
        Vec3 position = player.position();
        boolean fresh = lastObservation == null;
        return new StepContext(
                player,
                fresh ? player.getHealth() : lastHealth,
                player.getHealth(),
                fresh ? position : lastPosition,
                position,
                fresh ? census : lastCensus,
                census,
                obtained,
                steps);
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
            obtained = obtained.plusGains(previousStepCensus, now);
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
        return allowed;
    }

    /** Making nothing is always on the table; making a thing needs the ingredients for it. */
    private boolean[] legalCrafts(ActionContext context) {
        CraftChoice[] choices = CraftChoice.values();
        boolean[] allowed = new boolean[choices.length];
        for (int i = 0; i < choices.length; i++) {
            allowed[i] = !choices[i].makesSomething() || context.craftable().contains(choices[i].resource());
        }
        return allowed;
    }

    /**
     * Swaps the engine's goal for the chosen one. Repeating the same action on the same thing leaves the
     * running goal alone: restarting it every decision would mean a tree never finishes being chopped.
     */
    private void install(GoalAction action, ActionContext context) {
        // A goal mid-way through something it cannot abandon keeps the body. Swapping here is what made
        // mining impossible to learn: every swap threw away the break.
        if (installedGoal != null && engine.isCommitted(installedGoal)) {
            return;
        }
        Object target = action.usesSighting() ? context.sighting().target() : null;
        if (installedGoal != null && action == installedAction && Objects.equals(target, installedTarget)) {
            return;
        }
        uninstall();
        installedAction = action;
        installedTarget = target;
        installedGoal = action.create(context);
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
        removeCraft();
        if (!choice.makesSomething()) {
            return;
        }
        if (CraftAtTableGoal.tableAvailable(player)) {
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
            log.info("Brain: on {}, goals {} states, timing {}, crafting {}, interrupts {} ({} fired),"
                            + " epsilon {}, {} decisions",
                    progression.stateKey(), goals.table.states(), timing.table.states(),
                    crafting.table.states(), interrupts.table.states(), interruptionCount,
                    String.format(Locale.ROOT, "%.3f", goals.table.epsilon()), goals.table.decisions());
        }
    }

    private void publish(String state, String action, String timingChoice, String craftChoice) {
        snapshotListener.accept(new QTableSnapshot(
                goals.columns, goals.table.rows(), goals.table.epsilon(), goals.table.decisions(),
                progression.stateKey(), state, action, timingChoice, craftChoice, interruptionCount,
                crafting.columns, crafting.table.rows(),
                interrupts.columns, interrupts.table.rows(), CraftLog.get().recent()));
    }

    /**
     * Dying is the one transition with no successor: score it, then start a fresh episode.
     *
     * <p>And then get up. Nothing else is going to press the button, and a body left on the death screen
     * ends the run there for good.
     */
    private void endEpisode(Minecraft client, LocalPlayer player) {
        if (lastObservation != null) {
            tables.forEach(table -> table.learnTerminal(DEATH_PENALTY));
            forget();
        }
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
        tables.forEach(table -> table.table.clear());
        CraftLog.get().clear();
        forget();
        save();
    }

    /** Drops the episode without scoring it, for when the body simply is not there any more. */
    private void forget() {
        uninstall();
        removeCraft();
        tables.forEach(Table::forget);
        lastObservation = null;
        lastCensus = InventoryCensus.empty();
        // Dying drops everything, and leaving takes the body with it. Either way the total is re-seeded
        // from whatever the next census finds, so the ladder never claims a pickaxe that is on the floor.
        obtained = InventoryCensus.empty();
        previousStepCensus = null;
        commitment = null;
        stepsRun = 0;
        idleSteps = 0;
        ticksSinceStep = 0;
        stepPosition = null;
        publish(null, null, null, null);
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

        private void forget() {
            pendingState = null;
            pendingColumn = -1;
        }
    }
}
