package io.github.ivannavas.autocraftai.mob.ai.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import io.github.ivannavas.autocraftai.mob.ai.Experience;
import io.github.ivannavas.autocraftai.mob.ai.QNetwork;

/**
 * Trains a network from moves already made, without the game.
 *
 * <p>This is what {@link Experience} is for. Under the tables, changing anything about how the learning
 * worked meant throwing away every hour the body had ever played, because the only record of those hours
 * was a summary computed under the old rules — and at fifteen to twenty-eight decisions a minute, an
 * hour is not many decisions to be throwing away. With the moves themselves on disk, a change of columns,
 * a change of shape, a bug found in the update, all become this: read the file, train, write the weights,
 * carry on. Minutes instead of nights.
 *
 * <p>It deliberately trains through the ordinary {@link QNetwork} rather than reimplementing the update
 * here. Two copies of a learning rule drift apart, and the one that gets the attention is never the one
 * the body is actually using.
 *
 * <p>Run it with the mod's own classes on the classpath:
 * <pre>
 * java -cp build/classes/java/main:&lt;deps&gt; io.github.ivannavas.autocraftai.mob.ai.net.Rehearse \
 *     --moves run/config/autocraft-ai/experience \
 *     --layer goals \
 *     --columns WANDER,APPROACH,FLEE,WATCH,MINE,PLACE,TRAVEL,DIG_DOWN,REACH_BAND,ATTACK,EAT \
 *     --out run/config/autocraft-ai/net/goals.net \
 *     --epochs 4
 * </pre>
 * The column list is the {@code actions=} line of the file being replaced, so that what is trained lines
 * up with what the run will read.
 */
public final class Rehearse {

    private Rehearse() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = options(args);
        Path moves = Path.of(options.getOrDefault("moves", "run/config/autocraft-ai/experience"));
        String layer = options.getOrDefault("layer", "goals");
        List<String> columns = List.of(options.getOrDefault("columns", "").split(","));
        Path out = Path.of(options.getOrDefault("out", layer + ".net"));
        int epochs = Integer.parseInt(options.getOrDefault("epochs", "4"));
        int capacity = Integer.parseInt(options.getOrDefault("replay", "200000"));
        int batch = Integer.parseInt(options.getOrDefault("batch", "32"));
        int passes = Integer.parseInt(options.getOrDefault("passes", "1"));

        if (columns.size() < 2) {
            System.err.println("--columns is the actions= line of the file being replaced, comma separated");
            return;
        }

        List<String[]> read = read(moves, layer, columns);
        if (read.isEmpty()) {
            System.err.println("No moves for layer " + layer + " under " + moves);
            return;
        }
        System.out.printf(Locale.ROOT, "%d moves of layer %s, %d columns%n", read.size(), layer, columns.size());

        QNetwork network = new QNetwork(columns.size(), capacity, batch, passes);
        for (int epoch = 1; epoch <= epochs; epoch++) {
            for (String[] move : read) {
                int column = columns.indexOf(move[1]);
                if (column < 0) {
                    continue;
                }
                boolean terminal = Boolean.parseBoolean(move[6]);
                if (terminal) {
                    network.updateTerminal(move[0], column, Double.parseDouble(move[2]));
                } else {
                    network.update(move[0], column, Double.parseDouble(move[2]),
                            Integer.parseInt(move[3]), move[4], mask(move[5], columns.size()));
                }
            }
            System.out.printf(Locale.ROOT, "  epoch %d of %d done%n", epoch, epochs);
        }
        network.save(out, columns);
        System.out.println("Wrote " + out);
    }

    /**
     * The moves of one layer, as {@code [state, action, reward, steps, next, legal, terminal]}.
     *
     * <p>Read with a hand-written scan rather than a JSON library, so that this can be run with nothing
     * on the classpath but the mod's own classes. The lines are written by {@link Experience} and have
     * exactly one shape.
     */
    private static List<String[]> read(Path moves, String layer, List<String> columns) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(moves)) {
            try (Stream<Path> found = Files.list(moves)) {
                found.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .sorted()
                        .forEach(files::add);
            }
        } else if (Files.isRegularFile(moves)) {
            files.add(moves);
        }

        List<String[]> out = new ArrayList<>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.startsWith("{") || !field(line, "layer").equals(layer)) {
                    continue;
                }
                String pursuit = field(line, "pursuit");
                String state = field(line, "state");
                String next = field(line, "next");
                if (!pursuit.isEmpty()) {
                    // The folder was a field of the key, which is how one network serves every pursuit.
                    state = pursuit + '|' + state;
                    next = next.isEmpty() ? next : pursuit + '|' + next;
                }
                out.add(new String[] {
                        state,
                        field(line, "action"),
                        number(line, "reward"),
                        number(line, "steps"),
                        next,
                        field(line, "legal"),
                        number(line, "terminal")});
            }
        }
        return out;
    }

    private static boolean[] mask(String legal, int columns) {
        boolean[] out = new boolean[Math.max(columns, legal.length())];
        for (int i = 0; i < legal.length(); i++) {
            out[i] = legal.charAt(i) == '1';
        }
        if (legal.isEmpty()) {
            java.util.Arrays.fill(out, true);
        }
        return out;
    }

    /** The value of a quoted field. */
    private static String field(String line, String name) {
        String marker = '"' + name + "\":\"";
        int at = line.indexOf(marker);
        if (at < 0) {
            return "";
        }
        int from = at + marker.length();
        StringBuilder out = new StringBuilder();
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> next;
                });
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The value of an unquoted field. */
    private static String number(String line, String name) {
        String marker = '"' + name + "\":";
        int at = line.indexOf(marker);
        if (at < 0) {
            return "0";
        }
        int from = at + marker.length();
        int to = from;
        while (to < line.length() && ",}".indexOf(line.charAt(to)) < 0) {
            to++;
        }
        return line.substring(from, to).trim();
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].startsWith("--")) {
                out.put(args[i].substring(2), args[++i]);
            }
        }
        return out;
    }
}
