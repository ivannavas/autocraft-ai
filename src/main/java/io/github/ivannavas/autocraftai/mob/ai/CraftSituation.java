package io.github.ivannavas.autocraftai.mob.ai;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.ivannavas.autocraftai.mob.ai.objective.InventoryCensus;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;

/**
 * The state the crafting table is keyed by, which is not the one the other tables use.
 *
 * <p>Sharing the goal table's key was a mistake with a clear symptom: that key describes what the body can
 * <em>see</em> — a block, how far off, how much health is left — and none of it has any bearing on what is
 * worth making. The table was busy learning things like "when a block is close and health is high, make
 * planks", which is not a rule about crafting at all.
 *
 * <h2>What is needed, not which objective</h2>
 * The objective's name was the second mistake, and a subtler one. It worked while the objectives were seven
 * fixed rungs; the moment a planner started inventing them, every state became a one-off. {@code GET_3_LOG}
 * and {@code GET_5_LOG} are different states about the same problem, and {@code GET_11_PLANKS} tomorrow is
 * a state this run has never seen. A table whose every row is visited once learns nothing.
 *
 * <p>So the key is what the plan is short of, which is the thing that actually decides a craft. Needing
 * planks and holding logs is the same situation on the first objective and the fiftieth, and it has the
 * same answer both times — which is what makes it worth writing down.
 *
 * <h2>The whole bag, filtered by relevance</h2>
 * And what is held is read off the whole inventory rather than the handful of things that happen to be
 * craftable. It is filtered, though: a resource that could not contribute to anything the plan needs does
 * not change what is worth making, and putting it in the key would split every state in two for nothing.
 *
 * <p>Presence, not amount. A run short of planks makes planks whether it has one or four, and the need
 * being met is what ends that; buckets per resource would multiply the states for a distinction the policy
 * would rarely act on.
 */
public final class CraftSituation {

    private static final String NOTHING = "-";

    private CraftSituation() {
    }

    /**
     * Key for the crafting table: what the plan is still short of, then what is held towards it.
     *
     * @param needs what the plan says it needs, and how much of each
     * @param held  the whole inventory as it stands
     */
    public static String key(Map<Resource, Integer> needs, InventoryCensus held) {
        String wanted = Arrays.stream(Resource.values())
                .filter(resource -> held.count(resource) < needs.getOrDefault(resource, 0))
                .map(Enum::name)
                .collect(Collectors.joining("+"));
        String carrying = Arrays.stream(Resource.values())
                .filter(resource -> held.count(resource) > 0)
                .filter(resource -> matters(resource, needs))
                .map(Enum::name)
                .collect(Collectors.joining("+"));
        return (wanted.isEmpty() ? NOTHING : wanted) + '|' + (carrying.isEmpty() ? NOTHING : carrying);
    }

    /** Whether holding this could bear on anything the plan is after, directly or as an ingredient. */
    private static boolean matters(Resource held, Map<Resource, Integer> needs) {
        return needs.keySet().stream().anyMatch(held::contributesTo);
    }
}
