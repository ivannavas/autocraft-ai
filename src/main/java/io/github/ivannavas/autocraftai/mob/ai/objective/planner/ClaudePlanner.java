package io.github.ivannavas.autocraftai.mob.ai.objective.planner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.autocraftai.mob.ai.objective.Ascend;
import io.github.ivannavas.autocraftai.mob.ai.objective.Build;
import io.github.ivannavas.autocraftai.mob.ai.objective.Descend;
import io.github.ivannavas.autocraftai.mob.ai.objective.Bounds;
import io.github.ivannavas.autocraftai.mob.ai.objective.Gather;
import io.github.ivannavas.autocraftai.mob.ai.objective.Phase;
import io.github.ivannavas.autocraftai.mob.ai.objective.Plan;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.objective.Source;
import io.github.ivannavas.autocraftai.mob.ai.objective.Structure;
import io.github.ivannavas.autocraftai.mob.ai.objective.Terrain;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import io.github.ivannavas.autocraftai.mob.ai.objective.Travel;
import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.anthropic.executor.AnthropicModelExecutor;
import io.github.ivannavas.sprout.impl.InMemoryConversationStore;
import io.github.ivannavas.sprout.model.AgentData;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks Claude what the run should go after next, off the game thread.
 *
 * <h2>Wired by hand, on purpose</h2>
 * Sprout normally bootstraps itself by scanning the classpath from an application class. Fabric loads mods
 * through its own classloader, and a scan started from inside a mod finds neither the mod's classes nor the
 * framework's reliably, so the three pieces are assembled here instead: the Anthropic executor, an
 * in-memory store for the conversation, and the agent configured from its own {@code @Agent} annotation.
 * The brief still lives on {@link ObjectiveAgent} and is still read off that annotation — nothing about the
 * agent is restated here, only the plumbing the container would otherwise have done.
 *
 * <h2>One conversation for the whole run</h2>
 * Every request goes into the same conversation, so the planner can see what it asked for last time and
 * why. That is what stops it going round in circles — a planner with no memory asks for wood, is told the
 * wood arrived, and asks for wood again. Sprout caches the prefix, so the growing transcript costs almost
 * nothing to re-send.
 *
 * <h2>Two questions</h2>
 * The same call answers both of them, and which one it is comes from the {@link Situation}: with no
 * objective in it the planner is being asked what to do next, and with one it is being asked whether that
 * objective is still right now it has dragged on. The second may be answered with {@code KEEP}, which is
 * not an objective and not a failure — it is the planner looking and deciding it was already right.
 *
 * <h2>Failure is a fallback, not an error</h2>
 * No key, no network, a reply that will not parse: all of them come out the same way, as no answer, and the
 * run carries on down the fixed ladder. A cooldown keeps a broken key from being retried once a second for
 * the rest of the session.
 */
@Slf4j
public final class ClaudePlanner implements ObjectivePlanner {

    /** The model this asks. Deliberately the strongest one: it is asked once an objective, not once a tick. */
    private static final String MODEL = "claude-opus-5";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final int MAX_TOKENS = 1024;
    private static final int TIMEOUT_SECONDS = 30;
    /** One transcript for the session, so the planner remembers what it has already asked for. */
    private static final String CONVERSATION = "run";
    /** How long to leave a failing planner alone before asking it again. */
    private static final long RETRY_AFTER_MILLIS = 60_000L;
    /** What the planner says when the objective it was asked about is still the right one. */
    private static final String KEEP = "KEEP";
    /** Read when the environment has no key. It is a secret: never logged, never echoed. */
    private static final String KEY_FILE = "anthropic-key.txt";
    private static final String KEY_ENVIRONMENT_VARIABLE = "ANTHROPIC_API_KEY";

    private final ObjectiveAgent agent;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService thread = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "autocraft-objective-planner");
        // A daemon: closing the game should not wait on a request that is still in the air.
        worker.setDaemon(true);
        return worker;
    });

    private final AtomicReference<Plan> answer = new AtomicReference<>();
    private final AtomicBoolean asking = new AtomicBoolean();
    private final AtomicLong silentUntil = new AtomicLong();

    private ClaudePlanner(ObjectiveAgent agent) {
        this.agent = agent;
    }

    /**
     * A planner if there is a key to ask with, and one with no opinions if there is not.
     *
     * <p>The key comes from {@code ANTHROPIC_API_KEY} or from {@code anthropic-key.txt} beside the saved
     * tables. Which of the two it came from is worth logging; the key itself never is.
     */
    public static ObjectivePlanner create(Path directory) {
        String key = apiKey(directory);
        if (key == null) {
            log.info("No Anthropic key ({} or {}); objectives will follow the fixed ladder",
                    KEY_ENVIRONMENT_VARIABLE, directory.resolve(KEY_FILE));
            return ObjectivePlanner.none();
        }
        Agent brief = ObjectiveAgent.class.getAnnotation(Agent.class);
        ObjectiveAgent agent = new ObjectiveAgent();
        agent.configure(AgentData.fromAnnotation(
                brief,
                new AnthropicModelExecutor(key, MODEL, MAX_TOKENS, TIMEOUT_SECONDS, API_URL),
                new InMemoryConversationStore(),
                null,
                Map.of()));
        log.info("Objectives will be planned by {}", MODEL);
        return new ClaudePlanner(agent);
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
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
            return null;
        }
    }

    @Override
    public void consider(Supplier<Situation> situation) {
        if (System.currentTimeMillis() < silentUntil.get() || !asking.compareAndSet(false, true)) {
            return;
        }
        // The description is built on the caller's thread, and that is where it has to be built: it reads
        // the world, and the world is only safe to read from the game thread.
        Situation asked = situation.get();
        String prompt = asked.describe();
        PlannerLog.get().asked(asked.summary(), prompt);
        thread.execute(() -> {
            try {
                ask(prompt);
            } finally {
                asking.set(false);
            }
        });
    }

    private void ask(String prompt) {
        try {
            String reply = agent.execute(CONVERSATION, prompt).response();
            Optional<Phase> errand = parse(reply);
            if (errand.isPresent()) {
                // Built before it is logged: an empty list is filled in from the objective, and the line
                // in the log should say what the run is actually going to work to.
                Plan plan = new Plan(errand.get(), bounds(reply), needs(reply), reserved(reply));
                log.info("Next objective: {} ({}) needing {}{} - {}",
                        plan.objective(), plan.bounds(), plan.needs(),
                        plan.reserved().isEmpty() ? "" : ", holding back " + plan.reserved().kept(),
                        plan.objective().reason());
                PlannerLog.get().answered(plan.objective().name(),
                        plan.bounds().bind()
                                ? plan.objective().reason() + " · " + plan.bounds()
                                : plan.objective().reason(),
                        reply);
                answer.set(plan);
                return;
            }
            // Nothing to take, but for two very different reasons, and only one of them is a problem.
            if (keepsCurrent(reply)) {
                log.info("The planner looked and left the objective alone");
                PlannerLog.get().kept(reasonIn(reply), reply);
                return;
            }
            // Naming what was wrong with it, because "not an objective" over a hundred characters of
            // JSON is a puzzle and the shape and the target are almost always where it went astray.
            String said = object(reply)
                    .map(node -> node.path("objective").asText("?") + " / " + node.path("target").asText("?"))
                    .orElse("no JSON");
            log.warn("The planner answered something that is not an objective ({}): {}",
                    said, shorten(reply));
            PlannerLog.get().failed("not an objective: " + said, shorten(reply));
            rest();
        } catch (RuntimeException e) {
            // The message can carry the API's own error body, which is worth seeing; the key is not in it.
            log.warn("Could not reach the planner ({}); falling back to the ladder", e.getMessage());
            PlannerLog.get().failed(shorten(e.getMessage()), null);
            rest();
        }
    }

    /** Whether the reply is the planner saying the objective it was asked about is still the right one. */
    private boolean keepsCurrent(String reply) {
        return object(reply)
                .map(node -> KEEP.equalsIgnoreCase(node.path("objective").asText("").strip()))
                .orElse(false);
    }

    /**
     * The heights the reply says to stay between, or no opinion when it did not say.
     *
     * <p>Optional on purpose. A plan that does not care where the body is should not have a band invented
     * for it, and a missing field is the planner saying exactly that.
     */
    private Bounds bounds(String reply) {
        return object(reply)
                .map(node -> node.path("bounds"))
                .filter(node -> node.has("floor") && node.has("ceiling"))
                .map(node -> new Bounds(node.path("floor").asInt(), node.path("ceiling").asInt()))
                .orElseGet(Bounds::anywhere);
    }

    /**
     * Everything the reply says the run will need, and how much of each.
     *
     * <p>Anything outside the vocabulary is dropped rather than sinking the objective, and an empty list
     * means the plan falls back to whatever the objective itself implies. Both are the same judgement as
     * everywhere else here: a good answer with one bad line in it is still a good answer.
     */
    private Map<Resource, Integer> needs(String reply) {
        return amounts(reply, "needs");
    }

    /**
     * What the reply puts aside, as a reserve the masks can enforce.
     *
     * <p>Read exactly like the shopping list and meaning something much stronger — see
     * {@link Reserve}. Same forgiveness, for the same reason: a name outside the vocabulary is dropped
     * rather than sinking an otherwise good objective, and nothing put aside is a perfectly ordinary
     * answer.
     */
    private Reserve reserved(String reply) {
        return new Reserve(amounts(reply, "reserve"));
    }

    /** A list of {@code {"item": ..., "amount": ...}} under one key, with anything unrecognised dropped. */
    private Map<Resource, Integer> amounts(String reply, String field) {
        Map<Resource, Integer> listed = new java.util.LinkedHashMap<>();
        object(reply).map(node -> node.path(field)).filter(JsonNode::isArray).ifPresent(entries -> {
            for (JsonNode entry : entries) {
                named(Resource.class, entry.path("item").asText(""))
                        .ifPresent(resource -> listed.merge(resource,
                                Math.max(1, entry.path("amount").asInt(1)), Math::max));
            }
        });
        return listed;
    }

    /** The sentence that came with a reply, for the overlay. Empty when there was none. */
    private String reasonIn(String reply) {
        return object(reply).map(node -> node.path("reason").asText("")).orElse("");
    }

    /** Stops asking for a while, so one bad key is not one failed request per objective for the rest of the run. */
    private void rest() {
        silentUntil.set(System.currentTimeMillis() + RETRY_AFTER_MILLIS);
    }

    /**
     * The objective in the reply, if there is one.
     *
     * <p>The brief asks for bare JSON and models mostly oblige, but "mostly" is not a contract, so the
     * object is cut out of whatever came back rather than the whole reply being handed to the parser.
     *
     * <p>Every field is checked against the vocabulary rather than trusted, and anything outside it is no
     * objective at all. That is not defensiveness about the model — it is the same rule that decides what
     * may be asked for in the first place: the run has to be able to tell when it is done.
     */
    private Optional<Phase> parse(String reply) {
        Optional<JsonNode> found = object(reply);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        JsonNode node = found.get();
        String target = node.path("target").asText("");
        int amount = node.path("amount").asInt(1);
        String reason = node.path("reason").asText("");
        return switch (node.path("objective").asText("").strip().toUpperCase(Locale.ROOT)) {
            case "GATHER" -> resource(target)
                    .map(r -> new Gather(r, amount, sources(node.path("sources")), reason));
            case "TRAVEL" -> named(Terrain.class, target).map(t -> new Travel(t, reason));
            case "DESCEND" -> Optional.of(new Descend(amount, reason));
            case "ASCEND" -> Optional.of(new Ascend(amount, reason));
            case "BUILD" -> named(Structure.class, target).map(b -> new Build(b, reason));
            default -> Optional.empty();
        };
    }

    /** The JSON object inside whatever came back, or empty when there is not one. */
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

    /**
     * Where the resource comes from, as the planner named it: block ids, each with the tool to break it
     * with. Anything the game does not recognise as a block is dropped rather than rejecting the whole
     * objective — a good objective with one bad name in it is still a good objective, and falling back to
     * no sources at all only costs the body the hint.
     */
    private static List<Source> sources(JsonNode listed) {
        List<Source> found = new ArrayList<>();
        if (listed.isArray()) {
            for (JsonNode entry : listed) {
                Tool tool = named(Tool.class, entry.path("tool").asText("")).orElse(null);
                Source.of(entry.path("block").asText(""), tool).ifPresent(found::add);
            }
        }
        return found;
    }

    /**
     * The resource a target names, whichever way it names it.
     *
     * <p>{@code LOG} is what the brief asks for and what it usually gets. {@code minecraft:oak_log} is
     * what it gets often enough to be worth handling: the model reaches for the block id it just listed
     * under {@code sources}, which is the same confusion a person would make and a perfectly good answer
     * to "what am I after". Rather than lose the objective over it, the block is looked up and the
     * resource it yields is taken — {@code stone} means cobblestone, {@code oak_log} means wood.
     *
     * <p>Anything that is neither is still nothing, which is the point of having a vocabulary at all.
     */
    private static Optional<Resource> resource(String target) {
        Optional<Resource> named = named(Resource.class, target);
        if (named.isPresent()) {
            return named;
        }
        return Source.of(target, Tool.HAND)
                .flatMap(source -> Resource.yieldedBy(source.block().defaultBlockState()));
    }

    /** The constant of that enum with this name, ignoring case and surrounding space, or empty. */
    private static <E extends Enum<E>> Optional<E> named(Class<E> type, String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.strip().toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(wanted)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    private static String shorten(String text) {
        String flat = text == null ? "" : text.replace('\n', ' ').strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }

    @Override
    public Optional<Plan> take() {
        return Optional.ofNullable(answer.getAndSet(null));
    }

    @Override
    public boolean pending() {
        return asking.get();
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
