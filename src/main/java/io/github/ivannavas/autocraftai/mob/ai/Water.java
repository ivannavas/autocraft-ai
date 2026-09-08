package io.github.ivannavas.autocraftai.mob.ai;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The body's relationship with the water it is in: how deep, how much breath is left, and the two ways out.
 *
 * <p>Nothing in the run represented water at all, and the deaths said so. The state the goal table is keyed
 * by — what is in view, how far, how hurt, three flags — reads exactly the same standing in a field and
 * treading water at the bottom of a flooded ravine, so the same move was chosen in both, and one of those
 * two situations kills you in about twenty seconds whatever the move was. Drowning is not a bad move; it is
 * a move made without noticing the water.
 *
 * <h2>Why a fact and a place, together</h2>
 * The words are the state a table can be keyed by. The two block positions are what a goal needs to act on
 * that state, and they are found here rather than in the goal for the same reason the tool for a block is:
 * scanning the surroundings is a job for whatever is already looking, and a goal should be handed a
 * decision instead of a search. They come out of one pass, so the word and the place can never disagree.
 *
 * @param depth   how much of the body is in it
 * @param air     how much breath is left
 * @param breath  how far off the nearest place the head could be in air is
 * @param shore   how far off the nearest place the body could stand out of the water is
 * @param breathAt where that air is, or null when there is none within reach
 * @param shoreAt  where that ground is, or null when there is none within reach
 */
public record Water(Depth depth, Air air, Reach breath, Reach shore,
                    BlockPos breathAt, BlockPos shoreAt) {

    /** How much of the body is in the water, which is what decides whether it can breathe at all. */
    public enum Depth {
        /** Not in it. Nothing here applies and the table is not asked. */
        DRY,
        /** Feet wet, head in air: slow going, and not dangerous. */
        WADING,
        /** Off the bottom and floating, head still out. */
        SWIMMING,
        /** Eyes under. The only one of the four with a clock running on it. */
        SUBMERGED
    }

    /** Breath left, in three words, because the exact tick count is not something a table can key on. */
    public enum Air {
        FULL,
        LOW,
        /** Out of it: from here the body takes damage every second until it surfaces or dies. */
        EMPTY
    }

    /** How far off a way out is. {@link #ABOVE} is straight up, which is the cheapest way there is. */
    public enum Reach {
        ABOVE,
        NEAR,
        FAR,
        NONE
    }

    /** Below this fraction of a full lungful, the breath counts as running out. */
    private static final double LOW_AIR = 0.5;
    /** How far out to look for air or for land. Beyond this the body cannot be steered there anyway. */
    private static final int SCAN = 8;
    /** How far up to look for air. A body under a ledge has to find the surface past it. */
    private static final int SCAN_UP = 8;
    /** How far down to look for land, since a shore can be below as well as beside. */
    private static final int SCAN_DOWN = 4;
    /** Within this, a way out is near enough to be worth a straight line at it. */
    private static final double NEAR_ENOUGH = 4.0;

    private static final Water DRY_LAND =
            new Water(Depth.DRY, Air.FULL, Reach.NONE, Reach.NONE, null, null);

    /** The body is in water, so everything else here means something. */
    public boolean present() {
        return depth != Depth.DRY;
    }

    /** Nothing to say, for a body that is not in the water and for one there is no body for. */
    public static Water dry() {
        return DRY_LAND;
    }

    /**
     * Reads the water around the body. Must be called on the client thread.
     *
     * <p>The scan is skipped entirely on dry land, which is nearly every decision of nearly every run: the
     * cost of knowing about water is paid only while there is water to know about.
     */
    public static Water around(LocalPlayer player) {
        if (player == null || !player.isInWater()) {
            return DRY_LAND;
        }
        Depth depth = player.isUnderWater() ? Depth.SUBMERGED
                : player.onGround() ? Depth.WADING : Depth.SWIMMING;

        int left = player.getAirSupply();
        int most = Math.max(1, player.getMaxAirSupply());
        Air air = left <= 0 ? Air.EMPTY : left < most * LOW_AIR ? Air.LOW : Air.FULL;

        BlockPos breathAt = nearestBreath(player);
        BlockPos shoreAt = nearestShore(player);
        return new Water(depth, air,
                reachOf(player, breathAt, true), reachOf(player, shoreAt, false),
                breathAt, shoreAt);
    }

    /** The state key, in a fixed order so a table written today still reads tomorrow. */
    public String key() {
        return depth.name() + '|' + air.name() + '|' + breath.name() + '|' + shore.name();
    }

    /**
     * The nearest place the body's head could be in open air.
     *
     * <p>Straight up first, and not only because it is closest: a body under a ledge that swims at the
     * nearest air it can see sideways is a body that has found the way out from under the ledge, and one
     * that treats "up" as the only answer drowns against the underside of it. So the column is preferred
     * and the rest of the box is the fallback rather than being ignored.
     */
    private static BlockPos nearestBreath(LocalPlayer player) {
        BlockPos head = BlockPos.containing(player.getEyePosition());
        for (int up = 0; up <= SCAN_UP; up++) {
            BlockPos above = head.above(up);
            if (breathable(player, above)) {
                return above;
            }
        }
        return nearest(player, head, SCAN, SCAN_UP, SCAN_DOWN, Water::breathable);
    }

    /** The nearest place the body could stand with its feet dry, which is the way out rather than up. */
    private static BlockPos nearestShore(LocalPlayer player) {
        return nearest(player, player.blockPosition(), SCAN, SCAN_UP, SCAN_DOWN, Water::standable);
    }

    /** The closest position in a box around the body that the test likes, or null when none does. */
    private static BlockPos nearest(LocalPlayer player, BlockPos from, int out, int up, int down,
                                    Test test) {
        BlockPos best = null;
        double closest = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(
                from.offset(-out, -down, -out), from.offset(out, up, out))) {
            double distance = candidate.distToCenterSqr(player.position());
            if (distance >= closest || !test.likes(player, candidate)) {
                continue;
            }
            closest = distance;
            best = candidate.immutable();
        }
        return best;
    }

    /** Whether a head in this block would be in air rather than in water or inside something solid. */
    private static boolean breathable(LocalPlayer player, BlockPos pos) {
        Level level = player.level();
        return level.isLoaded(pos)
                && level.getFluidState(pos).isEmpty()
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Whether the body could stand here, out of the water, with room for all of it. */
    private static boolean standable(LocalPlayer player, BlockPos pos) {
        Level level = player.level();
        if (!level.isLoaded(pos) || !level.getFluidState(pos).isEmpty()) {
            return false;
        }
        BlockPos floor = pos.below();
        if (!level.isLoaded(floor) || !level.getBlockState(floor).entityCanStandOn(level, floor, player)) {
            return false;
        }
        Player body = player;
        Vec3 spot = Vec3.atBottomCenterOf(pos);
        return level.noCollision(body, body.getDimensions(Pose.STANDING).makeBoundingBox(spot));
    }

    /** How far off a place is, in the three words the table is keyed by. */
    private static Reach reachOf(LocalPlayer player, BlockPos found, boolean mayBeAbove) {
        if (found == null) {
            return Reach.NONE;
        }
        BlockPos here = player.blockPosition();
        if (mayBeAbove && found.getX() == here.getX() && found.getZ() == here.getZ()) {
            return Reach.ABOVE;
        }
        return found.distToCenterSqr(player.position()) <= NEAR_ENOUGH * NEAR_ENOUGH
                ? Reach.NEAR : Reach.FAR;
    }

    /** What makes a position worth swimming to, so the two scans can share one pass over the box. */
    private interface Test {
        boolean likes(LocalPlayer player, BlockPos pos);
    }
}
