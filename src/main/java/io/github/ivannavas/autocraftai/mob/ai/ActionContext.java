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
 * @param hungry     the body is hungry enough for it to be worth deciding about
 * @param canEat     there is a mouthful in the hotbar and room for it
 * @param wellFed    the larder is full enough that another animal is not worth killing
 */
public record ActionContext(Sighting sighting, Set<Resource> craftable, BlockPos wall,
                            boolean hasBlocks, boolean canDigDown, Tool tool,
                            boolean hungry, boolean canEat, boolean wellFed) {

    public ActionContext {
        craftable = Set.copyOf(craftable);
        tool = tool == null ? Tool.HAND : tool;
    }

    /**
     * The part of the surroundings that goes into the state key, as a short tag.
     *
     * <p>A literal map of every block in view cannot be a tabular key — no two ticks would ever share one
     * and nothing would ever be learned twice. What can is a handful of facts that change which move is
     * right: it is walled in, it has something to build with, it is hungry. The first two are what make
     * building a way out a decision the table can represent at all; the third is what lets it learn that a
     * cow is worth chasing on an empty stomach and not worth it on a full one.
     *
     * <p>Letters in a fixed order, so a state written before hunger existed still reads the same today.
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

    /** Whether eating is a move the body could make right now. Legality only, like {@link #canDigDown()}. */
    public boolean canEat() {
        return canEat;
    }

    /**
     * Whether there is enough food in the bag that hunting is a waste of the time it takes.
     *
     * <p>A rule rather than something the table is left to learn, and deliberately. The reward for killing
     * a cow you do not need is the same as for killing one you do — a resource gain — so nothing in the
     * numbers would ever tell the difference, and the body would spend a well-stocked run chasing animals.
     */
    public boolean wellFed() {
        return wellFed;
    }

    public String flags() {
        StringBuilder tags = new StringBuilder();
        if (walled()) {
            tags.append('W');
        }
        if (hasBlocks) {
            tags.append('B');
        }
        if (hungry) {
            tags.append('H');
        }
        return tags.isEmpty() ? "-" : tags.toString();
    }
}
