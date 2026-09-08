package io.github.ivannavas.autocraftai.mob.ai;

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
        String flags) {

    /**
     * @param source the source in play within the pursuit, as {@code Pursuit#source()} names it
     */
    public static Observation of(LocalPlayer player, ActionContext context, String source) {
        Sighting sighting = context.sighting();
        return new Observation(source, sighting.kind().name(),
                Perception.distanceTo(player, sighting), Perception.healthOf(player), context.flags());
    }

    /** Key into the table. Stable across runs, since it is what gets written to disk. */
    public String key() {
        return source + '|' + subject + '|' + distance.name() + '|' + health.name() + '|' + flags;
    }
}
