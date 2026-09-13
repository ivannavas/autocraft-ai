package io.github.ivannavas.autocraftai.mob.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import io.github.ivannavas.autocraftai.mob.ai.net.Features;
import io.github.ivannavas.autocraftai.mob.ai.net.Mlp;
import io.github.ivannavas.autocraftai.mob.ai.net.Replay;
import lombok.extern.slf4j.Slf4j;

/**
 * The same learning rule as {@link QTable}, over a network instead of a row per state.
 *
 * <h2>What changes, and what deliberately does not</h2>
 * The update is still the semi-Markov one — everything the move earned, discounted by how long it took,
 * plus the best the next state offers among the moves it actually allows. Exploration is still per row
 * and still decays with how often that exact state has been chosen from. The legality mask is still
 * obeyed everywhere. A skill is still a column, still starts at its writer's prior, and is still judged
 * by what it earns. None of that was the problem.
 *
 * <p>Two things change. A belief is no longer stored at a key but computed from one, so what is learned
 * about a state reaches every state that shares fields with it — the generalisation the tables could
 * not have and the parent table was a hand-made stand-in for. And a move is no longer learned from once:
 * it goes into {@link Replay} and is drawn again, dozens of times, over the following hour. At fifteen
 * to twenty-eight decisions a minute that second one is the larger of the two.
 *
 * <h2>One network per layer, not one per folder</h2>
 * A folder was a way of keeping what the body learned about wood apart from what it learned about stone,
 * and it cost what it bought: every new pursuit met a resource block in reach for the first time. The
 * network is given the folder as the first field of the key instead — see {@link Features} — so a
 * pursuit new to the run arrives already believing what every other pursuit has learned about a block in
 * reach, and can still learn that its own is different. That is what the {@code inherit} arrangement was
 * reaching for, without the copy.
 *
 * <h2>The overlay still sees rows</h2>
 * Every key the run has chosen in is remembered, and {@link #rows} asks the network what it believes
 * about each. The page renders exactly what it always did; the numbers behind it are now an opinion
 * about a state rather than a record of one. That mattered enough to build: the way this run is
 * diagnosed is by reading rows.
 */
@Slf4j
public final class QNetwork implements Values {

    private static final String FORMAT_HEADER = "# autocraft-ai q-network v1";
    private static final String ACTIONS_KEY = "actions";
    private static final String EPSILON_KEY = "epsilon";
    private static final String DECISIONS_KEY = "decisions";
    private static final String SHAPE_KEY = "shape";
    private static final String WEIGHTS_KEY = "weights";
    private static final String VISITS_PREFIX = "seen|";

    private static final double DISCOUNT = 0.90;
    /** The same rates the tables used, and for the same reasons. See {@link QTable}. */
    private static final double EPSILON_START = 0.15;
    private static final double EPSILON_MIN = 0.05;
    private static final double ROW_DECAY = 0.90;

    /** Hidden width. Small on purpose: the input is five hundred buckets and the evidence is thin. */
    private static final int HIDDEN = 128;
    /**
     * How often the network the targets are read from catches up with the one being trained.
     *
     * <p>Without a second copy the target moves every time the thing chasing it does, and a value that
     * bootstraps from itself runs away — the failure has a name and it is the reason every version of
     * this algorithm since 2015 has had two networks. Two hundred steps is a couple of minutes of a run.
     */
    private static final int TARGET_EVERY = 200;
    /**
     * How often the target actually catches up here. Two hundred steps while a run is going, which is
     * what a live run wants; a sweep of a finished log wants something else entirely — see
     * {@link #targetEvery}.
     */
    private int targetEvery = TARGET_EVERY;
    /**
     * The most a belief may be pushed to by credit arriving after the fact. The table's own bound, kept
     * so a run of deaths cannot drive a move out of reach of the learning that has to bring it back.
     */
    private static final double MOST_NUDGED = 50.0;
    /**
     * The most passes a taught lesson gets, and how near the taught value is near enough.
     *
     * <p>A table wrote the number and was done. Here the value has to be trained in, and how far it has
     * to travel depends on what the state already believed — a lesson worth six landing in a row that
     * sits at minus two is a different distance from one landing in a row that sits at five. A fixed
     * twenty passes got the first of those a third of the way, which was enough to be the best move in
     * the row by luck rather than by having taught anything. So: pass until it is there, and stop.
     */
    private static final int LESSON_PASSES = 120;
    private static final double LESSON_CLOSE = 0.25;

    /**
     * Sets how many training steps pass before the target catches up.
     *
     * <p>Exists for training from a log that is not going to grow. A target chasing every two hundred
     * steps is right while a run is going, where every step brings a move nobody has seen before; over
     * a finished set it is not fitted Q iteration but a fit against a target that keeps moving during
     * the fit. Frozen for a whole sweep, each pass is a proper fit against a fixed target, which is
     * what the method is supposed to be.
     *
     * <p>It should be said plainly that this was added to stop values inflating over repeated sweeps —
     * one pass put the best move at 0.6, three at 8.4, ten at 14.7 — and it did not stop them. The
     * inflation was not the target's doing: a log from a stuck body is three quarters states whose
     * recorded successor is themselves, with no terminal anywhere to anchor the discounting, so
     * {@code r / (1 - 0.9)} is the honest answer to what the data says and ten times the reward is what
     * it comes to. The data was the problem. Freezing the target is still the correct thing to do here
     * and is kept for that reason alone, not for the one it was written for.
     */
    public void targetEvery(int steps) {
        this.targetEvery = Math.max(1, steps);
    }

    private final Mlp online;
    private final Mlp target;
    private final Replay replay;
    private final int batch;
    private final int perDecision;
    private final Random random = new Random();

    /** How often each state has been chosen from, which is what its exploration rate falls with. */
    private final Map<String, Integer> visits = new LinkedHashMap<>();
    /** The buckets for a key, worked out once. Keys repeat constantly and hashing is not free. */
    private final Map<String, int[]> encoded = new HashMap<>();

    private int actionCount;
    private double[] initial;
    private double epsilon = EPSILON_START;
    private long decisions;
    private long trained;

    public QNetwork(int actionCount, int capacity, int batch, int perDecision) {
        this.actionCount = Math.max(1, actionCount);
        this.initial = unset(this.actionCount);
        this.online = new Mlp(Features.WIDTH, HIDDEN, this.actionCount);
        this.target = new Mlp(Features.WIDTH, HIDDEN, this.actionCount);
        this.replay = new Replay(capacity);
        this.batch = Math.max(1, batch);
        this.perDecision = Math.max(0, perDecision);
        online.copyInto(target);
    }

    private static double[] unset(int columns) {
        double[] values = new double[columns];
        Arrays.fill(values, Double.NaN);
        return values;
    }

    private int[] bucketsFor(String state) {
        return encoded.computeIfAbsent(state, Features::of);
    }

    /** What the network believes about a state right now. */
    private double[] valuesFor(String state) {
        int[] on = bucketsFor(state);
        return online.forward(on, Features.scale(on));
    }

    // --- choosing ---------------------------------------------------------------------------------

    @Override
    public int choose(String state, boolean[] allowed) {
        List<Integer> legal = new ArrayList<>(actionCount);
        for (int action = 0; action < actionCount; action++) {
            if (action < allowed.length && allowed[action]) {
                legal.add(action);
            }
        }
        if (legal.isEmpty()) {
            return -1;
        }

        decisions++;
        int seen = visits.merge(state, 1, Integer::sum);
        epsilon = Math.max(EPSILON_MIN, EPSILON_START * Math.pow(ROW_DECAY, seen - 1));

        if (random.nextDouble() < epsilon) {
            return legal.get(random.nextInt(legal.size()));
        }

        double[] values = valuesFor(state);
        List<Integer> best = new ArrayList<>(legal.size());
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int action : legal) {
            double value = values[action];
            if (value > bestValue) {
                bestValue = value;
                best.clear();
                best.add(action);
            } else if (value == bestValue) {
                best.add(action);
            }
        }
        return best.get(random.nextInt(best.size()));
    }

    // --- learning ---------------------------------------------------------------------------------

    @Override
    public void update(String state, int action, double reward, int steps,
                       String nextState, boolean[] nextAllowed) {
        remember(state, action, reward, steps, nextState, nextAllowed, false);
        train();
    }

    @Override
    public void updateTerminal(String state, int action, double reward) {
        remember(state, action, reward, 1, state, new boolean[0], true);
        train();
    }

    private void remember(String state, int action, double reward, int steps,
                          String nextState, boolean[] nextAllowed, boolean terminal) {
        if (action < 0 || action >= actionCount) {
            return;
        }
        // Seen, so the overlay has a row for it. Almost always it was chosen in first and this does
        // nothing; a claim settled after the folder moved on is the case where it was not, and a state
        // the run has learned from and cannot show is a state nobody can diagnose.
        visits.putIfAbsent(state, 0);
        int[] on = bucketsFor(state);
        int[] nextOn = terminal ? on : bucketsFor(nextState);
        replay.add(new Replay.Move(on, Features.scale(on), action, reward, Math.max(1, steps),
                nextOn, Features.scale(nextOn),
                nextAllowed == null ? new boolean[0] : nextAllowed.clone(), terminal));
    }

    /** A batch drawn from the window and one step of Adam on it, as many times as the run is set to. */
    private void train() {
        for (int pass = 0; pass < perDecision; pass++) {
            if (replay.held() == 0) {
                return;
            }
            for (int sample = 0; sample < batch; sample++) {
                Replay.Move move = replay.sample();
                if (move == null || move.action() >= online.outputs()) {
                    continue;
                }
                learn(move);
            }
            online.step();
            if (++trained % targetEvery == 0) {
                online.copyInto(target);
            }
        }
    }

    /**
     * One sample's contribution to the batch.
     *
     * <p>The bootstrap is the double one: which move the next state is worth is decided by the network
     * being trained, and <em>how much</em> it is worth is read off the slower copy. One network doing
     * both picks whichever move it happens to be overestimating and then believes its own overestimate,
     * which is how a body ends up certain that walking into a wall is the best thing available.
     */
    private void learn(Replay.Move move) {
        double bootstrap = 0.0;
        if (!move.terminal()) {
            int best = -1;
            double bestValue = Double.NEGATIVE_INFINITY;
            double[] live = online.forward(move.nextOn(), move.nextScale());
            boolean[] allowed = move.nextAllowed();
            for (int action = 0; action < live.length; action++) {
                if (action < allowed.length && allowed[action] && live[action] > bestValue) {
                    bestValue = live[action];
                    best = action;
                }
            }
            if (best >= 0) {
                double[] slower = target.forward(move.nextOn(), move.nextScale());
                bootstrap = Math.pow(DISCOUNT, move.steps()) * slower[best];
            }
        }
        // The forward that feeds the gradient has to be the last one done, since the activations it
        // leaves behind are what the backward pass reads.
        double[] predicted = online.forward(move.on(), move.scale());
        online.accumulate(move.on(), move.scale(), move.action(),
                predicted[move.action()], move.reward() + bootstrap);
    }

    // --- taught, rather than earned ----------------------------------------------------------------

    /**
     * A lesson planted at a state.
     *
     * <p>The table wrote the number into the cell and that was that. A network has no cell to write, and
     * the translation is better than the original: the lesson is trained in as a handful of passes at
     * that state, so it reaches the states that look like it as well — which is what the coach meant
     * every time it taught one corner of one forest. Ordinary learning moves it afterwards, so a lesson
     * that turns out wrong is unlearned rather than frozen, exactly as before.
     */
    @Override
    public void seed(String state, int action, double value) {
        if (action < 0 || action >= actionCount) {
            return;
        }
        visits.putIfAbsent(state, 0);
        teach(bucketsFor(state), action, value, LESSON_PASSES);
    }

    @Override
    public void nudge(String state, int action, double delta) {
        if (action < 0 || action >= actionCount) {
            return;
        }
        int[] on = bucketsFor(state);
        double now = online.forward(on, Features.scale(on))[action];
        double wanted = Math.max(-MOST_NUDGED, Math.min(MOST_NUDGED, now + delta));
        // Fewer passes than a lesson gets. This is credit arriving after the fact, not somebody's
        // considered answer, and a death traced back two minutes is a hint about a choice rather than
        // a statement about it.
        teach(on, action, wanted, LESSON_PASSES / 4);
    }

    /** Trains one belief towards a value, stopping as soon as it is there. */
    private void teach(int[] on, int action, double value, int passes) {
        double scale = Features.scale(on);
        for (int pass = 0; pass < passes; pass++) {
            double[] predicted = online.forward(on, scale);
            if (Math.abs(predicted[action] - value) <= LESSON_CLOSE) {
                return;
            }
            online.accumulate(on, scale, action, predicted[action], value);
            online.step();
        }
    }

    // --- columns ----------------------------------------------------------------------------------

    @Override
    public void initial(int column, double value) {
        if (column >= 0 && column < actionCount) {
            initial[column] = value;
        }
    }

    @Override
    public void resize(int columns) {
        if (columns <= actionCount) {
            return;
        }
        double prior = columns - 1 < initial.length ? initial[columns - 1] : Double.NaN;
        initial = Arrays.copyOf(initial, columns);
        Arrays.fill(initial, Math.min(actionCount, columns), columns, Double.NaN);
        actionCount = columns;
        online.grow(columns, prior);
        target.grow(columns, prior);
    }

    @Override
    public void width(int columns) {
        if (columns > actionCount) {
            resize(columns);
            return;
        }
        while (actionCount > columns) {
            dropColumn(actionCount - 1);
        }
    }

    @Override
    public void dropColumn(int index) {
        if (index < 0 || index >= actionCount || actionCount <= 1) {
            return;
        }
        actionCount--;
        initial = without(initial, index);
        online.dropOutput(index);
        target.dropOutput(index);
    }

    private static double[] without(double[] row, int index) {
        double[] out = new double[row.length - 1];
        System.arraycopy(row, 0, out, 0, index);
        System.arraycopy(row, index + 1, out, index, row.length - index - 1);
        return out;
    }

    // --- what the overlay reads ---------------------------------------------------------------------

    @Override
    public double epsilon() {
        return epsilon;
    }

    @Override
    public long decisions() {
        return decisions;
    }

    @Override
    public int states() {
        return visits.size();
    }

    @Override
    public List<QTableSnapshot.Row> rows(String prefix) {
        List<QTableSnapshot.Row> out = new ArrayList<>();
        for (String state : List.copyOf(visits.keySet())) {
            if (!state.startsWith(prefix)) {
                continue;
            }
            double[] values = valuesFor(state);
            List<Double> row = new ArrayList<>(values.length);
            for (double value : values) {
                row.add(value);
            }
            out.add(new QTableSnapshot.Row(state.substring(prefix.length()), row));
        }
        return out;
    }

    @Override
    public void clear() {
        int forgotten = visits.size();
        visits.clear();
        encoded.clear();
        replay.clear();
        online.clear();
        online.copyInto(target);
        epsilon = EPSILON_START;
        decisions = 0;
        trained = 0;
        log.info("Cleared the q-network, forgetting what it believed about {} states", forgotten);
    }

    // --- persistence ------------------------------------------------------------------------------

    /**
     * Reads a network back.
     *
     * <p>Columns are matched by name the way a table's are, with one difference that has to be said out
     * loud: a table could keep the values of the columns it recognised and drop the rest, because a
     * value belongs to a cell. A network's parameters are shared between every column, so a file whose
     * column list differs from this build's is refused whole rather than half-read. That is what the
     * transition log is for — the experience survives a change of columns even when the weights do not,
     * and the network can be trained back up from it.
     */
    @Override
    public void load(Path path, List<String> columns) {
        if (!Files.isRegularFile(path)) {
            log.info("No q-network at {}, starting from nothing", path);
            return;
        }
        try {
            String weights = null;
            int savedOutputs = -1;
            double loadedEpsilon = EPSILON_START;
            long loadedDecisions = 0;
            Map<String, Integer> loadedVisits = new LinkedHashMap<>();
            boolean sameColumns = true;

            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                switch (key) {
                    case ACTIONS_KEY -> sameColumns = List.of(value.split(",")).equals(columns);
                    case EPSILON_KEY -> loadedEpsilon = Double.parseDouble(value);
                    case DECISIONS_KEY -> loadedDecisions = Long.parseLong(value);
                    case SHAPE_KEY -> savedOutputs = shapeOutputs(value);
                    case WEIGHTS_KEY -> weights = value;
                    default -> {
                        if (key.startsWith(VISITS_PREFIX)) {
                            loadedVisits.put(key.substring(VISITS_PREFIX.length()),
                                    Integer.parseInt(value.trim()));
                        }
                    }
                }
            }

            if (!sameColumns || weights == null || savedOutputs < 0) {
                log.info("The q-network at {} was written for another set of columns; starting from nothing",
                        path);
                return;
            }
            if (!online.decode(weights, savedOutputs)) {
                log.warn("The q-network at {} does not fit this build's shape; starting from nothing", path);
                online.clear();
                return;
            }
            actionCount = savedOutputs;
            initial = Arrays.copyOf(initial, actionCount);
            online.copyInto(target);
            visits.clear();
            visits.putAll(loadedVisits);
            epsilon = loadedEpsilon;
            decisions = loadedDecisions;
            log.info("Loaded q-network from {}: {} states seen, epsilon {}, {} decisions so far",
                    path, visits.size(), format(epsilon), decisions);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the q-network at {}, starting from nothing", path, e);
            online.clear();
            online.copyInto(target);
        }
    }

    private static int shapeOutputs(String shape) {
        String[] parts = shape.split("x");
        return parts.length == 3 ? Integer.parseInt(parts[2].trim()) : -1;
    }

    @Override
    public void save(Path path, List<String> columns) {
        try {
            Files.createDirectories(path.getParent());
            List<String> lines = new ArrayList<>(visits.size() + 6);
            lines.add(FORMAT_HEADER);
            lines.add(ACTIONS_KEY + "=" + String.join(",", columns));
            lines.add(SHAPE_KEY + "=" + Features.WIDTH + "x" + HIDDEN + "x" + actionCount);
            lines.add(EPSILON_KEY + "=" + format(epsilon));
            lines.add(DECISIONS_KEY + "=" + decisions);
            lines.add(WEIGHTS_KEY + "=" + online.encode());
            visits.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> lines.add(VISITS_PREFIX + entry.getKey() + "=" + entry.getValue()));

            Path pending = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(pending, lines, StandardCharsets.UTF_8);
            try {
                Files.move(pending, path,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("Saved q-network to {} ({} states seen, {} training steps)",
                    path, visits.size(), online.steps());
        } catch (IOException e) {
            log.warn("Could not write the q-network to {}", path, e);
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
