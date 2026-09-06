package io.github.ivannavas.autocraftai.mob.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * One thing the body has in view, and the handle the goals steer by.
 *
 * <p>The position is read live rather than captured: an entity sighting follows the entity as it moves, so
 * a goal chasing or fleeing one keeps aiming at where it actually is. {@link #isValid()} is what tells a
 * goal its reason to exist has gone — the mob died, the drop was picked up, the block was broken.
 */
public final class Sighting {

    private static final Sighting NOTHING = new Sighting(FocusKind.NOTHING, null, null);

    private final FocusKind kind;
    private final Entity entity;
    private final BlockPos blockPos;

    private Sighting(FocusKind kind, Entity entity, BlockPos blockPos) {
        this.kind = kind;
        this.entity = entity;
        this.blockPos = blockPos;
    }

    public static Sighting nothing() {
        return NOTHING;
    }

    public static Sighting of(FocusKind kind, Entity entity) {
        return new Sighting(kind, entity, null);
    }

    public static Sighting ofBlock(FocusKind kind, BlockPos pos) {
        return new Sighting(kind, null, pos.immutable());
    }

    public FocusKind kind() {
        return kind;
    }

    public boolean isPresent() {
        return kind != FocusKind.NOTHING;
    }

    public boolean isBlock() {
        return blockPos != null;
    }

    /** Something that moves under its own power, which is the only kind of thing worth fleeing. */
    public boolean isCreature() {
        return kind == FocusKind.HOSTILE || kind == FocusKind.PASSIVE || kind == FocusKind.PLAYER;
    }

    /** The block this is a sighting of, or {@code null} when it is an entity or nothing at all. */
    public BlockPos blockPos() {
        return blockPos;
    }

    public Entity entity() {
        return entity;
    }

    /**
     * Whether the thing is still there to be acted on. A block sighting stays valid as long as the position
     * is known; whether the block itself survived is the mining goal's business, since only it knows which
     * block it was after.
     */
    public boolean isValid() {
        if (!isPresent()) {
            return false;
        }
        return entity == null || (!entity.isRemoved() && entity.isAlive());
    }

    /** Where it is now. */
    public Vec3 position() {
        return entity != null ? entity.position() : Vec3.atCenterOf(blockPos);
    }

    /** Where to look to meet its eye, or its middle for a block. */
    public Vec3 eyePosition() {
        return entity != null ? entity.getEyePosition() : Vec3.atCenterOf(blockPos);
    }

    /**
     * What the sighting is of, for telling two sightings apart. The entity itself when there is one, so a
     * different mob of the same kind counts as a new thing to react to.
     */
    public Object target() {
        return entity != null ? entity : blockPos;
    }
}
