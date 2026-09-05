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
 * <p>Plain one-step Q-learning: {@code Q(s,a) += rate * (reward + discount * max Q(s',a') - Q(s,a))}, with
 * an epsilon-greedy choice that decays towards mostly-greedy as the brain gathers experience.
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
    private final int actionCount;

    private double epsilon = EPSILON_START;
    private long decisions;

    public QTable(int actionCount) {
        this.actionCount = actionCount;
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
        return values.computeIfAbsent(state, key -> new double[actionCount]);
    }

    /**
     * Epsilon-greedy pick among the legal actions. Ties are broken at random so a fresh all-zero state does
     * not always fall to the same column.
     *
     * @return the chosen action index, or -1 if nothing is legal
     */
    public int choose(String state, boolean[] allowed) {
        List<Integer> legal = new ArrayList<>(actionCount);
        for (int action = 0; action < actionCount; action++) {
            if (allowed[action]) {
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

    /** One learning step with a successor state to bootstrap from. */
    public void update(String state, int action, double reward, String nextState, boolean[] nextAllowed) {
        learn(state, action, reward + DISCOUNT * bestValue(nextState, nextAllowed));
    }

    /** One learning step for a transition with no successor, such as dying. */
    public void updateTerminal(String state, int action, double reward) {
        learn(state, action, reward);
    }

    private void learn(String state, int action, double target) {
        double[] stateValues = valuesFor(state);
        stateValues[action] += LEARNING_RATE * (target - stateValues[action]);
    }

    private double bestValue(String state, boolean[] allowed) {
        double[] stateValues = valuesFor(state);
        double best = Double.NEGATIVE_INFINITY;
        for (int action = 0; action < actionCount; action++) {
            if (allowed[action]) {
                best = Math.max(best, stateValues[action]);
            }
        }
        return best == Double.NEGATIVE_INFINITY ? 0.0 : best;
    }

    // --- persistence ------------------------------------------------------------------------------

    /**
     * Reads a table back. A file written for a different action set is dropped rather than read, since its
     * columns no longer mean what this build thinks they mean.
     */
    public void load(Path path, String actionSignature) {
        if (!Files.isRegularFile(path)) {
            log.info("No q-table at {}, starting from nothing", path);
            return;
        }
        try {
            Map<String, double[]> loaded = new HashMap<>();
            double loadedEpsilon = EPSILON_START;
            long loadedDecisions = 0;

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
                        if (!value.equals(actionSignature)) {
                            log.warn("Q-table at {} was written for actions [{}] but this build has [{}];"
                                    + " discarding it", path, value, actionSignature);
                            return;
                        }
                    }
                    case EPSILON_KEY -> loadedEpsilon = Double.parseDouble(value);
                    case DECISIONS_KEY -> loadedDecisions = Long.parseLong(value);
                    default -> loaded.put(key, parseRow(value));
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

    private double[] parseRow(String value) {
        String[] parts = value.split(",");
        double[] row = new double[actionCount];
        for (int i = 0; i < Math.min(parts.length, actionCount); i++) {
            row[i] = Double.parseDouble(parts[i].trim());
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
    public void save(Path path, String actionSignature) {
        try {
            Files.createDirectories(path.getParent());
            List<String> lines = new ArrayList<>(values.size() + 4);
            lines.add(FORMAT_HEADER);
            lines.add(ACTIONS_KEY + "=" + actionSignature);
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
        return Arrays.stream(row).mapToObj(QTable::format).collect(Collectors.joining(","));
    }

    /** Root locale on purpose: a decimal comma would make the row unreadable to its own parser. */
    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
