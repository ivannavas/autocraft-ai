package io.github.ivannavas.autocraftai.mob.goal;

import java.util.Optional;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.ai.Placed;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Jump, and put a block into the space the feet have just left — done at the moment it can actually work.
 *
 * <p>The move is timing and nothing else, and the timing used to be wrong. A block cannot go where a body
 * is: the game refuses a placement whose space the player's box overlaps, on the client before the click is
 * even sent. A jump lifts the feet by 0.42 on its first tick, 0.75 on the second and only clears a whole
 * block on the third; the old code clicked on the first, was refused every time, and — never having looked
 * at the answer — called the block placed and handed the decision back. From outside that read as a body
 * that hops in place and cannot coordinate the hands with the legs, which is exactly what it was.
 *
 * <p>So this waits. The support is the top solid block under the feet; the new block goes on it, so its top
 * will be two above the support's base, and the feet have to be at least that high before the click. Until
 * they are, the answer is to keep jumping: on the ground that is the jump itself, in water it is a stroke
 * upward, and in the air it is nothing. The click is only counted when the game says it consumed it, so a
 * refusal is tried again next tick rather than believed.
 */
@Slf4j
final class Pillar {

    /** How far down to look for the block to build on. Deeper is a hole, not a floor. */
    private static final int SUPPORT_SEARCH = 3;
    /**
     * How high the feet have to be above the support's base for the new block to fit under them: the
     * block's own height plus the support's. Equality is enough — the game's overlap test is strict.
     */
    private static final double CLEARANCE = 2.0;

    /** What one tick of pillaring came to. */
    enum Step {
        /** Nothing solid within reach below to build on. */
        NO_SUPPORT,
        /** Not high enough yet; a jump was asked for. */
        RISING,
        /** The block is down. */
        PLACED,
        /** High enough, clicked, and the game said no. Worth another go next tick. */
        REFUSED
    }

    private Pillar() {
    }

    /**
     * One tick of the move. The caller has already put a block in the hand; this stands still, looks
     * down, jumps until there is room, and places.
     */
    static Step tick(MobBody body) {
        LocalPlayer player = body.player();
        // Standing still: the block has to go under the body, not wherever it drifted to.
        body.moveControl().stop();
        BlockPos feet = player.blockPosition();
        if (player.onGround()) {
            // On the ground the support is the block under the feet and nothing else. Searching
            // further down found the floor under a slab the body was standing on, and put the block
            // in the gap under the slab — three times, thirty seconds apart, with no height gained.
            // Feet inside a block that is not a whole one (a bottom slab) are feet in the space the
            // new block would need, and there is no building there.
            if (!body.level().getBlockState(feet).getCollisionShape(body.level(), feet).isEmpty()
                    || !supports(body, feet.below())) {
                return Step.NO_SUPPORT;
            }
        }
        BlockPos support = player.onGround() ? feet.below() : supportBelow(body, feet);
        if (support == null) {
            return Step.NO_SUPPORT;
        }
        Vec3 top = Vec3.atCenterOf(support).add(0.0, 0.5, 0.0);
        body.lookControl().lookAt(top);
        if (player.getY() < support.getY() + CLEARANCE) {
            body.jump();
            return Step.RISING;
        }
        BlockPos where = support.above();
        if (!body.level().getBlockState(where).canBeReplaced()) {
            return Step.NO_SUPPORT;
        }
        Optional<MultiPlayerGameMode> mode = Optional.ofNullable(Minecraft.getInstance().gameMode);
        if (mode.isEmpty()) {
            return Step.REFUSED;
        }
        // Copied before the click: after it the stack may be gone, and the record wants to know what went.
        ItemStack hand = player.getMainHandItem().copy();
        InteractionResult result = mode.get().useItemOn(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(top, Direction.UP, support, false));
        if (!result.consumesAction()) {
            log.debug("Pillar refused at {} with feet at {}", where, player.getY());
            return Step.REFUSED;
        }
        Placed.get().mark(where, hand);
        player.swing(InteractionHand.MAIN_HAND);
        log.debug("Pillar placed {} at {}", hand.getItem(), where);
        return Step.PLACED;
    }

    /**
     * The highest block under the feet within reach that a block could go on top of, for a body in
     * the air mid-jump: the one it left and will land back on.
     */
    private static BlockPos supportBelow(MobBody body, BlockPos feet) {
        for (int drop = 1; drop <= SUPPORT_SEARCH; drop++) {
            BlockPos candidate = feet.below(drop);
            if (supports(body, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether a block could be put on top of this one: its collision box reaches the top of its space.
     * A whole block does, and so do a top slab and a fence; a bottom slab does not, and nor does air.
     */
    private static boolean supports(MobBody body, BlockPos pos) {
        if (!body.level().isLoaded(pos)) {
            return false;
        }
        var shape = body.level().getBlockState(pos).getCollisionShape(body.level(), pos);
        return !shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) >= 1.0 - 1.0E-6;
    }
}
