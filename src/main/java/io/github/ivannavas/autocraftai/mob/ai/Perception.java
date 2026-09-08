package io.github.ivannavas.autocraftai.mob.ai;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Predicate;

import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.objective.Terrain;
import io.github.ivannavas.autocraftai.mob.goal.EatGoal;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
    /** At or below this the body is hungry enough for the tables to be told about it. */
    private static final int HUNGRY_BELOW = 10;
    /** Box half-extents for the wanted-block scan. */
    private static final int BLOCK_SCAN_HORIZONTAL = 8;
    private static final int BLOCK_SCAN_VERTICAL = 4;

    public Sighting look(Minecraft client, LocalPlayer player, Optional<Predicate<BlockState>> wanted) {
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
    private Sighting nearestWantedBlock(LocalPlayer player, Optional<Predicate<BlockState>> wanted) {
        if (wanted.isEmpty()) {
            return Sighting.nothing();
        }
        Predicate<BlockState> kind = wanted.get();
        Level level = player.level();
        BlockPos origin = player.blockPosition();
        Vec3 from = player.position();

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-BLOCK_SCAN_HORIZONTAL, -BLOCK_SCAN_VERTICAL, -BLOCK_SCAN_HORIZONTAL),
                origin.offset(BLOCK_SCAN_HORIZONTAL, BLOCK_SCAN_VERTICAL, BLOCK_SCAN_HORIZONTAL))) {
            if (!level.isLoaded(pos) || !kind.test(level.getBlockState(pos))) {
                continue;
            }
            // Never one of its own. A block the body put down is a resource it already has, and seeing
            // it as one to be gathered is how it came to dig its own pillar out from under itself.
            if (Placed.get().isOurs(level, pos)) {
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

    /**
     * Whether there is ground under the feet worth breaking, with more ground under that.
     *
     * <p>The same two-block check {@link io.github.ivannavas.autocraftai.mob.goal.DigDownGoal} makes before
     * every swing, asked here so an impossible dig is never chosen in the first place. Standing over air or
     * lava is not somewhere to dig; it is somewhere to already be falling.
     */
    public static boolean canDigDown(LocalPlayer player) {
        Level level = player.level();
        BlockPos under = player.blockPosition().below();
        BlockPos below = under.below();
        if (!level.isLoaded(under) || !level.isLoaded(below)) {
            return false;
        }
        return !level.getBlockState(under).isAir()
                && level.getFluidState(under).isEmpty()
                && level.getBlockState(below).isSolid()
                && level.getFluidState(below).isEmpty();
    }

    /** How far out the loaded world is searched for a kind of place, in blocks. Eight chunks, as loaded. */
    private static final int TERRAIN_SCAN = 128;
    /** How far apart the samples along each direction are. A biome smaller than this is not worth the trip. */
    private static final int TERRAIN_STEP = 16;
    /** How many directions are tried. Sixteen is a compass with half-points, and enough. */
    private static final int TERRAIN_RAYS = 16;

    /**
     * The way to the nearest place of one of these kinds that the loaded world already contains, as a yaw
     * in radians, or empty when none is in range.
     *
     * <p>The game keeps eight chunks of biome loaded in every direction, which is a hundred and twenty
     * eight blocks of map the body was not reading. Told to find a forest it wandered until it stumbled
     * into one, and on the first run it never did; the forest was two hundred blocks off and the position
     * table was learning which way to turn from a reward that could not see it. This looks. It is a fact
     * about the map, not a lesson, so it is not learned: where the forest is is not a matter of opinion.
     *
     * <p>Nearest first, so a scan stops at the first ring that has one; the direction is the ring's, which
     * for a body outside the biome is the way in.
     */
    public static OptionalDouble bearingTo(LocalPlayer player, List<Terrain> kinds) {
        if (kinds.isEmpty()) {
            return OptionalDouble.empty();
        }
        Level level = player.level();
        Vec3 here = player.position();
        for (int radius = TERRAIN_STEP; radius <= TERRAIN_SCAN; radius += TERRAIN_STEP) {
            for (int ray = 0; ray < TERRAIN_RAYS; ray++) {
                double heading = ray * (2.0 * Math.PI / TERRAIN_RAYS);
                int x = (int) Math.floor(here.x - Math.sin(heading) * radius);
                int z = (int) Math.floor(here.z + Math.cos(heading) * radius);
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                String biome = level.getBiome(new BlockPos(x, player.getBlockY(), z)).getRegisteredName();
                if (kinds.stream().anyMatch(kind -> kind.matches(biome))) {
                    return OptionalDouble.of(heading);
                }
            }
        }
        return OptionalDouble.empty();
    }

    /** How far under the surface a wanted block is looked for: a trunk under its canopy, an ore in a cliff. */
    private static final int SURFACE_DEPTH = 8;

    /**
     * The way to the nearest place in the loaded map where one of the wanted blocks stands at or just
     * under the surface, as a yaw in radians, or empty when the map shows none.
     *
     * <p>The kind of place is not enough. Plains have oaks, and a body on plains looking for oak is in
     * the right kind of place and may still be eighty blocks from the nearest tree, with eyes that reach
     * eight. It walked at random for eight minutes that way. The heightmap says where the surface is at
     * every loaded column, and a few blocks under the surface is where a trunk is; sampling the columns
     * along sixteen rays is a few hundred block reads, once a decision, and it turns a search into a walk.
     */
    public static OptionalDouble bearingToBlock(LocalPlayer player, Predicate<BlockState> wanted) {
        Level level = player.level();
        Vec3 here = player.position();
        for (int radius = TERRAIN_STEP / 2; radius <= TERRAIN_SCAN; radius += TERRAIN_STEP / 2) {
            for (int ray = 0; ray < TERRAIN_RAYS; ray++) {
                double heading = ray * (2.0 * Math.PI / TERRAIN_RAYS);
                int x = (int) Math.floor(here.x - Math.sin(heading) * radius);
                int z = (int) Math.floor(here.z + Math.cos(heading) * radius);
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                for (int y = top; y > top - SURFACE_DEPTH; y--) {
                    if (wanted.test(level.getBlockState(new BlockPos(x, y, z)))) {
                        return OptionalDouble.of(heading);
                    }
                }
            }
        }
        return OptionalDouble.empty();
    }

    /**
     * Whether the body is hungry enough for it to be worth a decision.
     *
     * <p>Half a bar, not one point short of full. Every player is a little hungry most of the time, and a
     * flag that is true almost always would split every state in the table for nothing.
     */
    public static boolean isHungry(LocalPlayer player) {
        return player.getFoodData().getFoodLevel() <= HUNGRY_BELOW;
    }

    /** Whether there is a mouthful in the hotbar and room for it. */
    public static boolean canEat(LocalPlayer player) {
        return player.canEat(false) && EatGoal.hotbarSlotWithFood(player) >= 0;
    }

    /** Whether the larder is full enough that killing another animal is only a way to pass the time. */
    public static boolean wellFed(LocalPlayer player) {
        return Resource.FOOD.countIn(player.getInventory()) >= Resource.ENOUGH_FOOD;
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
