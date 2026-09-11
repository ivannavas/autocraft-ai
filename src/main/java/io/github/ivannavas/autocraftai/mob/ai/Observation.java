package io.github.ivannavas.autocraftai.mob.ai;

import java.util.OptionalInt;

import io.github.ivannavas.autocraftai.mob.ai.objective.Pursuit;
import net.minecraft.client.player.LocalPlayer;

/**
 * The discrete state the per-pursuit tables are keyed by: five fields, always in the same order, so a
 * saved table is readable and the overlay can render a row without knowing which kind of state it is
 * looking at.
 *
 * <p>The first field used to be the objective — the rung, or {@code THREAT} for anything hostile. Neither
 * belongs in a key any more. Which objective the body is on decides which <em>folder</em> of tables it is
 * learning in (see {@link io.github.ivannavas.autocraftai.mob.ai.objective.Pursuit}), and a threat is a
 * folder of its own; what the first field carries now is the source within that folder — which tree, which
 * ore, which mob — so that two things learned in the same place can still be told apart where it matters.
 *
 * <p>The fifth field is the rest of what is around, boiled down to the handful of facts that change which
 * move is right — see {@link ActionContext#flags()}.
 */
public record Observation(
        String source,
        String subject,
        Perception.Distance distance,
        Perception.Health health,
        String flags,
        String setting) {

    /**
     * @param source the source in play within the pursuit, as {@code Pursuit#source()} names it
     */
    public static Observation of(LocalPlayer player, ActionContext context, String source) {
        return of(player, context, source, null, OptionalInt.empty(), false);
    }

    /**
     * The same reading, with where the body is added for an objective that has no block to look for.
     *
     * <p>Food, a height, a cave, a shelter: for those the source is {@link Pursuit#NO_SOURCE} and the
     * five words above say nothing that decides them — "a block close, mid health, carrying blocks,
     * hungry" — so sixty per cent of a day's decisions fell into five rows that could not tell the
     * surface from a hole, or the band above from the band below. Two more words for those rows only:
     * the cover overhead and where the body stands against the plan's band; and a flag for a berry
     * bush within reach.
     * Objectives with a source keep their rows exactly as they were.
     *
     * @param cover    what is overhead, or null when nothing has been read yet
     * @param height   the height the plan wants, when it wants one
     * @param bushNear whether a sweet berry bush stands within a few blocks
     */
    public static Observation of(LocalPlayer player, ActionContext context, String source,
                                 Surroundings.Cover cover, OptionalInt height, boolean bushNear) {
        Sighting sighting = context.sighting();
        String flags = context.flags();
        String setting = "";
        if (Pursuit.NO_SOURCE.equals(source)) {
            int y = player.getBlockY();
            String band = height.isEmpty() ? "IN"
                    : height.getAsInt() - y > 1 ? "BELOW"
                    : y - height.getAsInt() > 1 ? "ABOVE" : "IN";
            setting = (cover == null ? "NONE" : cover.name()) + '|' + band;
            if (bushNear) {
                flags = "-".equals(flags) ? "S" : flags + "S";
            }
        }
        return new Observation(source, sighting.kind().name(),
                Perception.distanceTo(player, sighting), Perception.healthOf(player), flags, setting);
    }

    /** Key into the table. Stable across runs, since it is what gets written to disk. */
    public String key() {
        return source + '|' + subject + '|' + distance.name() + '|' + health.name() + '|' + flags
                + (setting.isEmpty() ? "" : "|" + setting);
    }
}
