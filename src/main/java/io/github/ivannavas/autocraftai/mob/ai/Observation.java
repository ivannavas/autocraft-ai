package io.github.ivannavas.autocraftai.mob.ai;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EntityType;

/**
 * The discrete state the Q-tables are keyed by: five fields, always in the same order, so a saved table is
 * readable and the overlay can render a row without knowing which kind of state it is looking at.
 *
 * <p>The fifth field is the rest of what is around, boiled down to the handful of facts that change which
 * move is right — see {@link ActionContext#flags()}. Two shapes share the first four, because a threat and
 * an errand are not the same kind of problem.
 *
 * <h2>Errands carry the rung; threats carry the mob</h2>
 * For anything to do with the objective the context is the rung being climbed, since the same tree is worth
 * chopping while the run is after wood and worth walking past while it is after stone.
 *
 * <p>A creeper is a creeper on every rung. Keying threats by the rung would split what the body learns
 * about one mob across six unrelated states and make it learn the same lesson six times over — so threats
 * drop the rung and spend that field on the thing that actually changes the answer: which mob it is. What
 * to do about a skeleton at a distance is not what to do about a creeper at the same distance, and with a
 * single lumped HOSTILE the policy could only ever learn the average of the two.
 */
public record Observation(
        String context,
        String subject,
        Perception.Distance distance,
        Perception.Health health,
        String flags) {

    /** Context used in place of the rung for anything hostile. */
    private static final String THREAT = "THREAT";

    public static Observation of(LocalPlayer player, ActionContext context, String phase) {
        Sighting sighting = context.sighting();
        Perception.Distance distance = Perception.distanceTo(player, sighting);
        Perception.Health health = Perception.healthOf(player);
        String flags = context.flags();

        if (sighting.kind() == FocusKind.HOSTILE && sighting.entity() != null) {
            return new Observation(THREAT, mobName(sighting), distance, health, flags);
        }
        return new Observation(phase, sighting.kind().name(), distance, health, flags);
    }

    /** The mob's registry name — "zombie", "creeper" — which is what tells two threats apart. */
    private static String mobName(Sighting sighting) {
        return EntityType.getKey(sighting.entity().getType()).getPath();
    }

    /** Key into the table. Stable across runs, since it is what gets written to disk. */
    public String key() {
        return context + '|' + subject + '|' + distance.name() + '|' + health.name() + '|' + flags;
    }
}
