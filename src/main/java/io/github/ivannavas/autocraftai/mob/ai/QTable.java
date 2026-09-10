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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * Tabular Q-learning over {@link Observation} keys and {@link GoalAction} columns.
 *
 * <p>Q-learning over temporally extended actions, which is what a column here is: a goal plus how long it
 * is held for. The update is the semi-Markov one,
 * {@code Q(s,a) += rate * (R + discount^k * max Q(s',a') - Q(s,a))}, where {@code R} is everything the move
 * earned over its whole run and {@code k} is how many steps that took.
 *
 * <p>The exponent is not a detail. A fifteen-second move collects fifteen seconds of reward, so discounting
 * it as if it were one step would make long commitments look better than short ones for no reason other
 * than their length, and the table would learn to stand around doing one thing forever.
 *
 * <p>Choice and update both take a mask of legal actions. Without it the table would spend its exploration
 * budget on moves that cannot be made — there is no approaching a thing that is not there — and the
 * bootstrap term would look up the value of a move the next state does not offer.
 */
@Slf4j
public final class QTable {

    private static final String FORMAT_HEADER = "# autocraft-ai q-table v1";
    private static final String ACTIONS_KEY = "actions";
    private static final String EPSILON_KEY = "epsilon";
    private static final String DECISIONS_KEY = "decisions";

    private static final double LEARNING_RATE = 0.15;
    private static final double DISCOUNT = 0.90;
    private static final double EPSILON_START = 0.30;
    private static final double EPSILON_MIN = 0.05;
    private static final double EPSILON_DECAY = 0.9995;

    private final Map<String, double[]> values = new HashMap<>();
    private final Random random = new Random();
    private int actionCount;
    /**
     * What each column starts at in a row that has not learned it yet: NaN for the row's own mean, else
     * the value given — a skill's prior, so a skill is tried first wherever it applies and what it
     * earns there replaces the guess.
     */
    private double[] initial;

    private double epsilon = EPSILON_START;
    private long decisions;

    public QTable(int actionCount) {
        this.actionCount = actionCount;
        this.initial = unset(actionCount);
    }

    private static double[] unset(int columns) {
        double[] values = new double[columns];
        Arrays.fill(values, Double.NaN);
        return values;
    }

    /** The value a column starts at in every row that has not learned it yet. */
    public void initial(int column, double value) {
        if (column >= 0 && column < actionCount) {
            initial[column] = value;
        }
    }

    /** A row seen for the first time: zero, except where a column has a starting value of its own. */
    private double[] fresh() {
        double[] row = new double[actionCount];
        for (int column = 0; column < actionCount; column++) {
            if (!Double.isNaN(initial[column])) {
                row[column] = initial[column];
            }
        }
        return row;
    }

    public int states() {
        return values.size();
    }

    public double epsilon() {
        return epsilon;
    }

    public long decisions() {
        return decisions;
    }

    /**
     * Every state and what it believes, copied out for another thread to read. Boxing sixty rows once a
     * second is not worth avoiding, and the copy is what makes the overlay safe to serve without a lock.
     */
    public List<QTableSnapshot.Row> rows() {
        return values.entrySet().stream()
                .map(entry -> new QTableSnapshot.Row(
                        entry.getKey(), Arrays.stream(entry.getValue()).boxed().toList()))
                .toList();
    }

    /** Action values for a state, created at zero the first time the state is seen. */
    public double[] valuesFor(String state) {
        double[] row = values.computeIfAbsent(state, key -> fresh());
        if (row.length < actionCount) {
            row = grown(row, actionCount);
            values.put(state, row);
        }
        return row;
    }

    /**
     * Gives the table another column, at zero in every row.
     *
     * <p>What lets a move be added while the run is going — a skill the mentor has just written. Every
     * state it is asked about from now on has a value for it, which is nothing known, which is what
     * the exploration rate is for.
     */
    public void resize(int columns) {
        if (columns <= actionCount) {
            return;
        }
        actionCount = columns;
        initial = Arrays.copyOf(initial, columns);
        Arrays.fill(initial, Math.min(initial.length, columns), columns, Double.NaN);
        values.replaceAll((state, row) -> grown(row, columns));
    }

    /**
     * The width the table has from now on. Narrower only while it holds no rows: a cleared table whose
     * skill columns have gone with the skills; with rows it can only grow, as {@link #resize} does.
     */
    public void width(int columns) {
        if (values.isEmpty()) {
            actionCount = columns;
            initial = unset(columns);
        } else {
            resize(columns);
        }
    }

    /**
     * A row with more columns, the new ones at the row's own mean rather than at zero.
     *
     * <p>Zero was an opinion in disguise. A lesson that marked every built-in move in a row as a dead
     * end left a fresh skill column, at zero, the best thing in the row, and two skills the mentor
     * wrote for the night — wall yourself in, break back out — were chosen by turns all afternoon
     * in rows they were never written for. At the mean, a new column is tried where its writer seeded
     * it and elsewhere only when exploration picks it, which is what "untried" should mean.
     */
    private double[] grown(double[] row, int columns) {
        double[] wider = Arrays.copyOf(row, columns);
        double mean = 0.0;
        if (row.length > 0) {
            for (double value : row) {
                mean += value;
            }
            mean /= row.length;
        }
        for (int column = row.length; column < columns; column++) {
            double starts = column < initial.length ? initial[column] : Double.NaN;
            wider[column] = Double.isNaN(starts) ? mean : starts;
        }
        return wider;
    }

    /**
     * Epsilon-greedy pick among the legal actions. Ties are broken at random so a fresh all-zero state does
     * not always fall to the same column.
     *
     * @return the chosen action index, or -1 if nothing is legal
     */
    public int choose(String state, boolean[] allowed) {
        List<Integer> legal = new ArrayList<>(actionCount);
        // A mask shorter than the table was built before a column was added; the columns it does not
        // know about are simply not on offer this time.
        for (int action = 0; action < actionCount; action++) {
            if (action < allowed.length && allowed[action]) {
                legal.add(action);
            }
        }
        if (legal.isEmpty()) {
            return -1;
        }

        decisions++;
        epsilon = Math.max(EPSILON_MIN, epsilon * EPSILON_DECAY);

        if (random.nextDouble() < epsilon) {
            return legal.get(random.nextInt(legal.size()));
        }

        double[] stateValues = valuesFor(state);
        List<Integer> best = new ArrayList<>(legal.size());
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int action : legal) {
            if (stateValues[action] > bestValue) {
                bestValue = stateValues[action];
                best.clear();
                best.add(action);
            } else if (stateValues[action] == bestValue) {
                best.add(action);
            }
        }
        return best.get(random.nextInt(best.size()));
    }

    /**
     * One learning step with a successor state to bootstrap from.
     *
     * @param reward everything the move earned across its whole run
     * @param steps  how many decisions it ran for, which is what the discount is raised to
     */
    public void update(String state, int action, double reward, int steps,
                       String nextState, boolean[] nextAllowed) {
        double discount = Math.pow(DISCOUNT, Math.max(1, steps));
        learn(state, action, reward + discount * bestValue(nextState, nextAllowed));
    }

    /** One learning step for a transition with no successor, such as dying. */
    public void updateTerminal(String state, int action, double reward) {
        learn(state, action, reward);
    }

    /**
     * Plants a value at a state and action outright, as a lesson taught rather than a reward earned.
     *
     * <p>The mentor uses this to hand the local policy an answer it could not find fast enough on its own:
     * make the escape move attractive in the state it kept failing in, so epsilon-greedy takes it and,
     * because the value is written into the table like any other, keeps it. Ordinary updates adjust it
     * afterwards, so a lesson that turns out wrong is unlearned rather than frozen.
     */
    /** The most a cell may be pushed to by credit arriving after the fact. */
    private static final double MOST_NUDGED = 50.0;

    /**
     * Moves one cell by a delta: credit that arrives after the fact, like a death traced back to the
     * choices of the minutes before it. Bounded, so a run of deaths cannot drive a cell out of reach of
     * the ordinary learning that has to bring it back.
     */
    public void nudge(String state, int action, double delta) {
        double[] stateValues = valuesFor(state);
        if (action >= 0 && action < stateValues.length) {
            stateValues[action] = Math.max(-MOST_NUDGED, Math.min(MOST_NUDGED, stateValues[action] + delta));
        }
    }

    public void seed(String state, int action, double value) {
        if (action >= 0 && action < actionCount) {
            valuesFor(state)[action] = value;
        }
    }

    private void learn(String state, int action, double target) {
        double[] stateValues = valuesFor(state);
        stateValues[action] += LEARNING_RATE * (target - stateValues[action]);
    }

    private double bestValue(String state, boolean[] allowed) {
        double[] stateValues = valuesFor(state);
        double best = Double.NEGATIVE_INFINITY;
        for (int action = 0; action < actionCount; action++) {
            if (action < allowed.length && allowed[action]) {
                best = Math.max(best, stateValues[action]);
            }
        }
        return best == Double.NEGATIVE_INFINITY ? 0.0 : best;
    }

    // --- persistence ------------------------------------------------------------------------------

    /**
     * Reads a table back, matching its columns to this build's by name.
     *
     * <p>A file used to be dropped whole when its column list differed from the build's, which was the
     * right thing while columns only ever changed when the code did. Now a column can be a skill the
     * mentor wrote yesterday, and one can be retired, so the columns are matched by name: what the
     * file has that this build has is kept, in this build's order; a column the file lacks starts at
     * nothing; a column the build lacks is left behind. A renamed move still loses its values, which is
     * what renaming means.
     */
    public void load(Path path, List<String> columns) {
        if (!Files.isRegularFile(path)) {
            log.info("No q-table at {}, starting from nothing", path);
            return;
        }
        try {
            Map<String, double[]> loaded = new HashMap<>();
            double loadedEpsilon = EPSILON_START;
            long loadedDecisions = 0;
            List<String> saved = columns;

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
                    case ACTIONS_KEY -> {
                        saved = List.of(value.split(","));
                        if (!saved.equals(columns)) {
                            log.info("Q-table at {} was written for [{}]; matching its columns to [{}] by name",
                                    path, value, String.join(",", columns));
                        }
                    }
                    case EPSILON_KEY -> loadedEpsilon = Double.parseDouble(value);
                    case DECISIONS_KEY -> loadedDecisions = Long.parseLong(value);
                    default -> loaded.put(key, parseRow(value, saved, columns));
                }
            }

            values.clear();
            values.putAll(loaded);
            epsilon = loadedEpsilon;
            decisions = loadedDecisions;
            log.info("Loaded q-table from {}: {} states, epsilon {}, {} decisions so far",
                    path, values.size(), format(epsilon), decisions);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the q-table at {}, starting from nothing", path, e);
            values.clear();
        }
    }

    /** A saved row rearranged into this build's columns, by name. */
    private double[] parseRow(String value, List<String> saved, List<String> columns) {
        String[] parts = value.split(",");
        double[] row = new double[actionCount];
        for (int i = 0; i < Math.min(parts.length, saved.size()); i++) {
            int column = columns.indexOf(saved.get(i));
            if (column >= 0 && column < actionCount) {
                row[column] = Double.parseDouble(parts[i].trim());
            }
        }
        return row;
    }

    /**
     * Writes the table as one line per state, so it can be read with an editor while it learns.
     *
     * <p>Written beside the real file and moved into place, so a crash halfway through cannot leave hours
     * of learning as a truncated file. The reader survives a corrupt table by starting over, which is
     * exactly the outcome worth never reaching.
     */
    public void save(Path path, List<String> columns) {
        try {
            Files.createDirectories(path.getParent());
            List<String> lines = new ArrayList<>(values.size() + 4);
            lines.add(FORMAT_HEADER);
            lines.add(ACTIONS_KEY + "=" + String.join(",", columns));
            lines.add(EPSILON_KEY + "=" + format(epsilon));
            lines.add(DECISIONS_KEY + "=" + decisions);
            values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> lines.add(entry.getKey() + "=" + formatRow(entry.getValue())));

            Path pending = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(pending, lines, StandardCharsets.UTF_8);
            moveIntoPlace(pending, path);
            log.debug("Saved q-table to {} ({} states)", path, values.size());
        } catch (IOException e) {
            log.warn("Could not write the q-table to {}", path, e);
        }
    }

    private void moveIntoPlace(Path pending, Path path) throws IOException {
        try {
            Files.move(pending, path,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems will not promise atomicity. A plain replace is still better than having
            // written over the old table in place.
            Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Throws away everything learned. The caller is expected to save afterwards, so that the wipe is what
     * survives to the next session rather than the table it replaced.
     */
    public void clear() {
        int forgotten = values.size();
        values.clear();
        epsilon = EPSILON_START;
        decisions = 0;
        log.info("Cleared the q-table, forgetting {} states", forgotten);
    }

    private String formatRow(double[] row) {
        // Padded to the column count, so a row from before a column was added is written whole.
        double[] whole = row.length < actionCount ? Arrays.copyOf(row, actionCount) : row;
        return Arrays.stream(whole).mapToObj(QTable::format).collect(Collectors.joining(","));
    }

    /** Root locale on purpose: a decimal comma would make the row unreadable to its own parser. */
    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
