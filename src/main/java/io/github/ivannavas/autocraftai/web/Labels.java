package io.github.ivannavas.autocraftai.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.github.ivannavas.autocraftai.mob.ai.CraftChoice;
import io.github.ivannavas.autocraftai.mob.ai.FocusKind;
import io.github.ivannavas.autocraftai.mob.ai.GoalAction;
import io.github.ivannavas.autocraftai.mob.ai.Interruption;
import io.github.ivannavas.autocraftai.mob.ai.Perception;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.objective.Rung;
import io.github.ivannavas.autocraftai.mob.ai.objective.Structure;
import io.github.ivannavas.autocraftai.mob.ai.objective.Terrain;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.PlannerLog;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;

/**
 * The overlay's words, in whatever language the game is set to.
 *
 * <p>The page ships English in its own markup and this replaces it. Which means the panel follows the game
 * rather than the mod: switch Minecraft to Spanish and the overlay is Spanish the next time a browser
 * connects, because both are reading the same {@code lang/*.json} files the rest of the mod uses. A
 * language we have no file for falls back to the English already written into the page, which is the right
 * failure — a readable panel in the wrong language beats a blank one in the right one.
 *
 * <h2>Why nothing is translated on the wire</h2>
 * Everything the tables are keyed by stays in the enum names. Those are what is written to disk, and
 * translating them at the source would orphan every saved table the first time somebody changed a word. So
 * the names travel as they are and this is a dictionary the page looks them up in — which is also why a
 * missing entry is dropped rather than sent as the key: the page's own fallback prints a readable version
 * of the raw name, and a key nobody has translated yet shows up as "dig down" rather than as
 * "overlay.autocraft-ai.action.DIG_DOWN".
 *
 * <h2>Templates, not sentences</h2>
 * The lookup goes through {@link Language} rather than {@code I18n}, and that is not a preference. A
 * phrase with a placeholder in it — "%s decisions" — comes back from {@code I18n.get} run through
 * {@code String.format} with no arguments, which for a positional {@code %1$s} is not an error the caller
 * sees but the literal text "Format error: ...". What the page needs is the template, because the page is
 * what has the numbers to put in it.
 *
 * <h2>Read from the request thread, on purpose</h2>
 * The lookup is a volatile read of an immutable map, so resolving here rather than marshalling onto the
 * game thread is safe and keeps the language current: it happens when a page connects, which is after the
 * resource pack has loaded and after any change the player has made in the options.
 */
final class Labels {

    private static final String PREFIX = "overlay.autocraft-ai.";

    /** Words that are ours rather than an enum's, keyed by the same suffix the page asks for. */
    private static final List<String> TEXT = List.of(
            "objective", "doing", "making",
            "goals", "crafts", "interrupts", "interrupts.suffix", "planner",
            "head.situation", "head.craft", "head.stuck",
            "empty.goals", "empty.crafts", "empty.interrupts", "empty.planner", "ago",
            "machine", "cpu", "memory",
            "key.worse", "key.better", "key.bar", "key.best", "key.distance", "key.tables",
            "status.connecting", "status.live", "status.offline",
            "tally.decisions", "tally.random",
            "verdict.avoid", "verdict.none",
            "qualifier.wall", "qualifier.blocks", "qualifier.hungry",
            "bag.empty", "bag.holding",
            "shape.gather", "shape.travel", "shape.descend", "shape.ascend", "shape.build",
            "tip.cpu", "tip.cpu.machine", "tip.memory", "tip.memory.machine");

    private Labels() {
    }

    /** Everything the page needs to draw itself, as JSON. */
    static String json() {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        groups.put("text", words("", TEXT));
        groups.put("action", enums("action", GoalAction.values()));
        groups.put("focus", enums("focus", FocusKind.values()));
        groups.put("distance", enums("distance", Perception.Distance.values()));
        groups.put("health", enums("health", Perception.Health.values()));
        groups.put("rescue", enums("rescue", Interruption.values()));
        groups.put("craft", enums("craft", CraftChoice.values()));
        groups.put("resource", enums("resource", Resource.values()));
        groups.put("terrain", enums("terrain", Terrain.values()));
        groups.put("structure", enums("structure", Structure.values()));
        groups.put("planner", enums("planner", PlannerLog.Kind.values()));
        // The seven fixed rungs, plus the two states that are not an objective at all.
        Map<String, String> objectives = enums("objective", Rung.values());
        objectives.putAll(words("objective.", List.of("PLANNING", "DONE")));
        groups.put("objective", objectives);
        return Json.of(locale(), groups);
    }

    private static Map<String, String> enums(String group, Enum<?>[] values) {
        Map<String, String> words = new LinkedHashMap<>();
        for (Enum<?> value : values) {
            translated(group + '.' + value.name())
                    .ifPresent(text -> words.put(value.name(), text));
        }
        return words;
    }

    private static Map<String, String> words(String group, List<String> suffixes) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String suffix : suffixes) {
            translated(group + suffix).ifPresent(text -> found.put(suffix, text));
        }
        return found;
    }

    /** The template for this key, or empty when the loaded language has no translation for it. */
    private static Optional<String> translated(String suffix) {
        String key = PREFIX + suffix;
        Language language = Language.getInstance();
        return language.has(key) ? Optional.of(language.getOrDefault(key)) : Optional.empty();
    }

    /**
     * The game's language as a browser locale — {@code es_es} becomes {@code es-ES} — so the page's own
     * number formatting agrees with the words around it about which decimal separator to use.
     */
    private static String locale() {
        String selected = Minecraft.getInstance().getLanguageManager().getSelected();
        String[] parts = selected.split("_");
        if (parts.length < 2) {
            return parts[0].toLowerCase(Locale.ROOT);
        }
        return parts[0].toLowerCase(Locale.ROOT) + '-' + parts[1].toUpperCase(Locale.ROOT);
    }
}
