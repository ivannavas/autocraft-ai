package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Put something up: a workbench, a shelter, the frame of a nether portal.
 *
 * <p>Having the blocks and having built with them are different achievements, and until now the run could
 * only be asked for the first. A body carrying ten obsidian is not through to the Nether, and no amount of
 * gathering objectives can say what is left to do — which is what the fourth shape is for.
 *
 * <p>Completion is read off the world: enough of the right block, within reach of where the body stands.
 * Not the exact shape — see {@link Structure} for why — so what this really asks is "get the material and
 * put it down over there", which is the part the body has to learn.
 */
public record Build(Structure structure, String reason) implements Phase {

    public Build {
        reason = reason == null ? "" : reason.strip();
    }

    @Override
    public Map<Resource, Integer> needs() {
        return Map.of(structure.material(), structure.count());
    }

    /** Its material, so the plan does not charge for the very blocks this objective wants put down. */
    @Override
    public Optional<Resource> scores() {
        return Optional.of(structure.material());
    }

    @Override
    public String shape() {
        return "BUILD";
    }

    @Override
    public String name() {
        return "BUILD_" + structure.name();
    }

    @Override
    public boolean isComplete(StepContext context) {
        return standing(context.player()) >= structure.count();
    }

    /**
     * Paid for the material leaving the bag, which is the closest thing to "a block went down" that a
     * before-and-after pair of inventories can see.
     *
     * <p>It cannot tell a block placed from one spent at a crafting grid, and for these three materials
     * that costs nothing: nothing is made out of obsidian or cobblestone that the run can make, and a
     * crafting table that leaves the bag has been put down by definition.
     */
    @Override
    public double score(StepContext context) {
        Resource material = structure.material();
        int spent = Math.max(0, context.before().count(material) - context.after().count(material));
        return spent * material.worth();
    }

    /** What it is made of is what the eyes should be looking for while gathering it. */
    @Override
    public Optional<Predicate<BlockState>> wanted() {
        return structure.material().inWorld();
    }

    /** How much of the structure is already up, within its own radius of the body. */
    private int standing(LocalPlayer player) {
        if (player == null) {
            return 0;
        }
        Level level = player.level();
        BlockPos origin = player.blockPosition();
        int radius = structure.radius();
        int found = 0;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            if (level.isLoaded(pos) && structure.block().test(level.getBlockState(pos))) {
                found++;
                if (found >= structure.count()) {
                    return found;
                }
            }
        }
        return found;
    }

    /** One folder for building, the structure as the source: a portal frame and a workbench go up differently. */
    @Override
    public Pursuit pursuit(BlockState seen, int y, Bounds plan) {
        return new Pursuit(shape(), structure.name(),
                new Whereabouts(plan, List.of(), EnumSet.of(Way.WALK, Way.CLIMB)));
    }

    @Override
    public String toString() {
        return "build " + structure.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
