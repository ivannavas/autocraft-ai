package io.github.ivannavas.autocraftai.mob.ai;

import java.util.List;
import java.util.Map;

import io.github.ivannavas.autocraftai.mob.ai.objective.Bounds;
import io.github.ivannavas.autocraftai.mob.ai.objective.Plan;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.objective.Source;

/**
 * An immutable copy of what the brain knows, taken on the client thread and read by the web server's
 * threads.
 *
 * <p>This is the whole of the thread safety story. The live tables are plain {@code HashMap}s mutated once
 * a second by the game loop; handing the server a finished copy means neither side needs a lock and the
 * page can never catch a table mid-update.
 *
 * <p>Every folder of tables goes out, not only the one in play. The page is read by someone who wants to
 * know what the run has learned, and what it learned about zombies is not less interesting because it is
 * currently after wood. The column names travel once, at the top, since every folder's tables share them.
 */
public record QTableSnapshot(
        List<String> actions,
        List<String> timingActions,
        List<String> placementActions,
        List<String> positionActions,
        List<Folder> folders,
        String activeFolder,
        double epsilon,
        long decisions,
        String phase,
        String phaseReason,
        String pursuit,
        PlanView plan,
        String currentState,
        String currentAction,
        String currentTiming,
        String currentCraft,
        long stalls,
        List<String> craftActions,
        List<Row> craftRows,
        List<CraftLog.Craft> crafts,
        List<String> waterActions,
        List<Row> waterRows,
        List<String> passageActions,
        List<Row> passageRows,
        List<String> tacticActions,
        List<Row> tacticRows) {

    public QTableSnapshot {
        actions = List.copyOf(actions);
        timingActions = List.copyOf(timingActions);
        placementActions = List.copyOf(placementActions);
        positionActions = List.copyOf(positionActions);
        folders = List.copyOf(folders);
        activeFolder = activeFolder == null ? "" : activeFolder;
        craftActions = List.copyOf(craftActions);
        craftRows = List.copyOf(craftRows);
        crafts = List.copyOf(crafts);
        waterActions = List.copyOf(waterActions);
        waterRows = List.copyOf(waterRows);
        passageActions = List.copyOf(passageActions);
        passageRows = List.copyOf(passageRows);
        tacticActions = List.copyOf(tacticActions);
        tacticRows = List.copyOf(tacticRows);
    }

    /** One state and what it believes each action is worth. */
    public record Row(String state, List<Double> values) {
        public Row {
            values = List.copyOf(values);
        }
    }

    /** One pursuit's four tables, with the exploration rate and the decision count of its goal table. */
    public record Folder(String name, double epsilon, long decisions, List<Row> goals, List<Row> timing,
                         List<Row> placement, List<Row> position) {
        public Folder {
            goals = List.copyOf(goals);
            timing = List.copyOf(timing);
            placement = List.copyOf(placement);
            position = List.copyOf(position);
        }
    }

    /** So much of one thing: a line of a shopping list or of a reserve. */
    public record Amount(String item, int amount) {
    }

    /** One source as the planner described it: the block, the tool, and where and how to find it. */
    public record SourceView(String block, String tool, boolean banded, int floor, int ceiling,
                             List<String> terrain, List<String> ways) {
        public SourceView {
            terrain = List.copyOf(terrain);
            ways = List.copyOf(ways);
        }
    }

    /**
     * A plan in plain strings and numbers, which is all a page needs and all that is safe to hand across
     * threads: nothing here points back into the game.
     */
    public record PlanView(String objective, String reason, boolean banded, int floor, int ceiling,
                           List<Amount> needs, List<Amount> reserve, List<SourceView> sources) {
        public PlanView {
            needs = List.copyOf(needs);
            reserve = List.copyOf(reserve);
            sources = List.copyOf(sources);
        }

        public static PlanView of(Plan plan) {
            Bounds band = plan.bounds();
            return new PlanView(plan.objective().name(), plan.objective().reason(),
                    band.bind(), band.floor(), band.ceiling(),
                    amounts(plan.needs()), amounts(plan.reserved().kept()),
                    plan.objective().sources().stream().map(PlanView::source).toList());
        }

        private static List<Amount> amounts(Map<Resource, Integer> listed) {
            return listed.entrySet().stream()
                    .map(entry -> new Amount(entry.getKey().name(), entry.getValue()))
                    .toList();
        }

        private static SourceView source(Source source) {
            Bounds band = source.where().band();
            return new SourceView(source.name(), source.tool().name(),
                    band.bind(), band.floor(), band.ceiling(),
                    source.where().terrain().stream().map(Enum::name).toList(),
                    source.where().ways().stream().map(Enum::name).toList());
        }
    }

    public static QTableSnapshot empty(List<String> actions) {
        return new QTableSnapshot(actions, List.of(), List.of(), List.of(), List.of(), "", 0.0, 0L,
                "-", "", "", null, null, null, null, null, 0L,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of());
    }
}
