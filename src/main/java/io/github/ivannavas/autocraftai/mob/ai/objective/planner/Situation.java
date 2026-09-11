package io.github.ivannavas.autocraftai.mob.ai.objective.planner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.ivannavas.autocraftai.mob.ai.DecisionLog;
import io.github.ivannavas.autocraftai.mob.ai.objective.InventoryCensus;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.skill.Skills;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Where the body is, what it is carrying and how its run is going — everything the planner gets to see.
 *
 * <p>Taken on the client thread and immutable from then on, because the planner reads it from a background
 * thread while the game carries on around it. Nothing here is a Minecraft object: by the time the request
 * goes out the world may have moved on, and a record of plain strings and numbers cannot go stale in a way
 * that crashes anything.
 *
 * <p>What is in it is what changes the answer. A plains biome at noon with a full bag is a different
 * question from a cave at midnight on two hearts, and the count of what the run has already got hold of is
 * what stops the planner asking twice for the same thing.
 *
 * <h2>Two questions, one shape</h2>
 * {@link #objective()} is what makes this the same record for both of the questions the planner is asked.
 * Empty, it means the run has nothing to do and the question is "what next". Filled, it means the run has
 * been on that objective for a while without finishing it and the question is "is this still right" — and
 * then {@link #decisions()} is what answers it, because a rut is a thing you see in a sequence of moves and
 * not in a snapshot. Six goes at digging that all lost points, in a state that says there is a wall in
 * front, is a body at the bottom of a hole; no inventory count says that.
 *
 * <h2>And the run's own record</h2>
 * The second half is what the world does not show and the planner kept getting wrong without. How many
 * times the body has died and what killed it last, because a death empties the bag and the planner was
 * reading "obtained: nothing" after a death as a run that had never started. What the objective in hand
 * is still short of — the planks a pickaxe needs, the pickaxe stone needs — because "get twelve
 * cobblestone" with no pickaxe in the bag is an objective the body will swing its fists at for ten
 * minutes, and the shortage was visible the whole time to anyone who looked. And how long it has been
 * since the objective got any nearer, which is what tells a slow objective from a stuck one.
 *
 * <p>{@link #language()} changes nothing about which objective is right, and is here because the sentence
 * the planner writes to explain itself is shown on a panel that speaks the player's language.
 *
 * @param deaths                  how many times the body has died in this world
 * @param lastDeath               what the server said killed it last, or empty
 * @param shortOf                 what the objective in hand needs and the bag lacks, in plain words
 * @param minutesWithoutProgress  how long the objective in hand has got no nearer
 * @param note                    a word from the mentor, when it gave up on the last objective and asked
 *                                for this question to be put; empty otherwise
 */
public record Situation(
        String biome,
        String dimension,
        boolean night,
        int lightLevel,
        float health,
        float maxHealth,
        int food,
        int maxFood,
        int depth,
        int hostilesNearby,
        List<String> carrying,
        Map<Resource, Integer> obtained,
        List<String> achieved,
        String objective,
        List<String> decisions,
        String language,
        int deaths,
        String lastDeath,
        List<String> shortOf,
        int minutesWithoutProgress,
        String note,
        String table) {

    /** How far out a mob counts as being on top of us. */
    private static final double THREAT_RANGE = 16.0;
    /** Minecraft's day is 24000 ticks; the sun is down between these two. */
    private static final long DUSK = 13000L;
    private static final long DAWN = 23000L;
    /** A full hunger bar, which the game has no getter for because it is never anything else. */
    private static final int MAX_FOOD = 20;
    /** At or below this the situation says STARVING, and a fresh question is worth asking. */
    public static final int STARVING_AT = 3;
    /** Said when the game has not settled on one, which is never in practice. */
    private static final String DEFAULT_LANGUAGE = "en_us";

    public Situation {
        carrying = List.copyOf(carrying);
        obtained = Map.copyOf(obtained);
        achieved = List.copyOf(achieved);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        objective = objective == null ? "" : objective;
        language = language == null || language.isBlank() ? DEFAULT_LANGUAGE : language;
        lastDeath = lastDeath == null ? "" : lastDeath;
        shortOf = shortOf == null ? List.of() : List.copyOf(shortOf);
        note = note == null ? "" : note.strip();
        table = table == null ? "" : table.strip();
    }

    /**
     * Reads the world as it stands, with nothing yet said about the run's own record. Must be called on
     * the client thread. See {@link #withRun} for the rest.
     *
     * @param objective the objective already in hand when this is a review, or empty when it is not
     */
    public static Situation of(LocalPlayer player, InventoryCensus obtained, List<String> achieved,
                               String objective) {
        long timeOfDay = Math.floorMod(player.level().getOverworldClockTime(), 24000L);
        Map<Resource, Integer> totals = new LinkedHashMap<>();
        for (Resource resource : Resource.values()) {
            totals.put(resource, obtained.count(resource));
        }
        return new Situation(
                player.level().getBiome(player.blockPosition()).getRegisteredName(),
                player.level().dimension().identifier().getPath(),
                timeOfDay >= DUSK && timeOfDay < DAWN,
                player.level().getMaxLocalRawBrightness(player.blockPosition()),
                player.getHealth(),
                player.getMaxHealth(),
                player.getFoodData().getFoodLevel(),
                MAX_FOOD,
                player.getBlockY(),
                hostilesNear(player),
                carried(player.getInventory()),
                totals,
                achieved,
                objective,
                DecisionLog.get().recent(),
                Minecraft.getInstance().getLanguageManager().getSelected(),
                0, "", List.of(), 0, "", "");
    }

    /** The same moment, with the run's own record filled in. */
    public Situation withRun(int deaths, String lastDeath, List<String> shortOf, int minutesWithoutProgress,
                             String note) {
        return new Situation(biome, dimension, night, lightLevel, health, maxHealth, food, maxFood, depth,
                hostilesNearby, carrying, obtained, achieved, objective, decisions, language,
                deaths, lastDeath, shortOf, minutesWithoutProgress, note, table);
    }

    /** The same moment, with where the crafting table is said in words. */
    public Situation withTable(String table) {
        return new Situation(biome, dimension, night, lightLevel, health, maxHealth, food, maxFood, depth,
                hostilesNearby, carrying, obtained, achieved, objective, decisions, language,
                deaths, lastDeath, shortOf, minutesWithoutProgress, note, table);
    }

    private static int hostilesNear(LocalPlayer player) {
        return player.level().getEntities(player, player.getBoundingBox().inflate(THREAT_RANGE),
                        candidate -> candidate instanceof Enemy
                                && candidate instanceof LivingEntity living && living.isAlive())
                .size();
    }

    /**
     * The bag as a person would read it — "3 x Oak Log" — rather than as slots.
     *
     * <p>The names are the game's own, so they arrive in whatever language the client is set to. That is
     * the right way round: the model reads any language and the item names it sees then match the ones on
     * the panel beside its answer.
     */
    private static List<String> carried(Inventory inventory) {
        Map<String, Integer> byName = new LinkedHashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                byName.merge(stack.getHoverName().getString(), stack.getCount(), Integer::sum);
            }
        }
        return byName.entrySet().stream()
                .map(entry -> entry.getValue() + " x " + entry.getKey())
                .toList();
    }

    /** Whether this is a review of an objective already in hand rather than a request for a new one. */
    public boolean isReview() {
        return !objective.isEmpty();
    }

    /**
     * A coarse fingerprint of the situation, for the planner's answer cache. Two situations with the same
     * fingerprint get the same objective, so the second one is served from memory rather than the network.
     * Deliberately lossy — the biome family, the time of day, the danger, and roughly what has been got —
     * because that is what actually changes the answer, and finer detail would only turn every step into a
     * cache miss.
     *
     * <p>What has been achieved is part of it, and has to be: "what next?" asked just before an objective
     * and just after it is the same coarse situation — same biome, same bag near enough, between orders
     * both times — and served from memory the second question got the first question's answer back, an
     * objective already done and, once the body had wandered off, not doable again.
     *
     * <p>So are the deaths and the mentor's note. A death is a new situation whatever else looks the same,
     * because the bag is gone; and a question the mentor asked to have put must not be answered from the
     * memory of the objective it just gave up on.
     */
    public String signature() {
        return dimension + '|' + biome + '|' + (night ? "night" : "day")
                + '|' + (health < maxHealth / 2 ? "hurt" : "ok")
                + '|' + (food <= STARVING_AT ? "starving" : food < 10 ? "hungry" : "fed")
                + '|' + (hostilesNearby > 0 ? "threat" : "safe")
                + '|' + tierReached() + '|' + objective
                + '|' + achieved.size() + ':' + (achieved.isEmpty() ? "-" : achieved.get(achieved.size() - 1))
                + '|' + deaths + '|' + note;
    }

    /** How far up the chain the run has got, in a word — what the next objective hangs on. */
    private String tierReached() {
        return obtained.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> e.getKey().name())
                .sorted()
                .reduce((a, b) -> a + ',' + b)
                .orElse("nothing");
    }

    /** One line, for the overlay: enough to tell one call apart from the next. */
    public String summary() {
        return String.format(Locale.ROOT, "%s, Y %d, %.0f/%.0f HP, %d/%d food%s%s",
                biome.replace("minecraft:", ""), depth, health, maxHealth, food, maxFood,
                isReview() ? ", " + objective : "",
                deaths > 0 ? ", " + deaths + " death" + (deaths == 1 ? "" : "s") : "");
    }

    /**
     * The situation as the prompt states it. Plain lines rather than JSON: this is the half of the
     * conversation a person would want to be able to read in a log and recognise the run from.
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("Biome: ").append(biome).append(" (").append(dimension).append(")\n");
        text.append("Time: ").append(night ? "night" : "day")
                .append(", light ").append(lightLevel).append("/15\n");
        text.append(String.format(Locale.ROOT, "Health: %.1f/%.1f%n", health, maxHealth));
        text.append(String.format(Locale.ROOT, "Hunger: %d/%d%n", food, maxFood));
        if (food <= STARVING_AT) {
            text.append("STARVING: the hunger bar is almost empty. If there is nothing edible in the inventory"
                    + " this comes before everything else: the objective has to be the quickest way to food"
                    + " — the surface and its animals, or FOOD itself when it is already up there.\n");
        }
        text.append("Height Y: ").append(depth).append('\n');
        text.append("Hostiles in sight: ").append(hostilesNearby).append('\n');
        text.append("Inventory: ").append(carrying.isEmpty() ? "empty" : String.join(", ", carrying))
                .append('\n');
        if (!table.isEmpty()) {
            // Said apart from the bag because a table is the one thing on a list the body need not
            // carry to have: it is placed to be used, and both agents planned a second one from logs
            // it did not have while its own stood forty blocks back.
            text.append("Crafting table: ").append(table).append('\n');
        }
        text.append("Obtained over the whole run: ").append(totals()).append('\n');
        text.append("Objectives already completed: ")
                .append(achieved.isEmpty() ? "none" : String.join(", ", achieved)).append('\n');
        if (deaths > 0) {
            text.append("Deaths so far: ").append(deaths)
                    .append(" (the whole bag is lost on death, and the total above restarts from what "
                            + "is carried afterwards)");
            if (!lastDeath.isEmpty()) {
                text.append("; the last one: \"").append(lastDeath).append('"');
            }
            text.append('\n');
        }
        text.append("Player's language: ").append(language).append('\n');
        String chronicle = io.github.ivannavas.autocraftai.mob.ai.objective.Chronicle.get().describe();
        if (!chronicle.isEmpty()) {
            text.append('\n').append(chronicle);
        }
        String skills = Skills.get().catalogue();
        if (!skills.isEmpty()) {
            text.append("Skills the player already has (do not write these again):\n")
                    .append(skills).append('\n');
        }

        if (isReview()) {
            text.append('\n');
            text.append("REVIEW. The player has been on this objective for a while without finishing it:\n");
            text.append("  ").append(objective).append('\n');
            text.append(readiness());
            if (minutesWithoutProgress > 0) {
                text.append("No progress on it for ").append(minutesWithoutProgress).append(" minute")
                        .append(minutesWithoutProgress == 1 ? "" : "s").append(".\n");
            }
            text.append("Its last moves, oldest first:\n");
            if (decisions.isEmpty()) {
                text.append("  (none recorded)\n");
            } else {
                decisions.forEach(move -> text.append("  ").append(move).append('\n'));
            }
            text.append("Decide whether to keep this objective or replace it.");
        } else {
            if (!note.isEmpty()) {
                text.append("\nThe coach gave up on the last objective and asked for a new one: \"")
                        .append(note).append("\". Do not set the same one again.\n");
            }
            text.append("\nThe player has no objective. Choose the next one.");
        }
        return text.toString();
    }

    /** What the objective in hand is still short of, as a line, or that it is short of nothing. */
    public String readiness() {
        if (shortOf.isEmpty()) {
            return "Ready: it holds everything the objective needs but the objective's own item.\n";
        }
        return "Still short for it: " + String.join(", ", shortOf) + ".\n";
    }

    private String totals() {
        List<String> parts = obtained.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getValue() + " " + entry.getKey().name())
                .toList();
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }
}
