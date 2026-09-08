package io.github.ivannavas.autocraftai.mob.ai;

import java.util.List;

import io.github.ivannavas.autocraftai.mob.ai.objective.planner.PlannerLog;

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
        String phaseReason,
        String currentState,
        String currentAction,
        String currentTiming,
        String currentCraft,
        long interruptions,
        List<String> craftActions,
        List<Row> craftRows,
        List<String> interruptActions,
        List<Row> interruptRows,
        List<CraftLog.Craft> crafts,
        List<PlannerLog.Entry> planner,
        List<String> placementActions,
        List<Row> placementRows,
        List<String> positionActions,
        List<Row> positionRows,
        List<String> waterActions,
        List<Row> waterRows) {

    public QTableSnapshot {
        actions = List.copyOf(actions);
        rows = List.copyOf(rows);
        craftActions = List.copyOf(craftActions);
        craftRows = List.copyOf(craftRows);
        interruptActions = List.copyOf(interruptActions);
        interruptRows = List.copyOf(interruptRows);
        crafts = List.copyOf(crafts);
        planner = List.copyOf(planner);
        placementActions = List.copyOf(placementActions);
        placementRows = List.copyOf(placementRows);
        positionActions = List.copyOf(positionActions);
        positionRows = List.copyOf(positionRows);
        waterActions = List.copyOf(waterActions);
        waterRows = List.copyOf(waterRows);
    }

    /** One state and what it believes each action is worth. */
    public record Row(String state, List<Double> values) {
        public Row {
            values = List.copyOf(values);
        }
    }

    public static QTableSnapshot empty(List<String> actions) {
        return new QTableSnapshot(actions, List.of(), 0.0, 0L, "-", "", null, null, null, null, 0L,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
