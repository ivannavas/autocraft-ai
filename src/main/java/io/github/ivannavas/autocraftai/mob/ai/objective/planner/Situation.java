package io.github.ivannavas.autocraftai.mob.ai.objective.planner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.ivannavas.autocraftai.mob.ai.DecisionLog;
import io.github.ivannavas.autocraftai.mob.ai.objective.InventoryCensus;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
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
 * <p>{@link #language()} changes nothing about which objective is right, and is here because the sentence
 * the planner writes to explain itself is shown on a panel that speaks the player's language.
 */
public record Situation(
        String biome,
        String dimension,
        boolean night,
        int lightLevel,
        float health,
        float maxHealth,
        int depth,
        int hostilesNearby,
        List<String> carrying,
        Map<Resource, Integer> obtained,
        List<String> achieved,
        String objective,
        List<String> decisions,
        String language) {

    /** How far out a mob counts as being on top of us. */
    private static final double THREAT_RANGE = 16.0;
    /** Minecraft's day is 24000 ticks; the sun is down between these two. */
    private static final long DUSK = 13000L;
    private static final long DAWN = 23000L;
    /** Said when the game has not settled on one, which is never in practice. */
    private static final String DEFAULT_LANGUAGE = "en_us";

    public Situation {
        carrying = List.copyOf(carrying);
        obtained = Map.copyOf(obtained);
        achieved = List.copyOf(achieved);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        objective = objective == null ? "" : objective;
        language = language == null || language.isBlank() ? DEFAULT_LANGUAGE : language;
    }

    /**
     * Reads the world as it stands. Must be called on the client thread.
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
                player.getBlockY(),
                hostilesNear(player),
                carried(player.getInventory()),
                totals,
                achieved,
                objective,
                DecisionLog.get().recent(),
                Minecraft.getInstance().getLanguageManager().getSelected());
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

    /** One line, for the overlay: enough to tell one call apart from the next. */
    public String summary() {
        return String.format(Locale.ROOT, "%s, Y %d, %.0f/%.0f HP%s",
                biome.replace("minecraft:", ""), depth, health, maxHealth,
                isReview() ? ", " + objective : "");
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
        text.append("Height Y: ").append(depth).append('\n');
        text.append("Hostiles in sight: ").append(hostilesNearby).append('\n');
        text.append("Inventory: ").append(carrying.isEmpty() ? "empty" : String.join(", ", carrying))
                .append('\n');
        text.append("Obtained over the whole run: ").append(totals()).append('\n');
        text.append("Objectives already completed: ")
                .append(achieved.isEmpty() ? "none" : String.join(", ", achieved)).append('\n');
        text.append("Player's language: ").append(language).append('\n');

        if (isReview()) {
            text.append('\n');
            text.append("REVIEW. The player has been on this objective for a while without finishing it:\n");
            text.append("  ").append(objective).append('\n');
            text.append("Its last moves, oldest first:\n");
            if (decisions.isEmpty()) {
                text.append("  (none recorded)\n");
            } else {
                decisions.forEach(move -> text.append("  ").append(move).append('\n'));
            }
            text.append("Decide whether to keep this objective or replace it.");
        } else {
            text.append("\nThe player has no objective. Choose the next one.");
        }
        return text.toString();
    }

    private String totals() {
        List<String> parts = obtained.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getValue() + " " + entry.getKey().name())
                .toList();
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }
}
