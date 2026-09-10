package io.github.ivannavas.autocraftai.mob.ai.objective.mentor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.ClaudePlanner;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.Failure;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.PlannerLog;
import io.github.ivannavas.autocraftai.mob.ai.skill.Skill;
import io.github.ivannavas.autocraftai.mob.ai.skill.Skills;
import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.anthropic.executor.AnthropicModelExecutor;
import io.github.ivannavas.sprout.impl.InMemoryConversationStore;
import io.github.ivannavas.sprout.model.AgentData;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks Claude to teach the run out of a block, off the game thread.
 *
 * <p>Wired by hand for the same reason {@link
 * io.github.ivannavas.autocraftai.mob.ai.objective.planner.ClaudePlanner} is, and kept deliberately frugal:
 * a block is only ever sent once. Each stuck state taught is remembered, so a state the mentor has already
 * answered is never sent again — the lessons it planted are what handle it now. A cooldown keeps a broken
 * key or a bad night from turning every stuck second into a request.
 */
@Slf4j
public final class ClaudeMentor implements Mentor {

    private static final String MODEL = "claude-opus-5";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    /**
     * Room for the answer and for the thinking before it. The model thinks by default, and the thinking
     * is paid for out of the same budget as the text: at 700 the run got answers with no text block at
     * all — the whole budget gone on thinking — which came through as an empty reply, logged as "nothing
     * to add". A lesson with a skill in it is under 600 tokens of text; the rest is headroom.
     */
    private static final int MAX_TOKENS = 6_000;
    /**
     * Long enough for a model that thinks. Thirty seconds was the client's default and it was under
     * what these calls take: the run logged a dozen "Anthropic chat request failed" an hour, every one
     * of them a timeout on an answer that was still being written — billed, thrown away, and asked
     * again, while the body wandered without orders.
     */
    private static final int TIMEOUT_SECONDS = 120;
    private static final String CONVERSATION = "unblock";
    private static final long RETRY_AFTER_MILLIS = 60_000L;
    /**
     * The least time between any two questions, whatever the block. One block reads as several sibling
     * states as the body shuffles — a step closer, a block placed — and the first mentor was asked about
     * two of them four seconds apart. A body still pinned this long after a lesson is worth a second
     * opinion; one pinned for four seconds is not a second block.
     */
    private static final long MIN_INTERVAL_MILLIS = 45_000L;
    /** How long after teaching a state the same state may be taught again, if the body is still stuck. */
    private static final long RETEACH_AFTER_MILLIS = 90_000L;
    /** How many times one state may be taught. Two: the first answer and one that knows it failed. */
    private static final int MAX_TIMES = 2;
    /**
     * After this long since a state was last taught, its count starts over. Two lessons and then silence
     * for good was the rule, and a body pinned in the same corner for a quarter of an hour after its
     * second lesson had a coach that had stopped listening: the lessons it got are quoted back, so the
     * third answer knows both failed.
     */
    private static final long TAUGHT_WINDOW_MILLIS = 600_000L;
    /**
     * How many questions one pursuit gets in all, whatever the states. A body circling an item it cannot
     * reach reads as pinned in a new sibling state every minute, and each is a fresh question; four
     * answers that all "worked" for a few decisions is the pattern of a block the mentor cannot see, and
     * the fifth costs the same and teaches the same.
     */
    private static final int MAX_PER_PURSUIT = 4;
    private static final String KEY_FILE = "anthropic-key.txt";
    private static final String KEY_ENVIRONMENT_VARIABLE = "ANTHROPIC_API_KEY";

    private final MentorAgent agent;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService thread = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "autocraft-mentor");
        worker.setDaemon(true);
        return worker;
    });

    private final AtomicReference<Rescue> answer = new AtomicReference<>();
    private final AtomicBoolean asking = new AtomicBoolean();
    /** What was wrong with the last skill written, told back on the next asking so it is not repeated. */
    private volatile String lastSkillProblem = "";
    private final AtomicLong silentUntil = new AtomicLong();
    private final AtomicLong lastAsked = new AtomicLong();

    /** One state taught: when, how many times, and what was said, for the reminder if it did not take. */
    private record Taught(long at, int times, String summary) {
    }

    /** Stuck states taught so far, by folder and state, so a state is taught at most twice and not soon. */
    private final Map<String, Taught> taught = new ConcurrentHashMap<>();

    /** One lesson given on a pursuit and, once the run has judged it, how it went. */
    private static final class Given {
        final long at;
        final String state;
        final String summary;
        volatile String verdict = "not judged yet";

        Given(long at, String state, String summary) {
            this.at = at;
            this.state = state;
            this.summary = summary;
        }
    }

    /** How many lessons per pursuit are quoted back. */
    private static final int HISTORY = 5;
    /**
     * The lessons given lately on each pursuit, oldest first. A body shuffling in a corner reads as a new
     * sibling state every minute, each a fresh question, and the answers to four of them in two minutes
     * taught DIG_DOWN 6, then -2, then -6: each one knew only what was said about its own state. The
     * whole recent record, with how each lesson went, is what a coach would want before a fifth answer.
     */
    private final Map<String, Deque<Given>> given = new ConcurrentHashMap<>();
    /** Questions put per pursuit, for the overall cap. */
    private final Map<String, Integer> perPursuit = new ConcurrentHashMap<>();
    /** When each pursuit was last asked about, so a spent cap comes back after a while. */
    private final Map<String, Long> perPursuitAt = new ConcurrentHashMap<>();
    /**
     * After this long without a question about a pursuit its count starts over. The cap was for a
     * mentor asked four times in four minutes about one block; it also silenced it for the thirty-seven
     * minutes a body then spent in a hole on the same pursuit, with nobody else to ask.
     */
    private static final long PURSUIT_WINDOW_MILLIS = 600_000L;

    private ClaudeMentor(MentorAgent agent) {
        this.agent = agent;
    }

    /** A mentor if there is a key, and one with nothing to teach if there is not. */
    public static Mentor create(Path directory) {
        String key = apiKey(directory);
        if (key == null) {
            log.info("No Anthropic key; blocks will be left to the local policy to work out");
            return Mentor.none();
        }
        Agent brief = MentorAgent.class.getAnnotation(Agent.class);
        MentorAgent agent = new MentorAgent();
        agent.configure(AgentData.fromAnnotation(brief,
                new AnthropicModelExecutor(key, MODEL, MAX_TOKENS, TIMEOUT_SECONDS, API_URL),
                new InMemoryConversationStore(), null, Map.of()));
        log.info("Blocks will be coached by {}", MODEL);
        return new ClaudeMentor(agent);
    }

    private static String apiKey(Path directory) {
        String fromEnvironment = System.getenv(KEY_ENVIRONMENT_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.strip();
        }
        Path file = directory.resolve(KEY_FILE);
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public boolean consider(Supplier<MentorAsk> ask) {
        long now = System.currentTimeMillis();
        if (now < silentUntil.get() || now - lastAsked.get() < MIN_INTERVAL_MILLIS
                || !asking.compareAndSet(false, true)) {
            return false;
        }
        MentorAsk asked = ask.get();
        // A state taught recently, or taught twice, is left to the lessons already planted.
        Taught earlier = taught.get(asked.key());
        Taught before = earlier != null && now - earlier.at() > TAUGHT_WINDOW_MILLIS
                ? new Taught(earlier.at(), 0, earlier.summary()) : earlier;
        if (before != null && (before.times() >= MAX_TIMES || now - before.at() < RETEACH_AFTER_MILLIS)) {
            asking.set(false);
            return false;
        }
        if (now - perPursuitAt.getOrDefault(asked.pursuit(), 0L) > PURSUIT_WINDOW_MILLIS) {
            perPursuit.remove(asked.pursuit());
        }
        if (perPursuit.getOrDefault(asked.pursuit(), 0) >= MAX_PER_PURSUIT) {
            asking.set(false);
            return false;
        }
        perPursuit.merge(asked.pursuit(), 1, Integer::sum);
        perPursuitAt.put(asked.pursuit(), now);
        lastAsked.set(now);
        String prompt = asked.describe() + history(asked.pursuit(), now) + (before == null ? ""
                : "\nYou already taught this " + (before.times() == 0 ? "a while ago" : "once") + " ("
                        + before.summary() + ") and it is still " + (asked.stalled() ? "getting nowhere" : "stuck")
                        + ". Teach a different way out" + (asked.stalled() ? ", or replan." : "."));
        if (!lastSkillProblem.isEmpty() && asked.skillProblem().isEmpty()) {
            prompt += "\nThe last skill you wrote was refused: " + lastSkillProblem
                    + ". Fix it if you write one again.";
        }
        PlannerLog.get().mentorAsked((asked.stalled() ? "stall: " : "unblock: ") + asked.summary(), prompt);
        final String question = prompt;
        thread.execute(() -> {
            try {
                teach(asked, question, before, now);
            } finally {
                asking.set(false);
            }
        });
        return true;
    }

    private void teach(MentorAsk asked, String prompt, Taught before, long askedAt) {
        try {
            String reply = call(prompt);
            if (reply == null || reply.isBlank()) {
                // No text block at all: the model spent its budget before writing, or refused. Told
                // apart from a reply with nothing in it, because the fix is different.
                log.warn("The mentor answered nothing for {} (out of tokens before the text?)",
                        asked.summary());
                PlannerLog.get().mentorFailed("empty answer", null);
                rest();
                return;
            }
            // A skill the mentor wrote, checked before anything is done with it. Its name is a move
            // from now on, so lessons in the same reply may name it.
            Skill skill = null;
            String skillProblem = "";
            Optional<JsonNode> written = object(reply).map(node -> node.path("skill"))
                    .filter(JsonNode::isObject);
            if (written.isPresent()) {
                try {
                    skill = Skill.parse(written.get(), Skills.taken());
                } catch (IllegalArgumentException e) {
                    skillProblem = e.getMessage();
                    log.info("The mentor's skill was refused: {}", skillProblem);
                }
            }
            lastSkillProblem = skillProblem;
            List<String> named = new ArrayList<>(asked.actions());
            List<String> namedPassage = new ArrayList<>(asked.passageMoves());
            List<String> namedTactics = new ArrayList<>(asked.tacticMoves());
            List<String> namedCrafts = new ArrayList<>(asked.craftMoves());
            if (skill != null) {
                switch (skill.layer()) {
                    case GOAL -> named.add(skill.name());
                    case PASSAGE -> namedPassage.add(skill.name());
                    case TACTIC -> namedTactics.add(skill.name());
                    case CRAFT -> namedCrafts.add(skill.name());
                }
            }
            Rescue rescue = new Rescue(asked.reason(), asked.pursuit(), asked.stuckState(),
                    lessons(reply, "lessons", named),
                    asked.terrain(), lessons(reply, "passage", namedPassage),
                    asked.craftKey(), lessons(reply, "craft", namedCrafts),
                    asked.tacticKey(), lessons(reply, "tactic", namedTactics),
                    asked.waterKey(), lessons(reply, "water", new ArrayList<>(asked.waterMoves())),
                    skill, skillProblem,
                    // Only a stall may be given up on. A pinned body is a block, and a block is
                    // answered with a way out, not with a different errand to be blocked on.
                    asked.stalled() ? replanIn(reply) : "",
                    asked.situation().objective(), askedAt, forgetsIn(reply));
            if (rescue.isEmpty()) {
                log.info("The mentor had nothing to add for {}: {}", asked.summary(), shorten(reply));
                PlannerLog.get().mentorFailed("nothing taught", reply);
                rest();
                return;
            }
            taught.put(asked.key(), new Taught(System.currentTimeMillis(),
                    before == null ? 1 : before.times() + 1, rescue.summary()));
            Deque<Given> record = given.computeIfAbsent(asked.pursuit(), k -> new ArrayDeque<>());
            synchronized (record) {
                record.addLast(new Given(System.currentTimeMillis(), asked.stuckState(), rescue.summary()));
                while (record.size() > HISTORY) {
                    record.removeFirst();
                }
            }
            answer.set(rescue);
            log.info("Mentor taught {} for {}", rescue.summary(), asked.summary());
            // Kept in the same record the planner writes to, but tagged as the mentor's, so the overlay
            // shows the coaching in a panel of its own.
            PlannerLog.get().mentorTaught((rescue.asksToReplan() ? "gave up: " : "taught ")
                    + rescue.summary() + ": " + reasonIn(reply), reply);
        } catch (RuntimeException e) {
            String why = Failure.describe(e);
            log.warn("Could not reach the mentor ({}); leaving the block to the policy", why);
            PlannerLog.get().mentorFailed(shorten(why), null);
            rest();
        }
    }

    /** Tries again this many times, and waits this long the first time, doubling. */
    private static final int TRIES = 3;
    private static final long FIRST_WAIT_MILLIS = 3_000L;

    /**
     * One call, with a couple of quick retries for an overloaded API or a dropped connection. The body
     * is standing still waiting for this answer; a minute's rest over three seconds of trouble is the
     * whole hold wasted.
     */
    private String call(String prompt) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= TRIES; attempt++) {
            try {
                io.github.ivannavas.sprout.model.AgentResult result = agent.execute(CONVERSATION, prompt);
                ClaudePlanner.note(result.totalUsage(), "mentor", MAX_TOKENS);
                return result.response();
            } catch (RuntimeException e) {
                last = e;
                if (attempt == TRIES || !Failure.worthRetrying(e)) {
                    throw e;
                }
                long wait = FIRST_WAIT_MILLIS << (attempt - 1);
                log.info("The mentor's call failed ({}); trying again in {} s", Failure.describe(e), wait / 1000);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    /** One {@code {"action": X, "value": N}} as text, for a reply the JSON parser could not take whole. */
    private static final Pattern LESSON = Pattern.compile(
            "\"action\"\\s*:\\s*\"([A-Za-z_]+)\"\\s*,\\s*\"value\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");

    /**
     * The lessons under one field of the reply, keeping only moves the table actually offers.
     *
     * <p>Read from the parsed object when it parses, and off the text when it does not: a reply cut short
     * by the token limit, or with a word after the closing brace, still carries its lessons in order, and
     * the first mentor threw all of them away over a missing bracket.
     */
    private List<Lesson> lessons(String reply, String field, List<String> actions) {
        List<Lesson> found = new ArrayList<>();
        Optional<JsonNode> parsed = object(reply).map(node -> node.path(field)).filter(JsonNode::isArray);
        if (parsed.isPresent()) {
            for (JsonNode entry : parsed.get()) {
                String action = entry.path("action").asText("").strip().toUpperCase(Locale.ROOT);
                if (actions.contains(action)) {
                    found.add(new Lesson(action, entry.path("value").asDouble(0.0)));
                }
            }
            return found;
        }
        int at = reply == null ? -1 : reply.indexOf('"' + field + '"');
        if (at < 0) {
            return found;
        }
        int end = reply.indexOf(']', at);
        Matcher lesson = LESSON.matcher(end < 0 ? reply.substring(at) : reply.substring(at, end));
        while (lesson.find()) {
            String action = lesson.group(1).toUpperCase(Locale.ROOT);
            if (actions.contains(action)) {
                found.add(new Lesson(action, Double.parseDouble(lesson.group(2))));
            }
        }
        return found;
    }

    private String reasonIn(String reply) {
        return object(reply).map(node -> node.path("reason").asText("")).orElse("");
    }

    /** Every name a new skill may not take: the moves of every table, and the skills already written. */
    /**
     * The mentor giving the objective up, as the sentence it gave, or empty when it did not.
     *
     * <p>A model asked for a string sometimes answers with {@code true}, and that is still the objective
     * being given up; it is carried as a sentence the planner can read, so a bare yes becomes one.
     */
    /**
     * The skills the coach says to forget, each with its reason: objects with a name and a why, or bare
     * names, kept when such a skill exists.
     */
    private Map<String, String> forgetsIn(String reply) {
        Map<String, String> forgets = new LinkedHashMap<>();
        Optional<JsonNode> listed = object(reply).map(node -> node.path("forget")).filter(JsonNode::isArray);
        if (listed.isEmpty()) {
            return forgets;
        }
        Set<String> known = Skills.get().names();
        for (JsonNode entry : listed.get()) {
            String name = (entry.isTextual() ? entry.asText() : entry.path("name").asText(""))
                    .strip().toUpperCase(Locale.ROOT);
            String why = entry.isTextual() ? "" : entry.path("why").asText("").strip();
            if (known.contains(name)) {
                forgets.putIfAbsent(name, why);
            }
        }
        return forgets;
    }

    private String replanIn(String reply) {
        return object(reply).map(node -> node.path("replan")).map(node -> {
            if (node.isBoolean()) {
                return node.asBoolean() ? "the coach could not find a way to it from here" : "";
            }
            String said = node.asText("").strip();
            return "false".equalsIgnoreCase(said) || "null".equalsIgnoreCase(said) ? "" : said;
        }).orElse("");
    }

    private Optional<JsonNode> object(String reply) {
        if (reply == null) {
            return Optional.empty();
        }
        int open = reply.indexOf('{');
        int close = reply.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readTree(reply.substring(open, close + 1)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void rest() {
        silentUntil.set(System.currentTimeMillis() + RETRY_AFTER_MILLIS);
    }

    private static String shorten(String text) {
        String flat = text == null ? "" : text.replace('\n', ' ').strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "...";
    }

    @Override
    public Optional<Rescue> take() {
        return Optional.ofNullable(answer.getAndSet(null));
    }

    @Override
    public boolean pending() {
        return asking.get();
    }

    @Override
    public void judged(Rescue rescue, String verdict) {
        Deque<Given> record = given.get(rescue.pursuit());
        if (record == null) {
            return;
        }
        synchronized (record) {
            for (Given lesson : record) {
                if (lesson.summary.equals(rescue.summary()) && lesson.verdict.equals("not judged yet")) {
                    lesson.verdict = verdict;
                }
            }
        }
    }

    /** The recent lessons on a pursuit and how they went, as a paragraph for the prompt, or nothing. */
    private String history(String pursuit, long now) {
        Deque<Given> record = given.get(pursuit);
        if (record == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        synchronized (record) {
            for (Given lesson : record) {
                if (now - lesson.at > TAUGHT_WINDOW_MILLIS) {
                    continue;
                }
                text.append("\n  - ").append((now - lesson.at) / 1000).append(" s ago, at ").append(lesson.state)
                        .append(": ").append(lesson.summary).append(" -> ").append(lesson.verdict);
            }
        }
        if (text.isEmpty()) {
            return "";
        }
        return "\nLessons already given on this pursuit lately, oldest first, and how each went:" + text
                + "\nThe body is where it is after all of them. Read them before answering: an answer that"
                + " only reverses the last one is a guess, and a move they all left alone is worth more"
                + " than a fourth number on one they tried.";
    }

    @Override
    public void reset() {
        // A fresh world is a fresh policy: re-teaching a run's first block is cheap and lands the lessons
        // on tables that actually have the row.
        taught.clear();
        given.clear();
        perPursuit.clear();
        perPursuitAt.clear();
        answer.set(null);
        silentUntil.set(0L);
        lastAsked.set(0L);
    }

    @Override
    public void close() {
        thread.shutdownNow();
        try {
            thread.awaitTermination(1L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
