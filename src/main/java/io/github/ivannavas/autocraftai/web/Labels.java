package io.github.ivannavas.autocraftai.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.github.ivannavas.autocraftai.mob.ai.CraftChoice;
import io.github.ivannavas.autocraftai.mob.ai.FocusKind;
import io.github.ivannavas.autocraftai.mob.ai.GoalAction;
import io.github.ivannavas.autocraftai.mob.ai.Tactic;
import io.github.ivannavas.autocraftai.mob.ai.Ground;
import io.github.ivannavas.autocraftai.mob.ai.Passage;
import io.github.ivannavas.autocraftai.mob.ai.Perception;
import io.github.ivannavas.autocraftai.mob.ai.Spot;
import io.github.ivannavas.autocraftai.mob.ai.Swim;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.objective.Rung;
import io.github.ivannavas.autocraftai.mob.ai.objective.Structure;
import io.github.ivannavas.autocraftai.mob.ai.objective.Terrain;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import io.github.ivannavas.autocraftai.mob.ai.objective.Way;
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
            "goals", "crafts", "planner", "placement", "position",
            "water", "timing", "head.timing", "empty.timing", "empty.folders", "pursuit",
            "passage", "head.passage", "empty.passage",
            "tactics", "head.tactics", "empty.tactics",
            "threat.NONE", "threat.ZOMBIE", "threat.SKELETON", "threat.CREEPER", "threat.SPIDER",
            "threat.OTHER", "threat.MANY", "threat.ANY", "health.OK",
            "cover.SKY", "cover.PIT", "cover.ROOF", "light.DAY", "light.NIGHT", "light.DARK",
            "qualifier.armed", "qualifier.unarmed",
            "want.UP", "want.DOWN", "want.FLAT", "want.TOWARD",
            "ahead.NONE", "ahead.STEP", "ahead.WALL", "ahead.GAP", "ahead.LEAVES", "ahead.SOFT",
            "ahead.HARD",
            "above.OPEN", "above.CEILING", "qualifier.breakable", "qualifier.diggable",
            "head.block", "head.tool", "head.terrain", "head.ways",
            "plan", "plan.band", "plan.anyheight", "plan.needs", "plan.reserve", "plan.sources",
            "plan.nothing", "plan.noreason", "plan.unplanned", "plan.none", "plan.active",
            "pursuits", "folder.live", "folder.states", "folder.decisions", "folder.random",
            "folder.GO", "folder.DOWN", "folder.UP", "folder.BUILD", "folder.THREAT", "folder.IDLE",
            "shared", "planner.prompt", "planner.reply", "planner.plan",
            "head.placement", "head.position", "head.water",
            "depth.WADING", "depth.SWIMMING", "depth.SUBMERGED",
            "air.FULL", "air.LOW", "air.EMPTY",
            "breath.ABOVE", "breath.NEAR", "breath.FAR", "breath.NONE",
            "shore.NEAR", "shore.FAR", "shore.NONE",
            "band.IN", "band.BELOW", "band.ABOVE", "band.ANY",
            "trail.FRESH", "trail.CIRCLING", "trail.PINNED", "terrain.IN", "terrain.OUT",
            "head.situation", "head.craft",
            "empty.goals", "empty.crafts", "empty.planner", "empty.placement",
            "empty.position", "empty.water",
            "ago", "going.GET", "going.GO", "going.DOWN", "going.UP", "going.BUILD", "going.NONE",
            "machine", "cpu", "memory",
            "key.worse", "key.better", "key.bar", "key.best", "key.distance", "key.tables",
            "status.connecting", "status.live", "status.offline",
            "tally.decisions", "tally.random", "tally.stalls",
            "verdict.avoid", "verdict.none",
            "qualifier.wall", "qualifier.blocks", "qualifier.hungry",
            "bag.empty", "bag.holding", "needs.nothing", "needs.short",
            "shape.gather", "shape.travel", "shape.descend", "shape.ascend", "shape.build",
            "tip.cpu", "tip.cpu.machine", "tip.memory", "tip.memory.machine",
            "driving",
            "tab.overview", "tab.learning", "tab.talk", "tab.skills", "tab.logs", "tab.clips", "tab.control",
            "vitals.health", "vitals.food", "vitals.position", "vitals.biome", "vitals.time", "vitals.fps",
            "vitals.world", "vitals.clips", "time.day", "time.night", "time.dusk", "time.dawn",
            "now.situation", "now.folder", "now.decisions",
            "overview.plan", "overview.now", "overview.talk", "overview.skills", "overview.alerts",
            "overview.machine", "overview.more",
            "learning.search", "learning.opinion", "learning.rows",
            "talk.filter", "talk.all", "talk.answered", "talk.failed",
            "skills.name", "skills.layer", "skills.state", "skills.uses", "skills.done", "skills.failed",
            "skills.makes", "skills.when", "skills.until", "skills.steps", "skills.reason", "skills.prior",
            "skills.retired", "skills.live", "skills.problems", "skills.empty", "skills.forgotten",
            "empty.skills",
            "logs.level", "logs.filter", "logs.pause", "logs.resume", "logs.clear", "logs.follow",
            "logs.mod", "logs.all", "logs.count", "empty.logs",
            "clips.name", "clips.size", "clips.age", "clips.download", "clips.refresh", "empty.clips",
            "control.world", "control.name", "control.newworld", "control.newworld.confirm",
            "control.resume", "control.reset", "control.reset.confirm", "control.stop",
            "control.stop.confirm", "control.done", "control.failed", "control.busy", "control.hint",
            "status.inworld", "status.noworld");

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
        groups.put("craft", enums("craft", CraftChoice.values()));
        groups.put("resource", enums("resource", Resource.values()));
        groups.put("terrain", enums("terrain", Terrain.values()));
        groups.put("structure", enums("structure", Structure.values()));
        groups.put("planner", enums("planner", PlannerLog.Kind.values()));
        // The same kinds, in the mentor's words: it does not answer, it teaches.
        groups.put("mentor", words("mentor.", List.of("ASKED", "ANSWERED", "KEPT", "FAILED")));
        groups.put("spot", enums("spot", Spot.values()));
        groups.put("ground", enums("ground", Ground.values()));
        groups.put("swim", enums("swim", Swim.values()));
        groups.put("passage", enums("passage", Passage.values()));
        groups.put("tactic", enums("tactic", Tactic.values()));
        groups.put("way", enums("way", Way.values()));
        groups.put("tool", enums("tool", Tool.values()));
        // The named objectives the planner may hand back, plus the two states that are not one at all.
        Map<String, String> objectives = enums("objective", Rung.values());
        objectives.putAll(words("objective.", List.of("PLANNING", "UNPLANNED")));
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
