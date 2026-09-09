package io.github.ivannavas.autocraftai.mob.ai.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ivannavas.autocraftai.mob.ai.CraftChoice;
import io.github.ivannavas.autocraftai.mob.ai.GoalAction;
import io.github.ivannavas.autocraftai.mob.ai.Passage;
import io.github.ivannavas.autocraftai.mob.ai.Tactic;
import lombok.extern.slf4j.Slf4j;

/**
 * The skills the run has, on disk and in memory, with a record of how each has done.
 *
 * <p>Kept beside the tables, because they are part of what the run has learned: a skill the mentor
 * wrote on Tuesday is a column the tables have values for on Wednesday, and losing the skill would
 * leave those values pointing at nothing. Loaded before the tables are, so the tables open with the
 * right columns.
 *
 * <h2>Kept honest by its own record</h2>
 * Every use is counted and every completion, and a skill that has been tried enough times and almost
 * never finished is retired: it stays in the file so the mentor can be told it failed, but it is no
 * longer a column anything can choose. Without that a table with a dozen inventions would spend its
 * exploration on inventions, and a mentor that never heard back would write the same one again.
 *
 * <p>A small fixed number of live skills per table, for the same reason: each new column starts at
 * nothing and has to be tried to be valued, and every one of those trials is a second not spent on
 * the objective.
 */
@Slf4j
public final class Skills {

    /** The most skills a table may have live at once. */
    public static final int MOST_LIVE = 6;
    /** Uses before a skill's record is judged at all. */
    private static final int JUDGED_AFTER = 20;
    /** A skill that has never once finished is judged much sooner: every try is a minute of daylight. */
    private static final int JUDGED_EARLY = 6;
    /** How many of a skill's failure reasons are kept for its writer. */
    private static final int PROBLEMS_KEPT = 3;
    /** Below this share of uses completed, a judged skill is retired. */
    private static final double KEEP_ABOVE = 0.15;
    private static final String FILE = "skills.json";

    private static final Skills INSTANCE = new Skills();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConcurrentLinkedQueue<Skill> offered = new ConcurrentLinkedQueue<>();

    /** How a skill has done: uses, completions, and whether it has been retired for not finishing. */
    public static final class Record {
        private int uses;
        private int completions;
        private int failures;
        private boolean retired;
        private String rejection = "";
        /** Why the last few runs failed, for the writer to read before writing the next one. */
        private final java.util.ArrayDeque<String> problems = new java.util.ArrayDeque<>();

        public int uses() {
            return uses;
        }

        public int completions() {
            return completions;
        }

        public boolean retired() {
            return retired;
        }

        public int failures() {
            return failures;
        }

        public List<String> problems() {
            return List.copyOf(problems);
        }
    }

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Map<String, Record> records = new LinkedHashMap<>();
    private final List<Consumer<Skill>> listeners = new ArrayList<>();
    private Path file;

    private Skills() {
    }

    public static Skills get() {
        return INSTANCE;
    }

    /** Reads the file, if there is one. Called once, before any table is opened. */
    public void load(Path directory) {
        file = directory.resolve(FILE);
        skills.clear();
        records.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonNode entry : root.path("skills")) {
                try {
                    Skill skill = Skill.parse(entry, Set.of());
                    Record record = new Record();
                    record.uses = entry.path("uses").asInt(0);
                    record.completions = entry.path("completions").asInt(0);
                    record.failures = entry.path("failures").asInt(0);
                    record.retired = entry.path("retired").asBoolean(false);
                    for (JsonNode problem : entry.path("problems")) {
                        record.problems.addLast(problem.asText(""));
                    }
                    skills.put(skill.name(), skill);
                    records.put(skill.name(), record);
                } catch (IllegalArgumentException e) {
                    log.warn("Dropping a skill from {}: {}", file, e.getMessage());
                }
            }
            log.info("Loaded {} skill(s) from {}", skills.size(), file);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read {}; starting with no skills", file, e);
        }
    }

    public void save() {
        if (file == null) {
            return;
        }
        try {
            ObjectNode root = JSON.createObjectNode();
            ArrayNode listed = root.putArray("skills");
            for (Skill skill : skills.values()) {
                ObjectNode node = skill.toJson();
                Record record = records.get(skill.name());
                node.put("uses", record.uses);
                node.put("completions", record.completions);
                node.put("failures", record.failures);
                node.put("retired", record.retired);
                ArrayNode problems = node.putArray("problems");
                record.problems.forEach(problems::add);
                listed.add(node);
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not write {}", file, e);
        }
    }

    /** Told of every skill added from now on, so the tables can grow a column for it. */
    public void onAdded(Consumer<Skill> listener) {
        listeners.add(listener);
    }

    /** The skills that may be chosen in a layer, in the order they were written. */
    public List<Skill> live(Skill.Layer layer) {
        List<Skill> live = new ArrayList<>();
        for (Skill skill : skills.values()) {
            if (skill.layer() == layer && !records.get(skill.name()).retired) {
                live.add(skill);
            }
        }
        return live;
    }

    /** Every skill there is, live or retired. */
    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    public Optional<Skill> named(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /** The names in use across every table, so a new skill cannot shadow a move. */
    public Set<String> names() {
        return new HashSet<>(skills.keySet());
    }

    /**
     * Every name a new skill may not have: the built-in moves of every table and the skills so far. What
     * a writer's JSON is parsed against, on whichever thread the writer answers on.
     */
    public static Set<String> taken() {
        // The built-in moves only. A skill's own name is not taken, it is revisable: see Skill#parse.
        Set<String> names = new HashSet<>();
        for (GoalAction move : GoalAction.values()) {
            names.add(move.name());
        }
        for (Passage move : Passage.values()) {
            names.add(move.name());
        }
        for (Tactic move : Tactic.values()) {
            names.add(move.name());
        }
        for (CraftChoice move : CraftChoice.values()) {
            names.add(move.name());
        }
        return names;
    }

    /**
     * Hands a skill written off the game thread — the planner's, which answers on its own — to the run,
     * which takes it in with {@link #takeOffered()} on the game thread, where the tables can grow.
     */
    public void offer(Skill skill) {
        offered.add(skill);
    }

    /** The skills offered since last asked, oldest first. Game thread. */
    public List<Skill> takeOffered() {
        List<Skill> taken = new ArrayList<>();
        Skill next;
        while ((next = offered.poll()) != null) {
            taken.add(next);
        }
        return taken;
    }

    /**
     * Takes a new skill in, or says why not.
     *
     * @return empty when the skill was taken; the reason when it was not
     */
    public Optional<String> add(Skill skill) {
        Skill before = skills.get(skill.name());
        if (before != null) {
            // A revision: the writer read why it kept failing and wrote it again. The column stays,
            // the steps change, and the record starts over so the new version is judged on its own.
            if (before.layer() != skill.layer()) {
                return Optional.of("a skill keeps its layer when revised: " + skill.name() + " is "
                        + before.layer().name().toLowerCase());
            }
            skills.put(skill.name(), skill);
            records.put(skill.name(), new Record());
            save();
            log.info("Skill {} revised: {}", skill.name(), skill.describe());
            return Optional.empty();
        }
        if (live(skill.layer()).size() >= MOST_LIVE) {
            return Optional.of("the " + skill.layer().name().toLowerCase() + " table already has "
                    + MOST_LIVE + " live skills; retire one by not using it before writing another");
        }
        skills.put(skill.name(), skill);
        records.put(skill.name(), new Record());
        save();
        log.info("New skill {}: {}", skill.name(), skill.describe());
        listeners.forEach(listener -> listener.accept(skill));
        return Optional.empty();
    }

    /** Counted when a skill's goal is installed. */
    public void used(String name) {
        Record record = records.get(name);
        if (record != null) {
            record.uses++;
        }
    }

    /** Counted when a skill's goal finished what it set out to do. */
    public void completed(String name) {
        Record record = records.get(name);
        if (record != null) {
            record.completions++;
            save();
        }
    }

    /** Counted when a skill's goal gave up, and what retires a skill that keeps giving up. */
    public void failed(String name) {
        failed(name, "");
    }

    /** Counted when a skill's goal gave up, with the reason kept for its writer. */
    public void failed(String name, String why) {
        Record record = records.get(name);
        if (record == null) {
            return;
        }
        record.failures++;
        if (why != null && !why.isBlank()) {
            record.problems.addLast(why.strip());
            while (record.problems.size() > PROBLEMS_KEPT) {
                record.problems.pollFirst();
            }
        }
        boolean neverOnce = record.uses >= JUDGED_EARLY && record.completions == 0;
        boolean rarely = record.uses >= JUDGED_AFTER && record.completions < record.uses * KEEP_ABOVE;
        if (!record.retired && (neverOnce || rarely)) {
            record.retired = true;
            log.info("Retiring skill {}: {} of {} uses finished", name, record.completions, record.uses);
        }
        save();
    }

    public Record record(String name) {
        return records.getOrDefault(name, new Record());
    }

    /** Every skill with its record, as a JSON list for the overlay. */
    public String json() {
        ArrayNode listed = JSON.createArrayNode();
        for (Skill skill : skills.values()) {
            Record record = records.getOrDefault(skill.name(), new Record());
            ObjectNode node = skill.toJson();
            node.put("uses", record.uses);
            node.put("completions", record.completions);
            node.put("failures", record.failures);
            node.put("retired", record.retired);
            node.put("summary", skill.describe());
            ArrayNode problems = node.putArray("problems");
            record.problems.forEach(problems::add);
            listed.add(node);
        }
        return listed.toString();
    }

    /** Everything the mentor needs to know about what already exists, as lines. Empty when nothing does. */
    public String catalogue() {
        StringBuilder out = new StringBuilder();
        for (Skill skill : skills.values()) {
            Record record = records.get(skill.name());
            out.append(out.isEmpty() ? "" : "\n").append("  ").append(skill.describe())
                    .append(" [used ").append(record.uses).append(", finished ").append(record.completions)
                    .append(record.retired ? ", RETIRED for not finishing" : "");
            if (!record.problems.isEmpty()) {
                out.append("; last failures: ").append(String.join(" / ", record.problems));
            }
            out.append(']');
        }
        return out.toString();
    }
}
