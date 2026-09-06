package io.github.ivannavas.autocraftai.mob.ai;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Works out what the body should be attending to.
 *
 * <p>Only one thing is reported per decision, because the Q-table is keyed on one thing. The order below is
 * the whole of the judgement: a threat close enough to hit you outranks anything you were doing, free drops
 * outrank going to fetch more, and what the current rung is after outranks scenery.
 *
 * <ol>
 *   <li>a hostile within {@link #THREAT_RANGE}</li>
 *   <li>a dropped item within {@link #PICKUP_SCAN_RANGE}</li>
 *   <li>the nearest block the current rung wants</li>
 *   <li>the nearest living thing in the view cone</li>
 *   <li>whatever is under the crosshair</li>
 * </ol>
 *
 * <p>Living things have to be in the view cone and in line of sight — that part is genuinely seeing.
 * Drops and wanted blocks are found by a scan of the surroundings regardless of facing, which is a
 * deliberate cheat: the body's head is driven by its goals, so anything that had to be looked at first
 * could never be found in order to look at it.
 */
public final class Perception {

    /** How far out living things are noticed. */
    private static final double SIGHT_RANGE = 20.0;
    /** Half angle of the view cone, as the cosine the dot product is tested against (about 60 degrees). */
    private static final double VIEW_CONE_COS = 0.5;
    /** Inside this, a hostile is the only thing that matters. */
    private static final double THREAT_RANGE = 8.0;
    /** Drops are worth a detour from this far away. */
    private static final double PICKUP_SCAN_RANGE = 12.0;
    /** Box half-extents for the wanted-block scan. */
    private static final int BLOCK_SCAN_HORIZONTAL = 8;
    private static final int BLOCK_SCAN_VERTICAL = 4;

    public Sighting look(Minecraft client, LocalPlayer player, Optional<TagKey<Block>> wanted) {
        Sighting threat = nearestThreat(player);
        if (threat.isPresent()) {
            return threat;
        }
        Sighting drop = nearestDrop(player);
        if (drop.isPresent()) {
            return drop;
        }
        Sighting resource = nearestWantedBlock(player, wanted);
        if (resource.isPresent()) {
            return resource;
        }
        Sighting living = nearestVisibleLiving(player);
        if (living.isPresent()) {
            return living;
        }
        return blockUnderCrosshair(client);
    }

    private Sighting nearestThreat(LocalPlayer player) {
        return nearest(player, THREAT_RANGE,
                candidate -> candidate instanceof LivingEntity living
                        && living.isAlive()
                        && candidate instanceof Enemy)
                .map(found -> Sighting.of(FocusKind.HOSTILE, found))
                .orElseGet(Sighting::nothing);
    }

    private Sighting nearestDrop(LocalPlayer player) {
        return nearest(player, PICKUP_SCAN_RANGE,
                candidate -> candidate instanceof ItemEntity item && !item.getItem().isEmpty())
                .map(found -> Sighting.of(FocusKind.ITEM, found))
                .orElseGet(Sighting::nothing);
    }

    private Sighting nearestVisibleLiving(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        return nearest(player, SIGHT_RANGE,
                candidate -> candidate instanceof LivingEntity living
                        && living.isAlive()
                        && inViewCone(eye, look, living)
                        && player.hasLineOfSight(living))
                .map(found -> Sighting.of(classify(found), found))
                .orElseGet(Sighting::nothing);
    }

    private Optional<Entity> nearest(LocalPlayer player, double range,
                                     java.util.function.Predicate<Entity> test) {
        List<Entity> candidates =
                player.level().getEntities(player, player.getBoundingBox().inflate(range), test);
        return candidates.stream().min(Comparator.comparingDouble(player::distanceToSqr));
    }

    private boolean inViewCone(Vec3 eye, Vec3 look, Entity candidate) {
        Vec3 toCandidate = candidate.getEyePosition().subtract(eye);
        double distance = toCandidate.length();
        if (distance < 1.0E-4) {
            return true;
        }
        return toCandidate.scale(1.0 / distance).dot(look) >= VIEW_CONE_COS;
    }

    /**
     * Nearest block of the wanted kind in a box around the body. Walks the box rather than raycasting: the
     * point is to find the tree, not to check whether the body happens to be facing it.
     */
    private Sighting nearestWantedBlock(LocalPlayer player, Optional<TagKey<Block>> wanted) {
        if (wanted.isEmpty()) {
            return Sighting.nothing();
        }
        TagKey<Block> tag = wanted.get();
        Level level = player.level();
        BlockPos origin = player.blockPosition();
        Vec3 from = player.position();

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-BLOCK_SCAN_HORIZONTAL, -BLOCK_SCAN_VERTICAL, -BLOCK_SCAN_HORIZONTAL),
                origin.offset(BLOCK_SCAN_HORIZONTAL, BLOCK_SCAN_VERTICAL, BLOCK_SCAN_HORIZONTAL))) {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(tag)) {
                continue;
            }
            double distance = Vec3.atCenterOf(pos).distanceToSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }

        return best == null ? Sighting.nothing() : Sighting.ofBlock(FocusKind.RESOURCE, best);
    }

    private Sighting blockUnderCrosshair(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return Sighting.nothing();
        }
        // The block itself, not the point the ray struck: the identity of the sighting has to stay put
        // while the body keeps looking at the same block.
        return Sighting.ofBlock(FocusKind.BLOCK, ((BlockHitResult) hit).getBlockPos());
    }

    private FocusKind classify(Entity entity) {
        if (entity instanceof Player) {
            return FocusKind.PLAYER;
        }
        return entity instanceof Enemy ? FocusKind.HOSTILE : FocusKind.PASSIVE;
    }

    /** Distance band of a sighting, or {@link Distance#NONE} when there is nothing to measure. */
    public static Distance distanceTo(LocalPlayer player, Sighting sighting) {
        if (!sighting.isPresent()) {
            return Distance.NONE;
        }
        double distance = player.position().distanceTo(sighting.position());
        if (distance <= 4.0) {
            return Distance.CLOSE;
        }
        return distance <= 10.0 ? Distance.NEAR : Distance.FAR;
    }


    /**
     * The solid block straight ahead at body height — a wall — or {@code null} if the way is clear.
     *
     * <p>Both blocks are checked, at foot level and one up, because a step the body can walk over is not a
     * wall and should not read as one. The position comes back rather than a flag because whatever decides
     * to dig through it needs something to dig at, and while the body is fleeing the thing it is attending
     * to is the mob behind it, not the obstruction in front.
     */
    public static BlockPos wallAhead(LocalPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 front = player.position().add(look.x, 0.0, look.z);
        BlockPos ahead = BlockPos.containing(front.x, player.position().y + 0.5, front.z);
        Level level = player.level();
        if (!level.isLoaded(ahead)) {
            return null;
        }
        boolean blocked = level.getBlockState(ahead).isSolid()
                && level.getBlockState(ahead.above()).isSolid();
        return blocked ? ahead.immutable() : null;
    }

    /** Health band of the body, in thirds of its maximum. */
    public static Health healthOf(LocalPlayer player) {
        float fraction = Mth.clamp(player.getHealth() / Math.max(1.0F, player.getMaxHealth()), 0.0F, 1.0F);
        if (fraction <= 0.34F) {
            return Health.LOW;
        }
        return fraction <= 0.67F ? Health.MID : Health.HIGH;
    }

    public enum Distance {
        NONE, CLOSE, NEAR, FAR
    }

    public enum Health {
        LOW, MID, HIGH
    }
}
