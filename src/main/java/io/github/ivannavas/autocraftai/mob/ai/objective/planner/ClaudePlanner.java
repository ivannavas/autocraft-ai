package io.github.ivannavas.autocraftai.mob.ai.objective.planner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ivannavas.autocraftai.mob.ai.skill.Skill;
import io.github.ivannavas.autocraftai.mob.ai.skill.Skills;
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
import io.github.ivannavas.autocraftai.mob.ai.objective.Way;
import io.github.ivannavas.autocraftai.mob.ai.objective.Whereabouts;
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
 * <p>A run is a world. Arriving in a new one starts a new conversation — see {@link #reset()} — because
 * the last world's transcript is a history of objectives this body never had, and a reply still in the air
 * from that world is dropped rather than adopted as the first plan of this one.
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
    /** Room for the thinking as well as the answer; see {@code ClaudeMentor#MAX_TOKENS}. */
    private static final int MAX_TOKENS = 2500;
    private static final int TIMEOUT_SECONDS = 30;
    /** One transcript per world, so the planner remembers what it has already asked for in this one. */
    private static final String CONVERSATION = "run";
    /** How long to leave a failing planner alone before asking it again. */
    private static final long RETRY_AFTER_MILLIS = 60_000L;
    /**
     * The least time between any two questions, answered or not. A pace, not a filter: a question that
     * comes too soon is owed and put when the interval is up, never dropped — the run has no other source
     * of objectives, and a dropped question used to read as "nobody is answering" and send it down a
     * fixed ladder. What actually saves tokens is the cache below; this only stops a pathological loop
     * from turning into a request a second.
     */
    private static final long MIN_INTERVAL_MILLIS = 10_000L;
    /** How long a cached answer stands for a situation that has not meaningfully changed. */
    private static final long CACHE_TTL_MILLIS = 120_000L;
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
    /** When the last question actually went out, for the minimum interval between them. */
    private final AtomicLong lastAsked = new AtomicLong();
    /** A question that came too soon after the last and is owed as soon as the interval allows. */
    private final AtomicBoolean wanted = new AtomicBoolean();
    /** Why the last request came to nothing, for the overlay, while no new one will be made. */
    private final AtomicReference<String> trouble = new AtomicReference<>();
    /** The last situation answered and what it was answered with, so an identical one skips the network. */
    private final AtomicReference<String> cachedSignature = new AtomicReference<>("");
    private final AtomicReference<Plan> cachedPlan = new AtomicReference<>();
    private final AtomicLong cachedAt = new AtomicLong();
    /**
     * Which world this is, counted from the first. Names the conversation, and is captured by every
     * request on its way out so that a reply landing after the world it was asked about is gone can be
     * recognised and thrown away.
     */
    private final AtomicLong world = new AtomicLong();

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
            log.info("No Anthropic key ({} or {}); the run will have no objectives",
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
        long now = System.currentTimeMillis();
        if (now < silentUntil.get()) {
            // Resting after a failure; trouble() says why, and the next call after the rest tries again.
            return;
        }
        if (asking.get()) {
            // One is already out; its answer is the one coming, and nothing further is owed.
            return;
        }
        if (now - lastAsked.get() < MIN_INTERVAL_MILLIS) {
            // Too soon, so owed: pending() holds true and the caller, which asks every decision while it
            // has no objective, puts the question the moment the pace allows.
            wanted.set(true);
            return;
        }
        if (!asking.compareAndSet(false, true)) {
            return;
        }
        wanted.set(false);
        // The description is built on the caller's thread, and that is where it has to be built: it reads
        // the world, and the world is only safe to read from the game thread.
        Situation asked = situation.get();
        // The same situation, recently answered, is served from memory: the strategist would only say the
        // same thing, and saying it again costs a call for nothing.
        String signature = asked.signature();
        if (signature.equals(cachedSignature.get()) && now - cachedAt.get() < CACHE_TTL_MILLIS
                && cachedPlan.get() != null) {
            answer.set(cachedPlan.get());
            asking.set(false);
            PlannerLog.get().kept("from memory (same situation)", signature);
            return;
        }
        lastAsked.set(now);
        String prompt = asked.describe();
        PlannerLog.get().asked(asked.summary(), prompt);
        thread.execute(() -> {
            try {
                ask(signature, prompt);
            } finally {
                asking.set(false);
            }
        });
    }

    /** Tries again this many times, and waits this long the first time, doubling. */
    private static final int TRIES = 3;
    private static final long FIRST_WAIT_MILLIS = 3_000L;

    /**
     * One call, with a couple of quick retries for the failures that are worth one.
     *
     * <p>An overloaded API and a dropped connection are a few seconds of trouble, and resting a whole
     * minute for them left the body with no objective at all, wandering: three planner calls failed in
     * twenty minutes and the run spent them in UNPLANNED.
     */
    private String call(long asked, String prompt) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= TRIES; attempt++) {
            try {
                return agent.execute(conversation(asked), prompt).response();
            } catch (RuntimeException e) {
                last = e;
                if (attempt == TRIES || !Failure.worthRetrying(e)) {
                    throw e;
                }
                long wait = FIRST_WAIT_MILLIS << (attempt - 1);
                log.info("The planner's call failed ({}); trying again in {} s", Failure.describe(e), wait / 1000);
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

    private void ask(String signature, String prompt) {
        long asked = world.get();
        try {
            String reply = call(asked, prompt);
            if (world.get() != asked) {
                // The world this was about has been left. Whatever it says is about somewhere else.
                log.info("Dropping a plan that arrived after its world was left");
                return;
            }
            takeSkills(reply);
            Optional<Phase> errand = parse(reply);
            if (errand.isPresent()) {
                // Built before it is logged: an empty list is filled in from the objective, and the line
                // in the log should say what the run is actually going to work to.
                Plan plan = new Plan(errand.get(), bounds(reply), needs(reply), reserved(reply));
                log.info("Next objective: {} ({}) needing {}{} - {}",
                        plan.objective(), plan.bounds(), plan.needs(),
                        plan.reserved().isEmpty() ? "" : ", holding back " + plan.reserved().kept(),
                        plan.objective().reason());
                PlannerLog.get().answered(plan, plan.objective().reason(), reply);
                trouble.set(null);
                answer.set(plan);
                cachedSignature.set(signature);
                cachedPlan.set(plan);
                cachedAt.set(System.currentTimeMillis());
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
            rest("not an objective: " + said);
        } catch (RuntimeException e) {
            // The whole chain, because the client wraps everything in one generic sentence and the run's
            // log could not tell a rate limit from a bad key. The key itself is never in it.
            String why = Failure.describe(e);
            log.warn("Could not reach the planner ({}); the run carries on with its last orders", why);
            PlannerLog.get().failed(shorten(why), null);
            rest(shorten(why));
        }
    }

    /**
     * The skills the reply hands the player, checked and offered to the run. A skill that does not parse
     * is logged with what was wrong and dropped; the objective beside it is taken all the same.
     */
    private void takeSkills(String reply) {
        object(reply).map(node -> node.path("forget")).filter(JsonNode::isArray).ifPresent(listed -> {
            Set<String> known = Skills.get().names();
            for (JsonNode entry : listed) {
                String name = (entry.isTextual() ? entry.asText() : entry.path("name").asText(""))
                        .strip().toUpperCase(java.util.Locale.ROOT);
                String why = entry.isTextual() ? "" : entry.path("why").asText("").strip();
                if (known.contains(name)) {
                    Skills.get().offerForget(name, why.isEmpty() ? "the planner forgot it" : why);
                    log.info("The planner forgot a skill: {} ({})", name, why);
                    PlannerLog.get().plannerNoted("forgot skill " + name + (why.isEmpty() ? "" : ": " + why));
                }
            }
        });
        object(reply).map(node -> node.path("skills")).filter(JsonNode::isArray).ifPresent(listed -> {
            Set<String> taken = Skills.taken();
            for (JsonNode entry : listed) {
                try {
                    Skill skill = Skill.parse(entry, taken);
                    taken.add(skill.name());
                    Skills.get().offer(skill);
                    log.info("The planner wrote a skill: {}", skill.describe());
                    PlannerLog.get().plannerNoted("new skill " + skill.describe());
                } catch (IllegalArgumentException e) {
                    log.warn("The planner's skill was refused: {}", e.getMessage());
                    PlannerLog.get().plannerNoted("skill refused: " + e.getMessage());
                }
            }
        });
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

    /**
     * Stops asking for a while, so one bad key is not one failed request per objective for the rest of
     * the run, and remembers why for the overlay. Nothing is owed across a rest: the next question is
     * put when the rest is over, from the situation then.
     */
    private void rest(String why) {
        trouble.set(why);
        wanted.set(false);
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
                Source.of(entry.path("block").asText(""), tool)
                        .map(source -> source.with(whereabouts(entry, source.where())))
                        .ifPresent(found::add);
            }
        }
        return found;
    }

    /**
     * Where a source is and how to reach it, as the entry says — with whatever it left unsaid filled in
     * from what the run always assumed about the resource.
     *
     * <p>Filled in piecewise rather than all or nothing: a planner that named a band and forgot the ways
     * has still said something worth keeping, and the ways it forgot are the same ones the resource has
     * always had. A name outside the vocabulary is dropped, as everywhere else in a reply.
     */
    private static Whereabouts whereabouts(JsonNode entry, Whereabouts assumed) {
        JsonNode band = entry.path("band");
        Bounds where = band.has("floor") && band.has("ceiling")
                ? new Bounds(band.path("floor").asInt(), band.path("ceiling").asInt()) : assumed.band();
        List<Terrain> terrain = names(Terrain.class, entry.path("terrain"));
        List<Way> ways = names(Way.class, entry.path("ways"));
        return new Whereabouts(where,
                terrain.isEmpty() ? assumed.terrain() : terrain,
                ways.isEmpty() ? assumed.ways() : EnumSet.copyOf(ways));
    }

    /** The constants of an enum a JSON array names, in order, with anything unrecognised dropped. */
    private static <E extends Enum<E>> List<E> names(Class<E> type, JsonNode listed) {
        List<E> found = new ArrayList<>();
        if (listed.isArray()) {
            for (JsonNode entry : listed) {
                named(type, entry.asText("")).ifPresent(found::add);
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

    /** The transcript for a world: the first is {@code run}, the next {@code run-1}, and so on. */
    private static String conversation(long world) {
        return world == 0 ? CONVERSATION : CONVERSATION + '-' + world;
    }

    @Override
    public void reset() {
        world.incrementAndGet();
        answer.set(null);
        // A key that failed in the last world is not going to work in this one either, but a network
        // that was down may well be back, and the first question of a run is the one worth asking.
        silentUntil.set(0L);
        wanted.set(false);
        trouble.set(null);
    }

    @Override
    public Optional<Plan> take() {
        return Optional.ofNullable(answer.getAndSet(null));
    }

    @Override
    public boolean pending() {
        return asking.get() || wanted.get();
    }

    @Override
    public Optional<String> trouble() {
        return System.currentTimeMillis() < silentUntil.get()
                ? Optional.ofNullable(trouble.get())
                : Optional.empty();
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
