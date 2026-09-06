package io.github.ivannavas.autocraftai.mob.ai;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.ivannavas.autocraftai.mob.ai.objective.InventoryCensus;

/**
 * The state the crafting table is keyed by, which is not the one the other tables use.
 *
 * <p>Sharing the goal table's key was a mistake with a clear symptom: that key describes what the body can
 * <em>see</em> — a block, how far off, how much health is left — and none of it has any bearing on what is
 * worth making. The table was busy learning things like "when a block is close and health is high, make
 * planks", which is not a rule about crafting at all.
 *
 * <p>What decides a craft is the two things this key carries: the rung the run is trying to reach, and what
 * is already in the bag. Whether a recipe can be afforded stays out of it — that is the legality mask's
 * job, and putting it here as well would split every state in two for nothing.
 *
 * <p>Holdings are recorded as presence, not amount. Quantity mostly does not change the answer: a run short
 * of planks makes planks whether it has one or four, and the rung ticking over is what ends that. Buckets
 * per resource would multiply the states for a distinction the policy would rarely act on.
 */
public final class CraftSituation {

    private static final String NOTHING = "-";

    private CraftSituation() {
    }

    /** Key for the crafting table: the rung being climbed, then what is held towards it. */
    public static String key(String rung, InventoryCensus held) {
        String carrying = Stream.of(CraftChoice.values())
                .filter(CraftChoice::makesSomething)
                .filter(choice -> held.count(choice.resource()) > 0)
                .map(Enum::name)
                .collect(Collectors.joining("+"));
        return rung + '|' + (carrying.isEmpty() ? NOTHING : carrying);
    }
}
