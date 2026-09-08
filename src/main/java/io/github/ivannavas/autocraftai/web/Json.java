package io.github.ivannavas.autocraftai.web;

import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import io.github.ivannavas.autocraftai.mob.ai.QTableSnapshot;

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
        StringBuilder out = new StringBuilder(256 + snapshot.rows().size() * 64);
        out.append('{');
        out.append("\"phase\":").append(string(snapshot.phase())).append(',');
        out.append("\"phaseReason\":").append(string(snapshot.phaseReason())).append(',');
        out.append("\"currentState\":").append(string(snapshot.currentState())).append(',');
        out.append("\"currentAction\":").append(string(snapshot.currentAction())).append(',');
        out.append("\"currentTiming\":").append(string(snapshot.currentTiming())).append(',');
        out.append("\"currentCraft\":").append(string(snapshot.currentCraft())).append(',');
        out.append("\"epsilon\":").append(number(snapshot.epsilon())).append(',');
        out.append("\"decisions\":").append(snapshot.decisions()).append(',');
        out.append("\"interruptions\":").append(snapshot.interruptions()).append(',');
        out.append("\"actions\":").append(strings(snapshot.actions())).append(',');
        out.append("\"crafts\":").append(crafts(snapshot)).append(',');
        out.append("\"planner\":").append(planner(snapshot)).append(',');
        out.append("\"craftActions\":").append(strings(snapshot.craftActions())).append(',');
        out.append("\"craftRows\":").append(rows(snapshot.craftRows())).append(',');
        out.append("\"positionActions\":").append(strings(snapshot.positionActions())).append(',');
        out.append("\"positionRows\":").append(rows(snapshot.positionRows())).append(',');
        out.append("\"placementActions\":").append(strings(snapshot.placementActions())).append(',');
        out.append("\"placementRows\":").append(rows(snapshot.placementRows())).append(',');
        out.append("\"waterActions\":").append(strings(snapshot.waterActions())).append(',');
        out.append("\"waterRows\":").append(rows(snapshot.waterRows())).append(',');
        out.append("\"interruptActions\":").append(strings(snapshot.interruptActions())).append(',');
        out.append("\"interruptRows\":").append(rows(snapshot.interruptRows())).append(',');
        out.append("\"rows\":").append(rows(snapshot));
        out.append('}');
        return out.toString();
    }

    /**
     * The planner's conversation: what was asked and what came back, oldest first.
     *
     * <p>The detail — the whole prompt, the whole reply — goes out with it, because the panel shows it on
     * hover and a summary alone cannot answer the question a viewer actually has, which is "what did it
     * say". It is a couple of kilobytes at most and only changes when the planner is called.
     */
    private static String planner(QTableSnapshot snapshot) {
        long now = System.currentTimeMillis();
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        snapshot.planner().forEach(entry -> joiner.add("{\"kind\":" + string(entry.kind().name())
                + ",\"objective\":" + string(entry.objective())
                + ",\"text\":" + string(entry.text())
                + ",\"detail\":" + string(entry.detail())
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

    private static String rows(QTableSnapshot snapshot) {
        return rows(snapshot.rows());
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
