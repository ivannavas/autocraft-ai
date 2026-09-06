package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a step up out of whatever blocks the body is carrying.
 *
 * <p>It pillars: jump, and while off the ground put a block on the one just left. Done a few times that is
 * a way over a wall, which is the situation this exists for — the brain can see that it is walled in and
 * carrying blocks, and this is the move that changes that.
 *
 * <p>Whether it learns to reach for this while fleeing is not decided here. The goal only makes the move
 * possible; the table decides when it is worth making.
 */
public final class PlaceBlockGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    private static final int GIVE_UP_TICKS = 60;
    /** How far down to look for the block to build on. */
    private static final int SUPPORT_SEARCH = 3;

    private int ticksRunning;
    private boolean placed;

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return !placed && hotbarSlotWithBlock(body.player()) >= 0;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !placed && ticksRunning < GIVE_UP_TICKS;
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        placed = false;
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        int slot = hotbarSlotWithBlock(body.player());
        if (slot < 0) {
            return;
        }
        body.player().getInventory().setSelectedSlot(slot);
        // Standing still: the block has to go under the body, not wherever it drifted to.
        body.moveControl().stop();

        LocalPlayer player = body.player();
        if (player.onGround()) {
            body.jump();
            return;
        }

        BlockPos support = supportBelow(body);
        if (support == null) {
            return;
        }
        Vec3 top = Vec3.atCenterOf(support).add(0.0, 0.5, 0.0);
        body.lookControl().lookAt(top);
        gameMode().ifPresent(mode -> {
            mode.useItemOn(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(top, Direction.UP, support, false));
            player.swing(InteractionHand.MAIN_HAND);
        });
        placed = true;
    }

    /** The highest solid block under the body, which is the one a new block goes on top of. */
    private BlockPos supportBelow(MobBody body) {
        BlockPos feet = body.player().blockPosition();
        for (int drop = 1; drop <= SUPPORT_SEARCH; drop++) {
            BlockPos candidate = feet.below(drop);
            if (body.level().isLoaded(candidate) && body.level().getBlockState(candidate).isSolid()) {
                return candidate;
            }
        }
        return null;
    }

    /** Whether the body has something it could put down. Also what makes this action legal at all. */
    public static int hotbarSlotWithBlock(LocalPlayer player) {
        if (player == null) {
            return -1;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                return slot;
            }
        }
        return -1;
    }

    private Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        return "PlaceBlock";
    }
}
