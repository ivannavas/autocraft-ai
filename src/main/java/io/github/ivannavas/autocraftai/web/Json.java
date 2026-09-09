package io.github.ivannavas.autocraftai.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import io.github.ivannavas.autocraftai.mob.ai.QTableSnapshot;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.PlannerLog;

/**
 * Writes a {@link QTableSnapshot} as JSON.
 *
 * <p>Hand-rolled rather than pulling in a serialiser: the shapes are fixed and a handful of types wide, and
 * the one thing that actually needs care here is writing numbers under {@link Locale#ROOT}. On a machine
 * with a Spanish locale, {@code String.valueOf} on a double is fine but {@code %f} is not, and a comma
 * would turn every value in the table into a parse error in the browser.
 */
final class Json {

    private Json() {
    }

    /**
     * The machine's own numbers. Bytes go out raw and the browser does the units: a byte count is exact in
     * a double either way, and rounding to megabytes here would fix the panel's wording in this file.
     */
    static String of(Stats stats) {
        return "{\"processCpu\":" + number(stats.processCpu())
                + ",\"systemCpu\":" + number(stats.systemCpu())
                + ",\"heapUsed\":" + stats.heapUsed()
                + ",\"heapMax\":" + stats.heapMax()
                + ",\"ramUsed\":" + stats.ramUsed()
                + ",\"ramTotal\":" + stats.ramTotal()
                + "}";
    }

    static String of(QTableSnapshot snapshot) {
        StringBuilder out = new StringBuilder(1024);
        out.append('{');
        out.append("\"phase\":").append(string(snapshot.phase())).append(',');
        out.append("\"phaseReason\":").append(string(snapshot.phaseReason())).append(',');
        out.append("\"pursuit\":").append(string(snapshot.pursuit())).append(',');
        out.append("\"activeFolder\":").append(string(snapshot.activeFolder())).append(',');
        out.append("\"plan\":").append(plan(snapshot.plan())).append(',');
        out.append("\"currentState\":").append(string(snapshot.currentState())).append(',');
        out.append("\"currentAction\":").append(string(snapshot.currentAction())).append(',');
        out.append("\"currentTiming\":").append(string(snapshot.currentTiming())).append(',');
        out.append("\"currentCraft\":").append(string(snapshot.currentCraft())).append(',');
        out.append("\"epsilon\":").append(number(snapshot.epsilon())).append(',');
        out.append("\"decisions\":").append(snapshot.decisions()).append(',');
        out.append("\"stalls\":").append(snapshot.stalls()).append(',');
        out.append("\"actions\":").append(strings(snapshot.actions())).append(',');
        out.append("\"timingActions\":").append(strings(snapshot.timingActions())).append(',');
        out.append("\"placementActions\":").append(strings(snapshot.placementActions())).append(',');
        out.append("\"positionActions\":").append(strings(snapshot.positionActions())).append(',');
        out.append("\"crafts\":").append(crafts(snapshot)).append(',');
        out.append("\"plannerRevision\":").append(PlannerLog.get().revision()).append(',');
        out.append("\"craftActions\":").append(strings(snapshot.craftActions())).append(',');
        out.append("\"craftRows\":").append(rows(snapshot.craftRows())).append(',');
        out.append("\"waterActions\":").append(strings(snapshot.waterActions())).append(',');
        out.append("\"waterRows\":").append(rows(snapshot.waterRows())).append(',');
        out.append("\"passageActions\":").append(strings(snapshot.passageActions())).append(',');
        out.append("\"passageRows\":").append(rows(snapshot.passageRows())).append(',');
        out.append("\"tacticActions\":").append(strings(snapshot.tacticActions())).append(',');
        out.append("\"tacticRows\":").append(rows(snapshot.tacticRows())).append(',');
        out.append("\"folders\":").append(folders(snapshot.folders()));
        out.append('}');
        return out.toString();
    }

    /** Every folder of tables, in the order they were opened. */
    private static String folders(List<QTableSnapshot.Folder> folders) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        folders.forEach(folder -> joiner.add("{\"name\":" + string(folder.name())
                + ",\"epsilon\":" + number(folder.epsilon())
                + ",\"decisions\":" + folder.decisions()
                + ",\"goals\":" + rows(folder.goals())
                + ",\"timing\":" + rows(folder.timing())
                + ",\"placement\":" + rows(folder.placement())
                + ",\"position\":" + rows(folder.position()) + "}"));
        return joiner.toString();
    }

    /**
     * A plan as the page draws it, or {@code null} when there is none: between orders, and on every line
     * of the planner's conversation that is not an answer.
     */
    private static String plan(QTableSnapshot.PlanView plan) {
        if (plan == null) {
            return "null";
        }
        return "{\"objective\":" + string(plan.objective())
                + ",\"reason\":" + string(plan.reason())
                + ",\"floor\":" + (plan.banded() ? String.valueOf(plan.floor()) : "null")
                + ",\"ceiling\":" + (plan.banded() ? String.valueOf(plan.ceiling()) : "null")
                + ",\"needs\":" + amounts(plan.needs())
                + ",\"reserve\":" + amounts(plan.reserve())
                + ",\"sources\":" + sources(plan.sources()) + "}";
    }

    private static String amounts(List<QTableSnapshot.Amount> amounts) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        amounts.forEach(amount -> joiner.add(
                "{\"item\":" + string(amount.item()) + ",\"amount\":" + amount.amount() + "}"));
        return joiner.toString();
    }

    private static String sources(List<QTableSnapshot.SourceView> sources) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        sources.forEach(source -> joiner.add("{\"block\":" + string(source.block())
                + ",\"tool\":" + string(source.tool())
                + ",\"floor\":" + (source.banded() ? String.valueOf(source.floor()) : "null")
                + ",\"ceiling\":" + (source.banded() ? String.valueOf(source.ceiling()) : "null")
                + ",\"terrain\":" + strings(source.terrain())
                + ",\"ways\":" + strings(source.ways()) + "}"));
        return joiner.toString();
    }

    /**
     * The planner's whole conversation: what was asked and what came back, oldest first.
     *
     * <p>Fetched on its own rather than ridden along with the snapshot. The detail is the entire prompt
     * and the entire reply, which is the only thing that answers "what did it actually say" — and
     * sending a couple of hundred of those on every decision, several times a minute, would be tens of
     * megabytes an hour to say nothing new. The snapshot carries a revision instead, and the page comes
     * back for this when that number moves.
     */
    static String planner(List<PlannerLog.Entry> entries) {
        long now = System.currentTimeMillis();
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        entries.forEach(entry -> joiner.add("{\"source\":" + string(entry.source().name())
                + ",\"kind\":" + string(entry.kind().name())
                + ",\"objective\":" + string(entry.objective())
                + ",\"text\":" + string(entry.text())
                + ",\"detail\":" + string(entry.detail())
                + ",\"plan\":" + plan(entry.plan() == null ? null : QTableSnapshot.PlanView.of(entry.plan()))
                + ",\"ago\":" + Math.max(0L, now - entry.at()) + "}"));
        return joiner.toString();
    }

    private static String crafts(QTableSnapshot snapshot) {
        long now = System.currentTimeMillis();
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        snapshot.crafts().forEach(craft -> joiner.add(
                "{\"item\":" + string(craft.item())
                        + ",\"count\":" + craft.count()
                        + ",\"age\":" + Math.max(0, now - craft.at()) + "}"));
        return joiner.toString();
    }

    private static String rows(java.util.List<QTableSnapshot.Row> source) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (QTableSnapshot.Row row : source) {
            StringJoiner values = new StringJoiner(",", "[", "]");
            row.values().forEach(value -> values.add(number(value)));
            joiner.add("{\"state\":" + string(row.state()) + ",\"values\":" + values + "}");
        }
        return joiner.toString();
    }

    private static String strings(Iterable<String> items) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        items.forEach(item -> joiner.add(string(item)));
        return joiner.toString();
    }

    /**
     * The overlay's own words, grouped: {@code {"locale":"es-ES","action":{"MINE":"picar"},...}}.
     *
     * <p>Sent once when a page connects rather than with every snapshot. It is a couple of kilobytes and
     * it only changes when the player changes the game's language, which a reconnect picks up.
     */
    static String of(String locale, Map<String, Map<String, String>> groups) {
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        joiner.add("\"locale\":" + string(locale));
        groups.forEach((group, words) -> joiner.add(string(group) + ":" + object(words)));
        return joiner.toString();
    }

    private static String object(Map<String, String> words) {
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        words.forEach((key, value) -> joiner.add(string(key) + ":" + string(value)));
        return joiner.toString();
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String string(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
