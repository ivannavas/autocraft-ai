package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import io.github.ivannavas.autocraftai.mob.ai.Placed;
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

    /** Blocks of it up, and failing that blocks of its material in the bag: both are nearer than none. */
    @Override
    public double progress(StepContext context) {
        return standing(context.player())
                + Math.min(structure.count(), context.after().count(structure.material())) / 100.0;
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
        if (spent == 0 || context.player() == null) {
            return 0.0;
        }
        // Paid per block that went into the structure, not per block that left the bag. Spent was the
        // whole measure once, and a body on BUILD_SHELTER learned that the cheapest way to spend stone
        // was under its own feet: twenty-three placements in four minutes, a pillar sixteen high over
        // a forest, and not a wall to show for it. What joined the walls since the step began — the
        // registry knows when each block of its own went down — is what counts.
        long since = System.currentTimeMillis() - Math.max(1, context.steps()) * 1000L - JOINED_MARGIN_MILLIS;
        BlockPos feet = context.player().blockPosition();
        int joined = Placed.get().ownWithin(context.player().level(), feet, structure.radius(),
                feet.getY(), feet.getY() + WALL_HEIGHT, structure.block(), since);
        return Math.min(spent, joined) * material.worth();
    }

    /** How far above the feet a block still counts as the structure's: walls and a roof, not a tower. */
    private static final int WALL_HEIGHT = 2;
    /** Slack on the step's clock, so a block placed on the step's first tick is not missed. */
    private static final long JOINED_MARGIN_MILLIS = 1_500L;

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
        // Around and over the body, never under it: a shelter is walls at the height of the body and
        // something over its head. Counted a radius down as well, the pillar it stood on was a quarter
        // of a shelter.
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, 0, -radius),
                origin.offset(radius, WALL_HEIGHT, radius))) {
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
