package io.github.ivannavas.autocraftai.mob.ai;

import java.util.List;

/**
 * An immutable copy of what the brain knows, taken on the client thread and read by the web server's
 * threads.
 *
 * <p>This is the whole of the thread safety story. The live table is a plain {@code HashMap} mutated once a
 * second by the game loop; handing the server a finished copy means neither side needs a lock and the page
 * can never catch the table mid-update.
 */
public record QTableSnapshot(
        List<String> actions,
        List<Row> rows,
        double epsilon,
        long decisions,
        String phase,
        String currentState,
        String currentAction) {

    public QTableSnapshot {
        actions = List.copyOf(actions);
        rows = List.copyOf(rows);
    }

    /** One state and what it believes each action is worth. */
    public record Row(String state, List<Double> values) {
        public Row {
            values = List.copyOf(values);
        }
    }

    public static QTableSnapshot empty(List<String> actions) {
        return new QTableSnapshot(actions, List.of(), 0.0, 0L, "-", null, null);
    }
}
