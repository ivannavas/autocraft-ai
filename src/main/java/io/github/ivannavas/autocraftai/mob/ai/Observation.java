package io.github.ivannavas.autocraftai.mob.ai;

import net.minecraft.client.player.LocalPlayer;

/**
 * The discrete state the Q-table is keyed by.
 *
 * <p>Deliberately tiny: seven kinds of sighting, four distance bands and three health bands, times the rung
 * of the ladder. A table that size fills up in a few minutes of play, which is the difference between a
 * brain that visibly learns something and one that is still all zeroes when you get bored and quit.
 *
 * <p>The rung is part of the state and not just part of the reward. The same tree in the same place is
 * worth chopping while the run is after wood and worth walking past while it is after stone, and a policy
 * that could not tell those apart would have to average them into something that is wrong for both.
 */
public record Observation(
        String phase,
        FocusKind focus,
        Perception.Distance distance,
        Perception.Health health) {

    public static Observation of(LocalPlayer player, Sighting sighting, String phase) {
        return new Observation(
                phase,
                sighting.kind(),
                Perception.distanceTo(player, sighting),
                Perception.healthOf(player));
    }

    /** Key into the table. Stable across runs, since it is what gets written to disk. */
    public String key() {
        return phase + '|' + focus.name() + '|' + distance.name() + '|' + health.name();
    }
}
