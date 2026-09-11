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

    /** Uses before a skill's record is judged at all. */
    private static final int JUDGED_AFTER = 20;
    /** A skill that has never once finished is judged much sooner: every try is a minute of daylight. */
    private static final int JUDGED_EARLY = 6;
    /** How many of a skill's failure reasons are kept for its writer. */
    private static final int PROBLEMS_KEPT = 3;
    /** Below this share of uses completed, a judged skill is retired. */
    private static final double KEEP_ABOVE = 0.15;
    private static final String FILE = "skills.json";
    private static final String STARTERS = "/assets/autocraft-ai/skills/starters.json";

    private static final Skills INSTANCE = new Skills();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConcurrentLinkedQueue<Skill> offered = new ConcurrentLinkedQueue<>();
    /** Skills the planner said to forget, with its reason, until the game thread takes them. */
    private final ConcurrentLinkedQueue<Forget> forgetsOffered = new ConcurrentLinkedQueue<>();

    /** One skill to forget and why. */
    public record Forget(String name, String why) {
    }

    /** How a skill has done: uses, completions, and whether it has been retired for not finishing. */
    public static final class Record {
        private int uses;
        private int completions;
        private int failures;
        private boolean retired;
        /** Runs that finished with no step having done anything: every break found air, nothing moved. */
        private int empty;
        /** Why the coach forgot it, when it did; empty for a skill retired by its own record. */
        private String forgotten = "";
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

        public int empty() {
            return empty;
        }

        public String forgotten() {
            return forgotten;
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
            starters(false);
            save();
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
                    record.empty = entry.path("empty").asInt(0);
                    record.forgotten = entry.path("forgotten").asText("");
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
        // A starter missing from the file altogether was lost, not forgotten: a forgotten one is kept
        // in the file as retired. Two of them went that way when a wait for a condition was written
        // back as a wait for nought ticks and refused at the next load.
        if (starters(true) > 0) {
            save();
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
                node.put("empty", record.empty);
                node.put("forgotten", record.forgotten);
                ArrayNode problems = node.putArray("problems");
                record.problems.forEach(problems::add);
                listed.add(node);
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // Writing the shelf out is bookkeeping; a bug in it must not take the game down.
            log.warn("Could not write {}", file, e);
        }
    }

    /**
     * Throws every skill away, written and offered alike, and writes the empty file. The skills are
     * learning as much as the tables are: the planner and the mentor wrote them, and their records say
     * how they went. A reset that kept them kept half of what it said it had thrown away.
     */
    public void clear() {
        int had = skills.size();
        skills.clear();
        records.clear();
        offered.clear();
        starters(false);
        save();
        log.info("Cleared the skills, forgetting {}; the starter shelf is back", had);
    }

    /**
     * The starter shelf: the strategies that used to be code — hole up, tower, wall off, daylight,
     * fight, retreat — written in the skill language and shipped as examples of what it can say. They
     * are ordinary skills from the moment they are loaded: tried where they apply, judged by their
     * record, revised or forgotten by the coach. Read from the mod's own resources, never from disk.
     */
    private int starters(boolean onlyMissing) {
        int added = 0;
        try (java.io.InputStream in = Skills.class.getResourceAsStream(STARTERS)) {
            if (in == null) {
                log.warn("No starter shelf at {}", STARTERS);
                return 0;
            }
            JsonNode root = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            for (JsonNode entry : root.path("skills")) {
                String name = entry.path("name").asText("").strip().toUpperCase(java.util.Locale.ROOT);
                Skill stored = skills.get(name);
                // A writer's own under the same name is left alone. A starter nobody has rewritten —
                // its reason still says "starter:", which a coach's or a strategist's revision replaces
                // with their own sentence — follows the shelf when the shelf changes: HOLE_UP lost its
                // wait for day on every box this way, without a reset.
                boolean shipped = stored != null && stored.reason().startsWith("starter:");
                if (onlyMissing && stored != null && !shipped) {
                    continue;
                }
                try {
                    Skill skill = Skill.parse(entry, Set.of());
                    if (onlyMissing && shipped && skill.describe().equals(stored.describe())) {
                        continue;
                    }
                    if (shipped) {
                        log.info("Starter {} updated from the shelf: {}", skill.name(), skill.describe());
                    }
                    skills.put(skill.name(), skill);
                    records.putIfAbsent(skill.name(), new Record());
                    added++;
                } catch (IllegalArgumentException e) {
                    log.warn("Dropping a starter skill: {}", e.getMessage());
                }
            }
            log.info("Starter shelf: {} skill(s){}", added, onlyMissing ? " that were missing put back" : "");
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the starter shelf", e);
        }
        return added;
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

    /** The planner's word that a skill should go, from any thread; taken on the game thread. */
    public void offerForget(String name, String why) {
        forgetsOffered.add(new Forget(name, why));
    }

    /** The forgets offered since last asked. Game thread. */
    public List<Forget> takeOfferedForgets() {
        List<Forget> taken = new ArrayList<>();
        Forget next;
        while ((next = forgetsOffered.poll()) != null) {
            taken.add(next);
        }
        return taken;
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
            // A revision: the writer read why it kept failing and wrote it again. The steps change and
            // the record starts over so the new version is judged on its own. A revision may move to
            // another layer: the old column stays in the old table, dead, and the new table grows one.
            // The listeners are told either way, so the new prior reaches the tables.
            boolean moved = before.layer() != skill.layer();
            skills.put(skill.name(), skill);
            records.put(skill.name(), new Record());
            save();
            log.info("Skill {} revised{}: {}", skill.name(),
                    moved ? " and moved to the " + skill.layer().name().toLowerCase() + " layer" : "",
                    skill.describe());
            listeners.forEach(listener -> listener.accept(skill));
            return Optional.empty();
        }
        // No cap on how many there are. The coach reads the list and forgets the ones not earning
        // their keep; a run learns more from a shelf of tried moves than from six kept on purpose.
        skills.put(skill.name(), skill);
        records.put(skill.name(), new Record());
        save();
        log.info("New skill {}: {}", skill.name(), skill.describe());
        io.github.ivannavas.autocraftai.mob.ai.objective.Chronicle.get().skillWritten("a writer", skill.name());
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
            io.github.ivannavas.autocraftai.mob.ai.objective.Chronicle.get().skillRetired(name,
                    record.completions + " of " + record.uses + " uses finished"
                            + (record.problems.isEmpty() ? "" : "; last: " + record.problems.peekLast()));
        }
        save();
    }

    /**
     * Counted when a skill's goal ran every step and none of them did anything: the breaks found air,
     * nothing was placed, taken or hit. Not a completion — the skill did not do what it says — and a
     * skill that only ever does nothing is retired the way one that never finishes is.
     */
    public void idle(String name, String why) {
        Record record = records.get(name);
        if (record == null) {
            return;
        }
        record.empty++;
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
            log.info("Retiring skill {}: {} of {} uses finished, {} did nothing", name,
                    record.completions, record.uses, record.empty);
            io.github.ivannavas.autocraftai.mob.ai.objective.Chronicle.get().skillRetired(name,
                    record.completions + " of " + record.uses + " uses finished, " + record.empty + " did nothing");
        }
        save();
    }

    /** The coach's call: the skill is retired, and its record says the coach did it and why. */
    public void forget(String name, String why) {
        Record record = records.get(name);
        if (record == null || record.retired) {
            return;
        }
        record.retired = true;
        record.forgotten = why == null ? "" : why.strip();
        save();
        log.info("Forgot skill {}: {}", name, record.forgotten);
        io.github.ivannavas.autocraftai.mob.ai.objective.Chronicle.get().skillForgotten(name, record.forgotten);
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
            node.put("empty", record.empty);
            node.put("forgotten", record.forgotten);
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
                    .append(record.empty > 0 ? ", did nothing " + record.empty : "")
                    .append(!record.retired ? ""
                            : record.forgotten.isEmpty() ? ", RETIRED for not finishing"
                            : ", FORGOTTEN by you: " + record.forgotten);
            if (!record.problems.isEmpty()) {
                out.append("; last failures: ").append(String.join(" / ", record.problems));
            }
            out.append(']');
        }
        return out.toString();
    }
}
