package io.github.ivannavas.autocraftai.mob.ai;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The blocks the body has put down itself, so that putting one down is not scored as losing it and taking
 * it back up is not scored as finding it.
 *
 * <p>A pair of inventory snapshots cannot tell a block placed from a block crafted away or dropped, and
 * the scoring used to treat all three the same: a dirt block stacked underfoot while the plan wanted dirt
 * was charged as a loss, and then charged again as a broken reserve. The block was a metre away. Worse,
 * the eyes then found it — the nearest block of the kind the plan was after — and the rule that mines what
 * it sees had the body dig its own pillar out from under itself, which paid the loss straight back. Put a
 * block down, pick it up, put it down: a loop with a reward at every turn of it.
 *
 * <h2>Placed is not lost, and reclaimed is not gained</h2>
 * So a placement is remembered, and the scoring adds it back: a block that went from the bag into the
 * world is still the body's, and the shopping list and the reserve count it as held. When the body later
 * breaks one of its own, the gain is taken off the same way. The round trip is worth exactly nothing, which
 * is the truth about it, and the seconds it took are charged as seconds always are.
 *
 * <p>And the eyes skip them. A resource the body has already got hold of and put somewhere is not a
 * resource to be found, so it is never the block in view and the rule never fires on it.
 *
 * <h2>Confirmed by the world, once a step</h2>
 * A click is a request; the server may say no. A placement is noted when it is asked for and only counts
 * once the block is actually there at the next step, so a refused click leaves no trace. From then on the
 * block is watched: the step it is no longer what was put there, it was broken — by the body, if the body
 * is beside it, which is the only case that means anything here — and it is forgotten either way.
 *
 * <p>Everything here happens on the client thread. It is a tally of the episode and goes with it.
 */
public final class Placed {

    private static final Placed INSTANCE = new Placed();

    /** How many placements to keep track of. A pillar and a bridge and a floor; not a house. */
    private static final int REMEMBER = 256;
    /** Within this of a block that vanished, the body is what broke it. */
    private static final double NEAR = 6.0;

    /** One block the body put down: what it is, and what resource it was. */
    private record Own(Block kind, Resource resource, long at) {
    }

    private record Pending(BlockPos pos, Own own) {
    }

    private final List<Pending> pending = new ArrayList<>();
    private final Map<BlockPos, Own> ours = new LinkedHashMap<>(64, 0.75F, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockPos, Own> eldest) {
            return size() > REMEMBER;
        }
    };
    private final Map<Resource, Integer> placed = new EnumMap<>(Resource.class);
    private final Map<Resource, Integer> reclaimed = new EnumMap<>(Resource.class);

    private Placed() {
    }

    public static Placed get() {
        return INSTANCE;
    }

    /**
     * Notes that the body has just asked to put this down here. Counts once the world shows it there.
     *
     * @param pos   where the block will be
     * @param stack what was in the hand, for what block and what resource it is
     */
    public void mark(BlockPos pos, ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            return;
        }
        // Building material and the crafting table, which is placed to be used. A valuable that somehow
        // ends up in the ground is not remembered: it should read as a loss, and be found again.
        Resource resource = Resource.of(stack).orElse(null);
        // Building material, and the two blocks placed to be used and returned to: the table and the
        // furnace. A valuable that somehow ends up in the ground is not remembered, so it reads as a loss.
        if (resource != null && !resource.buildable()
                && resource != Resource.CRAFTING_TABLE && resource != Resource.FURNACE) {
            return;
        }
        pending.add(new Pending(pos.immutable(), new Own(item.getBlock(), resource, 0L)));
    }

    /**
     * The nearest block of this kind the body put down and that is still standing, or null. What lets a
     * craft find its way back to a table it set up earlier and then mined thirty blocks away from — the
     * planner used to paper over that by asking for a fresh table, and with no planner the ladder has no
     * such recourse of its own.
     */
    public BlockPos nearestOwn(Level level, Vec3 from, Block kind) {
        BlockPos best = null;
        double closest = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, Own> entry : ours.entrySet()) {
            if (entry.getValue().kind() != kind) {
                continue;
            }
            BlockPos pos = entry.getKey();
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(kind)) {
                continue;
            }
            double d = pos.distToCenterSqr(from);
            if (d < closest) {
                closest = d;
                best = pos;
            }
        }
        return best;
    }

    /**
     * How many of the body's own blocks of this kind stand in a box — {@code radius} out on the flat,
     * from {@code yFrom} to {@code yTo} — and were put down since a moment. What lets a build objective
     * pay for the blocks that went into the walls and not for the ones that went under the feet.
     */
    public int ownWithin(Level level, BlockPos origin, int radius, int yFrom, int yTo,
                         Predicate<BlockState> block, long since) {
        int found = 0;
        for (Map.Entry<BlockPos, Own> entry : ours.entrySet()) {
            Own own = entry.getValue();
            BlockPos pos = entry.getKey();
            if (own.at() < since
                    || pos.getY() < yFrom || pos.getY() > yTo
                    || Math.abs(pos.getX() - origin.getX()) > radius
                    || Math.abs(pos.getZ() - origin.getZ()) > radius
                    || !level.isLoaded(pos) || !block.test(level.getBlockState(pos))) {
                continue;
            }
            found++;
        }
        return found;
    }

    /** Whether this is one of the body's own blocks, still standing where it was put. */
    public boolean isOurs(Level level, BlockPos pos) {
        Own own = ours.get(pos);
        return own != null && level.isLoaded(pos) && level.getBlockState(pos).is(own.kind());
    }

    /**
     * Confirms the placements the world has honoured and notices the ones that are gone. Called once a
     * step, from the game thread, before the step's gains are counted.
     */
    public void sweep(LocalPlayer player) {
        Level level = player.level();
        long now = System.currentTimeMillis();
        for (Pending asked : pending) {
            if (level.isLoaded(asked.pos()) && level.getBlockState(asked.pos()).is(asked.own().kind())) {
                ours.put(asked.pos(), new Own(asked.own().kind(), asked.own().resource(), now));
                if (asked.own().resource() != null) {
                    placed.merge(asked.own().resource(), 1, Integer::sum);
                }
            }
        }
        pending.clear();

        ours.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            if (!level.isLoaded(pos)) {
                return false;
            }
            if (level.getBlockState(pos).is(entry.getValue().kind())) {
                return false;
            }
            Resource resource = entry.getValue().resource();
            if (resource != null && pos.distToCenterSqr(player.position()) <= NEAR * NEAR) {
                reclaimed.merge(resource, 1, Integer::sum);
            }
            return true;
        });
    }

    /** What has been put down since this was last asked, by resource. */
    public Map<Resource, Integer> drainPlaced() {
        return drain(placed);
    }

    /** What of the body's own has been taken back up since this was last asked, by resource. */
    public Map<Resource, Integer> drainReclaimed() {
        return drain(reclaimed);
    }

    private static Map<Resource, Integer> drain(Map<Resource, Integer> tally) {
        Map<Resource, Integer> out = new EnumMap<>(tally);
        tally.clear();
        return out;
    }

    /** Forgets everything, for an episode that has ended. */
    public void clear() {
        pending.clear();
        ours.clear();
        placed.clear();
        reclaimed.clear();
    }
}
