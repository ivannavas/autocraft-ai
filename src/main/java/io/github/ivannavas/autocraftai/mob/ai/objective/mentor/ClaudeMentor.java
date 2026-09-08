package io.github.ivannavas.autocraftai.mob.ai.objective.mentor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.PlannerLog;
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
    private static final int MAX_TOKENS = 512;
    private static final int TIMEOUT_SECONDS = 30;
    private static final String CONVERSATION = "unblock";
    private static final long RETRY_AFTER_MILLIS = 60_000L;
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
    private final AtomicLong silentUntil = new AtomicLong();
    /** Stuck states already taught, so none is ever sent twice: the planted lessons handle a repeat. */
    private final Set<String> taught = ConcurrentHashMap.newKeySet();

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
    public void consider(Supplier<MentorAsk> ask) {
        if (System.currentTimeMillis() < silentUntil.get() || !asking.compareAndSet(false, true)) {
            return;
        }
        MentorAsk asked = ask.get();
        // A block already taught is never sent again: the lessons planted last time are what answer it now.
        if (taught.contains(asked.stuckState())) {
            asking.set(false);
            return;
        }
        String prompt = asked.describe();
        PlannerLog.get().mentorAsked("unblock: " + asked.summary(), prompt);
        thread.execute(() -> {
            try {
                teach(asked, prompt);
            } finally {
                asking.set(false);
            }
        });
    }

    private void teach(MentorAsk asked, String prompt) {
        try {
            String reply = agent.execute(CONVERSATION, prompt).response();
            List<Lesson> lessons = lessons(reply, asked.actions());
            if (lessons.isEmpty()) {
                log.info("The mentor had nothing to add for {}", asked.summary());
                PlannerLog.get().mentorFailed("nothing taught", shorten(reply));
                rest();
                return;
            }
            taught.add(asked.stuckState());
            answer.set(new Rescue(asked.pursuit(), asked.stuckState(), lessons));
            log.info("Mentor taught {} lessons for {}: {}", lessons.size(), asked.summary(), lessons);
            // Kept in the same record the planner writes to, but tagged as the mentor's, so the overlay
            // shows the coaching in a panel of its own.
            PlannerLog.get().mentorTaught("taught " + lessons.size() + ": " + reasonIn(reply), reply);
        } catch (RuntimeException e) {
            log.warn("Could not reach the mentor ({}); leaving the block to the policy", e.getMessage());
            PlannerLog.get().mentorFailed(shorten(e.getMessage()), null);
            rest();
        }
    }

    /** The lessons in the reply, keeping only moves the stuck state actually offers. */
    private List<Lesson> lessons(String reply, List<String> actions) {
        List<Lesson> found = new ArrayList<>();
        object(reply).map(node -> node.path("lessons")).filter(JsonNode::isArray).ifPresent(entries -> {
            for (JsonNode entry : entries) {
                String action = entry.path("action").asText("").strip().toUpperCase(Locale.ROOT);
                if (actions.contains(action)) {
                    found.add(new Lesson(action, entry.path("value").asDouble(0.0)));
                }
            }
        });
        return found;
    }

    private String reasonIn(String reply) {
        return object(reply).map(node -> node.path("reason").asText("")).orElse("");
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
    public void reset() {
        // A fresh world is a fresh policy: re-teaching a run's first block is cheap and lands the lessons
        // on tables that actually have the row.
        taught.clear();
        answer.set(null);
        silentUntil.set(0L);
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
