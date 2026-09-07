package io.github.ivannavas.autocraftai.mob.ai;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Where a swing or a placed block goes, relative to what the body is looking at or to the body itself.
 *
 * <p>Mining and building used to happen in exactly one place each: the block the eyes had found, and the
 * ground under the feet. That is enough to chop a tree and no use for anything else. A staircase is mining
 * the block <em>above</em> the one in front of you; climbing out of a hole is putting a block <em>under</em>
 * yourself; getting past a gap is putting one where your next step would be. Same two verbs, different
 * place, and the difference is the whole move.
 *
 * <h2>Both kinds of relative, on purpose</h2>
 * Three of these are relative to the block in view and four to the body, and mixing them in one list is not
 * an oversight — it is what the choice actually looks like. "The one above that" and "the one under me" are
 * both answers to the same question, and a table that could only express one of them could not learn to cut
 * a staircase or to pillar out of a hole.
 *
 * <p>Which of them make sense at any moment is not learned but checked: you cannot mine air and you cannot
 * place a block inside a wall. What is learned is which of the ones that <em>are</em> possible is worth
 * doing, and that is what {@link Placement} keys and the placement table holds.
 */
public enum Spot {

    /** The block in view. Mining it is what the body has always done. */
    AT_SIGHT,

    /** One above the block in view: mine it and the wall becomes a step. */
    ABOVE_SIGHT,

    /** One below the block in view: mine it and the way down opens; fill it and a gap closes. */
    BELOW_SIGHT,

    /** The block under the feet. Mining it is a shaft straight down. */
    UNDER_FOOT,

    /** Where the body is standing. Nothing to mine there; a block placed there lifts the body a step. */
    AT_FEET,

    /** The block over the head: mine it to climb, or put one there for a roof. */
    OVERHEAD,

    /** The block a step in front at foot height: mine it to walk through, place it to bridge a gap. */
    AHEAD;

    /**
     * Where this actually is, or {@code null} when the run has nothing to measure it from.
     *
     * @param sighted the block in view, or null when the eyes are on nothing or on a creature
     */
    public BlockPos resolve(LocalPlayer player, BlockPos sighted) {
        // The body is only asked about for the spots that are relative to it, so the three that are
        // relative to what is in view answer honestly when there is nothing in view and no body either.
        return switch (this) {
            case AT_SIGHT -> sighted;
            case ABOVE_SIGHT -> sighted == null ? null : sighted.above();
            case BELOW_SIGHT -> sighted == null ? null : sighted.below();
            case UNDER_FOOT -> player.blockPosition().below();
            case AT_FEET -> player.blockPosition();
            case OVERHEAD -> player.blockPosition().above(2);
            case AHEAD -> ahead(player);
        };
    }

    /** One block along the way the body is facing, at the height its feet are. */
    private static BlockPos ahead(LocalPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 front = player.position().add(look.x, 0.0, look.z);
        return BlockPos.containing(front.x, player.position().y, front.z);
    }

    /** Whether there is something here worth swinging at. */
    public boolean canMine(LocalPlayer player, BlockPos sighted) {
        BlockPos pos = resolve(player, sighted);
        if (pos == null) {
            return false;
        }
        Level level = player.level();
        // Never the block the body is standing in: that is the air it occupies, and there is nothing there.
        return !pos.equals(player.blockPosition())
                && level.isLoaded(pos)
                && !level.getBlockState(pos).isAir()
                && level.getFluidState(pos).isEmpty();
    }

    /**
     * Whether a block could go here.
     *
     * <p>Air, and something solid next to it to place against — the game will not accept a block hung in
     * the middle of nothing. {@link #AT_FEET} is the exception it looks like and is not: the support is
     * underneath rather than beside, which is what pillaring is.
     */
    public boolean canPlace(LocalPlayer player, BlockPos sighted) {
        BlockPos pos = resolve(player, sighted);
        if (pos == null) {
            return false;
        }
        Level level = player.level();
        if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) {
            return false;
        }
        if (this == AT_FEET) {
            return player.onGround() || solid(level, pos.below());
        }
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            if (solid(level, pos.relative(side))) {
                return true;
            }
        }
        return false;
    }

    private static boolean solid(Level level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).isSolid();
    }
}
