package io.github.ivannavas.autocraftai.mob.ai;

import java.util.Set;

import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import net.minecraft.core.BlockPos;

/**
 * What the three tables get to look at when deciding what is legal and how to build a goal.
 *
 * <p>The sighting was enough while every choice acted on something in view. Crafting does not: it acts on
 * what the body is carrying, and which recipes that pays for is an answer only the recipe book has.
 * Working it out once per decision and passing it here keeps the scan off every caller.
 *
 * @param sighting   the one thing the goals steer by
 * @param craftable  everything the inventory could pay for right now
 * @param wall       the solid block straight ahead at body height, or null if the way is clear
 * @param hasBlocks  the body is carrying something it could put down
 * @param canDigDown there is solid ground under the feet with more solid ground under that
 * @param tool       what to break the sighted block with, per the objective or per the game
 */
public record ActionContext(Sighting sighting, Set<Resource> craftable, BlockPos wall,
                            boolean hasBlocks, boolean canDigDown, Tool tool) {

    public ActionContext {
        craftable = Set.copyOf(craftable);
        tool = tool == null ? Tool.HAND : tool;
    }

    /**
     * The part of the surroundings that goes into the state key, as a short tag.
     *
     * <p>A literal map of every block in view cannot be a tabular key — no two ticks would ever share one
     * and nothing would ever be learned twice. What can is a handful of facts that change which move is
     * right, and these two are the pair that make building a way out of somewhere a decision the table can
     * even represent: it is walled in, and it has something to build with.
     */
    public boolean walled() {
        return wall != null;
    }

    /**
     * Deliberately not part of {@link #flags()}. Standing on ground that could be dug is very nearly always
     * true, so keying on it would double the number of states to distinguish almost nothing; what it is for
     * is the legality mask, which is where a fact that rarely varies belongs.
     */
    public boolean canDigDown() {
        return canDigDown;
    }

    public String flags() {
        if (walled() && hasBlocks) {
            return "WB";
        }
        if (walled()) {
            return "W";
        }
        return hasBlocks ? "B" : "-";
    }
}
