package io.github.ivannavas.autocraftai.mob.ai.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A move the mentor or the planner wrote: a name, the table it is a column of, when it applies, what it
 * does, and when it is finished.
 *
 * <p>This is how the run gets moves nobody programmed. Every move the tables choose between used to be
 * a Java class, and a body that needed a move that did not exist — cutting steps up a pit wall, loading
 * a furnace, throwing a pearl — could not learn its way to it however long it tried, because there was
 * no column to learn. A skill is a column written in a small language of things the body already does:
 * break the block there, put one here, walk, jump, wait, hit that, run from it, use that, open this,
 * move that into the furnace. The mentor writes one when it sees a block that no existing move breaks;
 * the planner writes one when an objective needs an interaction the run has no move for; the run adds
 * it to the table; the table learns whether it was worth having. The models invent, the learning
 * judges, and neither has to trust the other.
 *
 * <h2>What is checked</h2>
 * Everything, before the skill is let anywhere near a table: the name is a plain word that is not
 * already a move, the layer is one of the four, every condition parses, every step is a known verb
 * with a position within reach, and there are no more steps than {@link #MOST_STEPS}. A skill that
 * fails any of it is refused with a sentence the writer is shown next time, and nothing else happens.
 *
 * @param name   the column's name, upper case
 * @param layer  which table it is a column of
 * @param when   whether it applies right now; the legality mask
 * @param until  what finishes it, tested as it runs; never true means it runs to its budget
 * @param steps  what it does, in order
 * @param repeat whether to go round the steps again until {@code until} holds or the budget is spent
 * @param prior  the value the writer thinks it is worth in the row it was written for
 * @param makes  the resource a craft skill makes, in the plan's vocabulary, or empty
 * @param reason the writer's sentence about it, for the overlay and for the writer's own memory
 */
public record Skill(String name, Layer layer, Condition when, Condition until, List<Step> steps,
                    boolean repeat, double prior, String makes, String reason) {

    /** Which table a skill is a column of. */
    public enum Layer {
        /** The goal table: a move chosen at a decision, held for a commitment. */
        GOAL,
        /** The passage table: a way past terrain, asked once a second while the body is stuck. */
        PASSAGE,
        /** The tactics table: a way through the surroundings, asked once a second while they demand it. */
        TACTIC,
        /** The crafting table: something to make or do with what is carried, alongside the move. */
        CRAFT
    }

    /** The verbs a step may use. */
    public enum Verb {
        BREAK, PLACE, WALK, LOOK, JUMP, WAIT, ATTACK, FLEE, DIG, SELECT,
        USE, HOLD, RECIPE, TAKE, PUT, CLOSE
    }

    /**
     * One thing to do.
     *
     * @param verb   what
     * @param at     where, as {@code [forward, up, right]}, for the verbs that act on a place; null else
     * @param word   the word for the verbs that take one — {@code feet}, {@code worst}, an item's name
     * @param amount the number for the verbs that take one — ticks to wait, hold or flee for
     */
    public record Step(Verb verb, int[] at, String word, int amount) {

        public Step {
            at = at == null ? null : at.clone();
            word = word == null ? "" : word.toLowerCase(Locale.ROOT);
        }

        /** One line, for the overlay and the writer's memory. */
        public String describe() {
            StringBuilder out = new StringBuilder(verb.name().toLowerCase(Locale.ROOT));
            if (at != null) {
                out.append(' ').append(at[0]).append(',').append(at[1]).append(',').append(at[2]);
            }
            if (!word.isEmpty()) {
                out.append(' ').append(word);
            }
            if (amount > 0) {
                out.append(' ').append(amount);
            }
            return out.toString();
        }
    }

    /** The most steps a skill may have. Longer is a script, not a move. */
    public static final int MOST_STEPS = 12;
    /** How far from the feet a step may reach, in blocks along any axis. */
    public static final int REACH = 4;
    /** The most ticks a whole skill may run for, whatever it is doing. A minute. */
    public static final int BUDGET_TICKS = 1200;
    /** The most ticks one step may take before the skill is called failed. Ten seconds. */
    public static final int STEP_TICKS = 200;
    /** The most ticks a wait or a hold may ask for: smelting one item takes two hundred. */
    public static final int LONGEST_WAIT = 600;
    /** The most a writer may seed a new skill with, so an invention is tried and not trusted. */
    public static final double MOST_PRIOR = 6.0;

    /**
     * The language, as the briefs state it. One text for the planner and the mentor, so the two never
     * disagree about what a skill may say.
     */
    public static final String LANGUAGE = """
            A skill is a JSON object:
              {"name": "STEPS_UP", "layer": "TACTIC", "prior": 4,
               "when": "cover == PIT and blocks == 0 and solid(1,0,0)",
               "until": "depth <= 0",
               "repeat": true,
               "steps": [{"break": [1,1,0]}, {"break": [1,2,0]}, {"walk": [1,1,0]}],
               "reason": "cut steps up the pit wall"}
            name: 2 to 24 upper-case letters, digits or underscores, and not a move that exists.
            layer: GOAL (a move chosen at decisions and held, like WANDER or MINE), PASSAGE (a way past
              terrain, asked every stuck second, like BREAK_AHEAD), TACTIC (a way through the
              surroundings, asked every second they call for it, like TOWER), CRAFT (something to make or
              do with what is carried, chosen alongside the move, like PLANKS; give it "makes": "<RESOURCE>"
              when it makes one of the plan's resources, so the run stops using it when there is enough).
            when: whether it applies, in this language: and, or, not, brackets, == != < <= > >=,
              numbers, words, and the readings: cover (SKY/PIT/ROOF), light (DAY/NIGHT/DARK), threat
              (the hostile kinds, e.g. SKELETON+ZOMBIE), hostiles (count), nearest (CLOSE/NEAR/FAR/NONE),
              armed, health (LOW/MID/HIGH), blocks (count in hand), candig, roomabove, stuck, y, surface,
              depth (surface - y), sky, night, day, wet, hungry, food, pickaxe, sword, onground,
              menu (NONE/CRAFTING/FURNACE/CHEST/OTHER: what screen is open);
              has(x) how many of x are carried, short(x) how many of x the plan is still short of,
              near(x) whether a block of kind x is within eight blocks — x is a plan resource (LOG,
              PLANKS, COBBLESTONE, RAW_IRON, COAL...) or a Minecraft item or block id (ender_pearl,
              water_bucket, furnace, crafting_table, chest, water, lava);
              and the block tests solid(f,u,r), air(f,u,r), breakable(f,u,r), water(f,u,r), lava(f,u,r).
            Positions are [forward, up, right] from the feet along the way the body faces, each within 4:
              [1,0,0] is the block ahead at foot height, [1,1,0] the one in front of the face, [0,2,0]
              the one over the head.
            until: when it is finished; leave it out for a one-shot list of steps.
            steps (at most 12), each one verb:
              {"break": [f,u,r]} break that block; {"dig": true} the block underfoot, only where safe;
              {"place": [f,u,r]} put a block there, or {"place": "feet"} jump and drop one under the feet
              (what is in hand when that is a block, else any building block);
              {"walk": [f,u,r]} or {"walk": "furnace"} (up to the nearest block of that kind within eight
              blocks: a plan resource or a block id); {"look": [f,u,r]} or {"look": "<block>"};
              {"jump": true}, {"wait": ticks}; {"attack": "worst"} or "nearest"; {"flee": ticks};
              {"select": "sword"|"pickaxe"|"axe"|"block"|"hand"|<item id or plan resource>} put it in hand;
              {"use": [f,u,r]} or {"use": "<block>"} right-click that block or the nearest of that kind
              (opens a table, furnace or chest; works a door, a lever, a bucket on water); {"use": "hand"}
              right-click with what is in hand at whatever is in front (throw a pearl or an egg, place from
              a bucket, eat); {"hold": ticks} keep the use button down (eat, drink, draw a bow);
              so to put a particular block down — a furnace, a table — select it and then place it;
              with a screen open: {"recipe": "<plan resource>"} lay that recipe out from the recipe book
              (in a crafting table, or the body's own 2x2 grid with no screen open); {"take": "result"}
              take what a grid made, or {"take": "output"} what a furnace made; {"put": "<item>"} shift
              an item from the bag into the open furnace or chest (a furnace takes fuel and ore by itself);
              {"close": true} close the screen.
            Each step has ten seconds (a wait or hold up to thirty) and the whole skill a minute; a step
            that does not finish fails the skill, and a skill that keeps failing is retired.
            prior: -6 to 6, the value it starts with in the row it was written for.
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    public Skill {
        if (name == null || !name.matches("[A-Z][A-Z0-9_]{1,23}")) {
            throw new IllegalArgumentException("a skill's name is 2 to 24 upper-case letters, digits or"
                    + " underscores, not '" + name + "'");
        }
        if (layer == null) {
            throw new IllegalArgumentException("a skill needs a layer: GOAL, PASSAGE, TACTIC or CRAFT");
        }
        when = when == null ? Condition.always() : when;
        until = until == null ? Condition.always() : until;
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a skill needs at least one step");
        }
        if (steps.size() > MOST_STEPS) {
            throw new IllegalArgumentException("a skill may have at most " + MOST_STEPS + " steps");
        }
        prior = Math.max(-MOST_PRIOR, Math.min(MOST_PRIOR, prior));
        makes = makes == null ? "" : makes.strip().toUpperCase(Locale.ROOT);
        reason = reason == null ? "" : reason.strip();
    }

    /**
     * A skill from its JSON, or {@link IllegalArgumentException} saying what was wrong with it.
     *
     * @param taken the names already taken — the built-in moves of every table and the skills so far
     */
    public static Skill parse(JsonNode node, Set<String> taken) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("a skill is a JSON object");
        }
        String name = node.path("name").asText("").strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (taken.contains(name)) {
            throw new IllegalArgumentException("the name " + name + " is already a move");
        }
        Layer layer;
        try {
            layer = Layer.valueOf(node.path("layer").asText("").strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("layer must be GOAL, PASSAGE, TACTIC or CRAFT");
        }
        Condition when = Condition.parse(node.path("when").asText(""));
        Condition until = Condition.parse(node.path("until").asText(""));
        List<Step> steps = new ArrayList<>();
        JsonNode listed = node.path("steps");
        if (!listed.isArray()) {
            throw new IllegalArgumentException("steps must be a list");
        }
        for (JsonNode entry : listed) {
            steps.add(step(entry));
        }
        return new Skill(name, layer, when, until, steps, node.path("repeat").asBoolean(false),
                node.path("prior").asDouble(0.0), node.path("makes").asText(""),
                node.path("reason").asText(""));
    }

    /** One step from {@code {"verb": argument}}, with the argument shaped as the verb wants. */
    private static Step step(JsonNode entry) {
        if (!entry.isObject() || entry.size() != 1) {
            throw new IllegalArgumentException("a step is one verb with one argument: " + entry);
        }
        String key = entry.fieldNames().next();
        JsonNode argument = entry.get(key);
        Verb verb;
        try {
            verb = Verb.valueOf(key.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("no verb called '" + key + "'");
        }
        return switch (verb) {
            case BREAK -> new Step(verb, offset(argument, verb), "", 0);
            case WALK, LOOK, USE -> argument.isTextual()
                    ? new Step(verb, null, anyWord(argument, verb), 0)
                    : new Step(verb, offset(argument, verb), "", 0);
            case PLACE -> argument.isTextual()
                    ? new Step(verb, null, word(argument, verb, Set.of("feet")), 0)
                    : new Step(verb, offset(argument, verb), "", 0);
            case JUMP, DIG, CLOSE -> new Step(verb, null, "", 0);
            case WAIT, HOLD -> new Step(verb, null, "", ticks(argument, verb, LONGEST_WAIT));
            case FLEE -> new Step(verb, null, "", ticks(argument, verb, STEP_TICKS));
            case ATTACK -> new Step(verb, null, argument.isTextual()
                    ? word(argument, verb, Set.of("worst", "nearest")) : "worst", 0);
            case TAKE -> new Step(verb, null, word(argument, verb, Set.of("result", "output")), 0);
            case SELECT, RECIPE, PUT -> new Step(verb, null, anyWord(argument, verb), 0);
        };
    }

    private static int[] offset(JsonNode argument, Verb verb) {
        if (!argument.isArray() || argument.size() != 3) {
            throw new IllegalArgumentException(verb.name().toLowerCase(Locale.ROOT)
                    + " takes [forward, up, right]");
        }
        int[] at = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!argument.get(i).isNumber()) {
                throw new IllegalArgumentException("offsets are whole numbers: " + argument);
            }
            at[i] = argument.get(i).asInt();
            if (Math.abs(at[i]) > REACH) {
                throw new IllegalArgumentException("offset " + at[i] + " is further than a skill may reach");
            }
        }
        if (verb == Verb.BREAK && at[0] == 0 && at[2] == 0 && (at[1] == 0 || at[1] == 1)) {
            throw new IllegalArgumentException("break cannot aim at the body's own space; use dig for the block underfoot");
        }
        return at;
    }

    private static String word(JsonNode argument, Verb verb, Set<String> allowed) {
        String word = argument.asText("").strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(word)) {
            throw new IllegalArgumentException(verb.name().toLowerCase(Locale.ROOT) + " takes one of "
                    + allowed + ", not '" + word + "'");
        }
        return word;
    }

    private static String anyWord(JsonNode argument, Verb verb) {
        String word = argument.asText("").strip().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (!word.matches("[a-z][a-z0-9_]{0,40}")) {
            throw new IllegalArgumentException(verb.name().toLowerCase(Locale.ROOT)
                    + " takes an item or resource name, not '" + word + "'");
        }
        return word;
    }

    private static int ticks(JsonNode argument, Verb verb, int most) {
        int ticks = argument.asInt(0);
        if (ticks <= 0 || ticks > most) {
            throw new IllegalArgumentException(verb.name().toLowerCase(Locale.ROOT) + " takes 1 to "
                    + most + " ticks");
        }
        return ticks;
    }

    /** The skill as JSON, the shape it was written in, for the file and for the writer's memory. */
    public ObjectNode toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", name);
        node.put("layer", layer.name());
        node.put("when", when.text());
        node.put("until", until.text());
        node.put("repeat", repeat);
        node.put("prior", prior);
        if (!makes.isEmpty()) {
            node.put("makes", makes);
        }
        node.put("reason", reason);
        ArrayNode listed = node.putArray("steps");
        for (Step step : steps) {
            ObjectNode one = listed.addObject();
            String verb = step.verb().name().toLowerCase(Locale.ROOT);
            switch (step.verb()) {
                case BREAK -> one.putArray(verb).add(step.at()[0]).add(step.at()[1]).add(step.at()[2]);
                case PLACE, USE, WALK, LOOK -> {
                    if (step.at() == null) {
                        one.put(verb, step.word());
                    } else {
                        one.putArray(verb).add(step.at()[0]).add(step.at()[1]).add(step.at()[2]);
                    }
                }
                case JUMP, DIG, CLOSE -> one.put(verb, true);
                case WAIT, FLEE, HOLD -> one.put(verb, step.amount());
                case ATTACK, SELECT, TAKE, RECIPE, PUT -> one.put(verb, step.word());
            }
        }
        return node;
    }

    /** One line, for the overlay and for telling a writer what already exists. */
    public String describe() {
        StringBuilder out = new StringBuilder(name).append(" (").append(layer.name().toLowerCase(Locale.ROOT))
                .append("): when ").append(when.text());
        if (!"true".equals(until.text())) {
            out.append("; until ").append(until.text());
        }
        out.append("; ");
        for (int i = 0; i < steps.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(steps.get(i).describe());
        }
        if (repeat) {
            out.append(", repeat");
        }
        if (!makes.isEmpty()) {
            out.append("; makes ").append(makes);
        }
        if (!reason.isEmpty()) {
            out.append(" — ").append(reason);
        }
        return out.toString();
    }
}
