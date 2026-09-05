package io.github.ivannavas.autocraftai.mob.ai;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import io.github.ivannavas.autocraftai.mob.MobEngine;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.GeneralObjectives;
import io.github.ivannavas.autocraftai.mob.ai.objective.InventoryCensus;
import io.github.ivannavas.autocraftai.mob.ai.objective.Objective;
import io.github.ivannavas.autocraftai.mob.ai.objective.Progression;
import io.github.ivannavas.autocraftai.mob.ai.objective.StepContext;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Decides what the body should be doing and installs the goal that does it.
 *
 * <p>Every {@link #DECISION_INTERVAL_TICKS} ticks it looks at the world, turns that into an
 * {@link Observation}, scores the previous decision, and picks the next {@link GoalAction} from the
 * {@link QTable}. Picking is installing: the previous goal is pulled out of the engine and the chosen one
 * is added in its place, which is the only way goals ever get registered.
 *
 * <p>A second between decisions is deliberate. Goals need time to accomplish something before being judged,
 * and re-deciding every tick would swap the legs out from under a stroll before it took a step.
 *
 * <h2>What it is trying to do</h2>
 * Beat the game, one rung at a time. The reward is the sum of the general objectives, which hold for the
 * whole run, and the current rung of the {@link Progression}, which pays for getting closer to the next
 * thing the run needs. Reaching a rung pays a lump sum that dwarfs both. Changing what the body wants means
 * editing an objective or adding a rung — nothing in this class.
 */
@Slf4j
public final class QLearningBrain {

    /** Ticks between decisions. Twenty is one second. */
    private static final int DECISION_INTERVAL_TICKS = 20;
    /** Priority the chosen goal is installed at. */
    private static final int GOAL_PRIORITY = 2;
    private static final int SAVE_EVERY_DECISIONS = 100;
    private static final int REPORT_EVERY_DECISIONS = 300;
    private static final double DEATH_PENALTY = -20.0;

    private final MobEngine engine;
    private final Perception perception = new Perception();
    private final QTable qTable = new QTable(GoalAction.values().length);
    private final Progression progression = Progression.standard();
    private final Path storage;
    private final String actionSignature;
    private final List<String> actionNames =
            Arrays.stream(GoalAction.values()).map(Enum::name).toList();

    /** Where a copy of what the brain knows goes after every decision. No-op until something wants it. */
    private Consumer<QTableSnapshot> snapshotListener = snapshot -> {
    };

    private Observation lastObservation;
    private int lastAction = -1;
    private GoalAction installedAction;
    private MobGoal installedGoal;
    private Object installedTarget;
    private float lastHealth;
    private Vec3 lastPosition;
    private InventoryCensus lastCensus = InventoryCensus.empty();
    private int ticksSinceDecision;
    private int decisionsSinceSave;
    private int decisionsSinceReport;

    public QLearningBrain(MobEngine engine, Path storage) {
        this.engine = engine;
        this.storage = storage;
        this.actionSignature = String.join(",", actionNames);
        qTable.load(storage, actionSignature);
    }

    public QTable qTable() {
        return qTable;
    }

    public List<String> actionNames() {
        return actionNames;
    }

    /** Hands every later snapshot to {@code listener}, and one now so a watcher starts with something. */
    public void onSnapshot(Consumer<QTableSnapshot> listener) {
        this.snapshotListener = listener;
        publish(null, null);
    }

    private void publish(String currentState, String currentAction) {
        snapshotListener.accept(new QTableSnapshot(
                actionNames, qTable.rows(), qTable.epsilon(), qTable.decisions(),
                progression.stateKey(), currentState, currentAction));
    }

    public Progression progression() {
        return progression;
    }

    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || player.isRemoved()) {
            leaveWorld();
            return;
        }
        if (player.isDeadOrDying()) {
            endEpisode();
            return;
        }
        if (++ticksSinceDecision < DECISION_INTERVAL_TICKS) {
            return;
        }
        ticksSinceDecision = 0;
        decide(client, player);
    }

    /** Writes the table out. Called on the way out of the game as well as periodically. */
    public void save() {
        qTable.save(storage, actionSignature);
    }

    private void decide(Minecraft client, LocalPlayer player) {
        StepContext step = stepSince(player);

        // Score before looking: reaching a rung changes what the body is after, and the sighting that
        // follows should already be taken with the new rung's eyes.
        double climbed = progression.advanceIfComplete(step);
        double reward = score(step) + climbed;

        Sighting sighting = perception.look(client, player, progression.wanted());
        Observation observation = Observation.of(player, sighting, progression.stateKey());
        boolean[] allowed = legalActions(sighting);

        if (lastObservation != null) {
            qTable.update(lastObservation.key(), lastAction, reward, observation.key(), allowed);
        }

        int action = qTable.choose(observation.key(), allowed);
        if (action < 0) {
            // WANDER is always legal, so this cannot happen; bail rather than install a goal from an index
            // that does not exist if it somehow does.
            return;
        }

        install(GoalAction.values()[action], sighting);
        remember(observation, action, step);
        publish(observation.key(), actionNames.get(action));
        maintain(climbed > 0.0);
    }

    /** What changed since the last decision, which is all the objectives are allowed to see. */
    private StepContext stepSince(LocalPlayer player) {
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
                census);
    }

    /** The step's worth against the general objectives and the rung being climbed. */
    private double score(StepContext step) {
        return GeneralObjectives.all().stream()
                .mapToDouble(objective -> objective.score(step))
                .sum()
                + progression.score(step);
    }

    private void remember(Observation observation, int action, StepContext step) {
        lastObservation = observation;
        lastAction = action;
        lastHealth = step.healthAfter();
        lastPosition = step.positionAfter();
        lastCensus = step.after();
    }

    private boolean[] legalActions(Sighting sighting) {
        GoalAction[] actions = GoalAction.values();
        boolean[] allowed = new boolean[actions.length];
        for (int i = 0; i < actions.length; i++) {
            allowed[i] = actions[i].isApplicable(sighting);
        }
        return allowed;
    }

    /**
     * Swaps the engine's goal for the chosen one. Repeating the same action on the same thing leaves the
     * running goal alone: restarting it every second would mean a tree never finishes being chopped.
     */
    private void install(GoalAction action, Sighting sighting) {
        // An action that is mid-way through something it cannot abandon keeps the body. Swapping here is
        // what made mining impossible to learn: every swap threw away the break, so the one action that
        // needed several decisions to pay off was never once paid.
        if (installedGoal != null && engine.isCommitted(installedGoal)) {
            return;
        }
        Object target = action.usesSighting() ? sighting.target() : null;
        if (installedGoal != null && action == installedAction && Objects.equals(target, installedTarget)) {
            return;
        }
        uninstall();
        installedAction = action;
        installedTarget = target;
        installedGoal = action.create(sighting);
        engine.addGoal(GOAL_PRIORITY, installedGoal);
        log.debug("Chose {} on {} while on {}", action, sighting.kind(), progression.stateKey());
    }

    private void uninstall() {
        if (installedGoal != null) {
            engine.removeGoal(installedGoal);
        }
        installedGoal = null;
        installedAction = null;
        installedTarget = null;
    }

    /** @param climbed whether a rung was reached, which is worth writing out straight away */
    private void maintain(boolean climbed) {
        if (climbed || ++decisionsSinceSave >= SAVE_EVERY_DECISIONS) {
            decisionsSinceSave = 0;
            save();
        }
        if (++decisionsSinceReport >= REPORT_EVERY_DECISIONS) {
            decisionsSinceReport = 0;
            log.info("Brain: on {}, {} states known, epsilon {}, {} decisions",
                    progression.stateKey(), qTable.states(),
                    String.format(Locale.ROOT, "%.3f", qTable.epsilon()), qTable.decisions());
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
        qTable.clear();
        forget();
        save();
    }

    /** Dying is the one transition with no successor: score it, then start a fresh episode. */
    private void endEpisode() {
        if (lastObservation != null) {
            qTable.updateTerminal(lastObservation.key(), lastAction, DEATH_PENALTY);
        }
        forget();
    }

    /**
     * Drops the episode without scoring it, for when the body simply is not there any more. The ladder is
     * left where it is: what has been gathered is still gathered, and the next census re-reads it from the
     * inventory anyway.
     */
    private void forget() {
        uninstall();
        // Nothing is being decided any more, so the overlay must stop pointing at a state that is no
        // longer current.
        publish(null, null);
        lastObservation = null;
        lastAction = -1;
        lastCensus = InventoryCensus.empty();
        ticksSinceDecision = 0;
    }
}
