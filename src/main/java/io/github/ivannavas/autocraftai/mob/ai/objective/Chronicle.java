package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * What has happened to the local policy so far, kept for the two agents that advise it.
 *
 * <p>The planner and the coach each saw one moment at a time: an inventory, a stuck state, a list of
 * skills. Neither saw the run — which objectives dragged and which flew, what keeps killing the body,
 * where its minutes go, which moves it reaches for and how often those are cut short, how the last ten
 * lessons went. This is that record, in numbers and in a short list of events, written into every
 * question both agents are asked so that what they say fits this player as it actually behaves.
 *
 * <p>Fed from the game thread, read from the agents' threads; every method is synchronized. Written to
 * disk on each event so a restart keeps the record, and thrown away with the rest of the learning.
 */
@Slf4j
public final class Chronicle {

    private static final Chronicle INSTANCE = new Chronicle();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FILE = "chronicle.json";
    /** Events kept, and how many of them are quoted. */
    private static final int KEEP_EVENTS = 60;
    private static final int QUOTE_EVENTS = 14;
    /** Seconds of the recent record that the time shares and the move shares are drawn from. */
    private static final int RECENT_SECONDS = 600;
    private static final int RECENT_MOVES = 200;

    /** One thing that happened, with the wall clock it happened at. */
    private record Event(long at, String text) {
    }

    /** One second of the body's life, in the facts that say where its time goes. */
    public record Sample(boolean wet, boolean underground, boolean nightOutside, boolean sheltering,
                         boolean holding, boolean crafting, boolean pinned) {
    }

    /** One move the goal table made and what came of it. */
    private record Move(String action, int seconds, double reward, boolean cutShort) {
    }

    private final Deque<Event> events = new ArrayDeque<>();
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Deque<Move> moves = new ArrayDeque<>();
    private final Map<String, Integer> deathsByCause = new LinkedHashMap<>();
    private long startedAt = System.currentTimeMillis();
    private long secondsLived;
    private int objectivesSet;
    private int objectivesReached;
    private int objectivesAbandoned;
    private long reachSecondsTotal;
    private final List<Integer> reachSeconds = new ArrayList<>();
    private String objectiveInHand = "";
    private long objectiveSince;
    private int deaths;
    private int lessonsTaught;
    private int lessonsWorked;
    private int lessonsFailed;
    private int lessonsLate;
    private int skillsWritten;
    private int skillsRetired;
    private int skillsForgotten;
    private Path file;

    private Chronicle() {
    }

    public static Chronicle get() {
        return INSTANCE;
    }

    // --- what happens --------------------------------------------------------------------------------

    public synchronized void objectiveStarted(String name, String reason) {
        objectivesSet++;
        objectiveInHand = name == null ? "" : name;
        objectiveSince = System.currentTimeMillis();
        note("objective set: " + objectiveInHand + (reason == null || reason.isBlank() ? "" : " — " + shorten(reason)));
    }

    public synchronized void objectiveReached(String name) {
        objectivesReached++;
        int seconds = secondsOnObjective();
        reachSecondsTotal += seconds;
        reachSeconds.add(seconds);
        note("reached " + name + " after " + minutes(seconds));
        objectiveInHand = "";
    }

    public synchronized void objectiveAbandoned(String name, String why) {
        objectivesAbandoned++;
        note("gave up " + name + " after " + minutes(secondsOnObjective()) + ": " + shorten(why));
        objectiveInHand = "";
    }

    public synchronized void died(String cause) {
        deaths++;
        String how = cause == null || cause.isBlank() ? "unknown cause" : causeOf(cause);
        deathsByCause.merge(how, 1, Integer::sum);
        note("DIED (" + how + ")" + (objectiveInHand.isEmpty() ? "" : " while on " + objectiveInHand
                + ", " + minutes(secondsOnObjective()) + " into it"));
        objectiveInHand = "";
    }

    public synchronized void lessonTaught(String pursuit, String summary) {
        lessonsTaught++;
        note("coach taught on " + pursuit + ": " + shorten(summary));
    }

    public synchronized void lessonJudged(String verdict) {
        if (verdict.startsWith("worked")) {
            lessonsWorked++;
        } else if (verdict.startsWith("arrived")) {
            lessonsLate++;
        } else {
            lessonsFailed++;
        }
        note("coach's lesson " + shorten(verdict));
    }

    public synchronized void skillWritten(String by, String name) {
        skillsWritten++;
        note(by + " wrote skill " + name);
    }

    public synchronized void skillRetired(String name, String why) {
        skillsRetired++;
        note("skill " + name + " retired: " + shorten(why));
    }

    public synchronized void skillForgotten(String name, String why) {
        skillsForgotten++;
        note("skill " + name + " forgotten: " + shorten(why));
    }

    /** Once a second while the body is in a world. Not written to disk: the record of it is the shares. */
    public synchronized void sample(Sample sample) {
        secondsLived++;
        samples.addLast(sample);
        while (samples.size() > RECENT_SECONDS) {
            samples.removeFirst();
        }
        // The seconds lived are what the death rate is over; written out once a minute so a restart
        // between events does not lose them.
        if (secondsLived % 60 == 0) {
            save();
        }
    }

    /** Each move the goal table made, as it ended. Not written to disk either. */
    public synchronized void move(String action, int seconds, double reward, boolean cutShort) {
        if (action == null) {
            return;
        }
        moves.addLast(new Move(action, Math.max(1, seconds), reward, cutShort));
        while (moves.size() > RECENT_MOVES) {
            moves.removeFirst();
        }
    }

    // --- what it reads as ----------------------------------------------------------------------------

    /** The whole record as a paragraph for a prompt, or empty when nothing has happened yet. */
    public synchronized String describe() {
        if (events.isEmpty() && secondsLived == 0) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        long minutes = Math.max(1, (System.currentTimeMillis() - startedAt) / 60_000L);
        text.append("THE RUN SO FAR (").append(minutes).append(" min of play on record):\n");
        text.append("  Objectives: ").append(objectivesSet).append(" set, ").append(objectivesReached)
                .append(" reached").append(reachSeconds.isEmpty() ? "" : " (typically " + minutes(median(reachSeconds)) + " each)")
                .append(", ").append(objectivesAbandoned).append(" given up.");
        if (!objectiveInHand.isEmpty()) {
            text.append(" In hand: ").append(objectiveInHand).append(" for ").append(minutes(secondsOnObjective())).append('.');
        }
        text.append('\n');
        text.append("  Deaths: ").append(deaths);
        if (deaths > 0) {
            text.append(" (one every ").append(minutes((int) Math.max(60, secondsLived / Math.max(1, deaths))))
                    .append("; ");
            List<String> causes = new ArrayList<>();
            deathsByCause.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue()).limit(3)
                    .forEach(entry -> causes.add(entry.getValue() + " " + entry.getKey()));
            text.append(String.join(", ", causes)).append(')');
        }
        text.append(".\n");
        if (!samples.isEmpty()) {
            int n = samples.size();
            int wet = 0, under = 0, night = 0, shelter = 0, hold = 0, craft = 0, pinned = 0;
            for (Sample s : samples) {
                wet += s.wet() ? 1 : 0;
                under += s.underground() ? 1 : 0;
                night += s.nightOutside() ? 1 : 0;
                shelter += s.sheltering() ? 1 : 0;
                hold += s.holding() ? 1 : 0;
                craft += s.crafting() ? 1 : 0;
                pinned += s.pinned() ? 1 : 0;
            }
            text.append("  Where the last ").append(minutes(n)).append(" went: ")
                    .append(share(under, n)).append(" underground, ").append(share(wet, n)).append(" in water, ")
                    .append(share(night, n)).append(" at night in the open, ").append(share(shelter, n))
                    .append(" under a tactic skill, ").append(share(craft, n)).append(" crafting, ")
                    .append(share(hold, n)).append(" waiting for the coach, ").append(share(pinned, n))
                    .append(" pinned in one spot.\n");
        }
        if (!moves.isEmpty()) {
            Map<String, int[]> byMove = new LinkedHashMap<>();
            Map<String, Double> earned = new LinkedHashMap<>();
            int total = 0;
            for (Move move : moves) {
                int[] counts = byMove.computeIfAbsent(move.action(), key -> new int[3]);
                counts[0]++;
                counts[1] += move.cutShort() ? 1 : 0;
                counts[2] += move.seconds();
                earned.merge(move.action(), move.reward(), Double::sum);
                total++;
            }
            final int all = total;
            List<String> parts = new ArrayList<>();
            byMove.entrySet().stream()
                    .sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(6)
                    .forEach(entry -> {
                        int[] counts = entry.getValue();
                        parts.add(String.format(Locale.ROOT, "%s %d%% (cut short %d%%, %+.2f/s)",
                                entry.getKey(), 100 * counts[0] / all, 100 * counts[1] / counts[0],
                                earned.get(entry.getKey()) / Math.max(1, counts[2])));
                    });
            text.append("  Its moves lately (share of the last ").append(total).append(" decisions): ")
                    .append(String.join(", ", parts)).append(".\n");
        }
        text.append("  Coach: ").append(lessonsTaught).append(" lessons, ").append(lessonsWorked).append(" worked, ")
                .append(lessonsFailed).append(" did not, ").append(lessonsLate).append(" arrived late; asked ")
                .append(lessonsIn(0, 600)).append(" times in the last 10 min, ").append(lessonsIn(600, 1200))
                .append(" in the 10 before, ").append(lessonsIn(1200, 1800)).append(" in the 10 before that.")
                .append(" Skills: ").append(skillsWritten).append(" written, ").append(skillsRetired)
                .append(" retired on their record, ").append(skillsForgotten).append(" forgotten on purpose.\n");
        if (!events.isEmpty()) {
            text.append("  Recent events, oldest first:\n");
            long now = System.currentTimeMillis();
            List<Event> quoted = new ArrayList<>(events);
            for (Event event : quoted.subList(Math.max(0, quoted.size() - QUOTE_EVENTS), quoted.size())) {
                text.append("    ").append(minutes((int) ((now - event.at()) / 1000))).append(" ago: ")
                        .append(event.text()).append('\n');
            }
        }
        return text.toString();
    }

    /** How many lessons landed in a window of seconds ago: the coach's own measure of being needed. */
    private int lessonsIn(int fromSecondsAgo, int toSecondsAgo) {
        long now = System.currentTimeMillis();
        int count = 0;
        for (Event event : events) {
            long ago = (now - event.at()) / 1000L;
            if (ago >= fromSecondsAgo && ago < toSecondsAgo && event.text().startsWith("coach taught")) {
                count++;
            }
        }
        return count;
    }

    // --- keeping it ----------------------------------------------------------------------------------

    /** Reads the record left by an earlier session, if there is one. */
    public synchronized void load(Path directory) {
        file = directory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            startedAt = root.path("startedAt").asLong(System.currentTimeMillis());
            secondsLived = root.path("secondsLived").asLong(0);
            objectivesSet = root.path("objectivesSet").asInt(0);
            objectivesReached = root.path("objectivesReached").asInt(0);
            objectivesAbandoned = root.path("objectivesAbandoned").asInt(0);
            reachSecondsTotal = root.path("reachSecondsTotal").asLong(0);
            deaths = root.path("deaths").asInt(0);
            lessonsTaught = root.path("lessonsTaught").asInt(0);
            lessonsWorked = root.path("lessonsWorked").asInt(0);
            lessonsFailed = root.path("lessonsFailed").asInt(0);
            lessonsLate = root.path("lessonsLate").asInt(0);
            skillsWritten = root.path("skillsWritten").asInt(0);
            skillsRetired = root.path("skillsRetired").asInt(0);
            skillsForgotten = root.path("skillsForgotten").asInt(0);
            reachSeconds.clear();
            root.path("reachSeconds").forEach(node -> reachSeconds.add(node.asInt()));
            deathsByCause.clear();
            root.path("deathsByCause").fields().forEachRemaining(entry ->
                    deathsByCause.put(entry.getKey(), entry.getValue().asInt()));
            events.clear();
            for (JsonNode node : root.path("events")) {
                events.addLast(new Event(node.path("at").asLong(0), node.path("text").asText("")));
            }
            log.info("Loaded the run's chronicle: {} events, {} objectives, {} deaths", events.size(),
                    objectivesSet, deaths);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read {}; the chronicle starts over", file, e);
        }
    }

    /** Starts the record over: a fresh world, or the learning thrown away. */
    public synchronized void clear() {
        events.clear();
        samples.clear();
        moves.clear();
        deathsByCause.clear();
        reachSeconds.clear();
        startedAt = System.currentTimeMillis();
        secondsLived = 0;
        objectivesSet = objectivesReached = objectivesAbandoned = 0;
        reachSecondsTotal = 0;
        objectiveInHand = "";
        deaths = lessonsTaught = lessonsWorked = lessonsFailed = lessonsLate = 0;
        skillsWritten = skillsRetired = skillsForgotten = 0;
        save();
    }

    private void note(String text) {
        events.addLast(new Event(System.currentTimeMillis(), text));
        while (events.size() > KEEP_EVENTS) {
            events.removeFirst();
        }
        log.info("Chronicle: {}", text);
        save();
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("startedAt", startedAt);
            root.put("secondsLived", secondsLived);
            root.put("objectivesSet", objectivesSet);
            root.put("objectivesReached", objectivesReached);
            root.put("objectivesAbandoned", objectivesAbandoned);
            root.put("reachSecondsTotal", reachSecondsTotal);
            root.put("deaths", deaths);
            root.put("lessonsTaught", lessonsTaught);
            root.put("lessonsWorked", lessonsWorked);
            root.put("lessonsFailed", lessonsFailed);
            root.put("lessonsLate", lessonsLate);
            root.put("skillsWritten", skillsWritten);
            root.put("skillsRetired", skillsRetired);
            root.put("skillsForgotten", skillsForgotten);
            ArrayNode reach = root.putArray("reachSeconds");
            reachSeconds.forEach(reach::add);
            ObjectNode causes = root.putObject("deathsByCause");
            deathsByCause.forEach(causes::put);
            ArrayNode listed = root.putArray("events");
            for (Event event : events) {
                ObjectNode node = listed.addObject();
                node.put("at", event.at());
                node.put("text", event.text());
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write {}", file, e);
        }
    }

    // --- small helpers -------------------------------------------------------------------------------

    private int secondsOnObjective() {
        return objectiveSince == 0 ? 0 : (int) ((System.currentTimeMillis() - objectiveSince) / 1000L);
    }

    private static String minutes(int seconds) {
        if (seconds < 90) {
            return seconds + " s";
        }
        return (seconds + 30) / 60 + " min";
    }

    private static String share(int part, int whole) {
        return (100 * part / Math.max(1, whole)) + "%";
    }

    private static int median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.strip().replace('\n', ' ');
        return flat.length() <= 110 ? flat : flat.substring(0, 107) + "...";
    }

    /** The cause without the player's name: "was slain by Zombie", "ha muerto entre espinas...". */
    private static String causeOf(String sentence) {
        String flat = sentence.strip();
        int space = flat.indexOf(' ');
        return space > 0 && space < 24 ? flat.substring(space + 1) : flat;
    }
}
