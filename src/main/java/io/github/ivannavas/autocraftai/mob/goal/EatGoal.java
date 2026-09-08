package io.github.ivannavas.autocraftai.mob.goal;

import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Eats one thing.
 *
 * <p>A body that starves is a body that dies, and dying costs more than anything else in the run. It had
 * no way to eat: nothing in the action set held down the use button, so a bag full of pork chops was a bag
 * full of nothing. This holds it down until the mouthful is finished, and then stops, so a decision to eat
 * costs one item rather than the whole larder.
 *
 * <h2>It has to hold the button, not click it</h2>
 * {@code useItem} starts the mouthful and vanilla ends it on the very next tick, because
 * {@code Minecraft.handleKeybinds} releases whatever is in use the moment the use key is not down — and
 * no key is ever down here. The first version of this goal called {@code useItem} once, saw the item no
 * longer in use a tick later, and reported the mouthful eaten; the body starved with a bag full of food it
 * had "eaten" fourteen times. So every tick of the mouthful the goal asks the body to keep the button held,
 * and {@code MultiPlayerGameModeMixin} keeps the release from happening while it does.
 *
 * <h2>It claims no controls</h2>
 * You can walk and eat, and eating needs neither the legs nor the eyes, so the engine never sees this as a
 * rival to anything. In practice the brain installs it as the move of the moment and the body stands still
 * for the second and a half it takes — which is what a player does too.
 */
public final class EatGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = Set.of();

    /** A mouthful is 32 ticks; twice that is enough for the server to have said no. */
    private static final int GIVE_UP_TICKS = 80;

    private int ticksRunning;
    private boolean started;
    private boolean eaten;

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return !eaten && body.player().canEat(false) && hotbarSlotWithFood(body.player()) >= 0;
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !eaten && ticksRunning < GIVE_UP_TICKS;
    }

    /**
     * Nothing to report, and that is the honest answer rather than an omission. A mouthful is a second and
     * a half of standing still and every one of those seconds is the move working; the other case — the
     * use refused, the food knocked out of the hand — does not leave this goal standing about, it ends it,
     * and a goal that has ended is already counted by the brain as a second spent on nothing.
     */
    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        started = false;
        eaten = false;
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        LocalPlayer player = body.player();

        if (!started) {
            int slot = hotbarSlotWithFood(player);
            if (slot < 0) {
                eaten = true;
                return;
            }
            player.getInventory().setSelectedSlot(slot);
            // The same right-click a player makes. The server answers by starting the animation, and from
            // then on the item eats itself down as long as the button stays held.
            gameMode().ifPresent(mode -> mode.useItem(player, InteractionHand.MAIN_HAND));
            body.holdUse();
            started = true;
            return;
        }

        // Started and no longer using it means the mouthful is finished — or something knocked it out of
        // the body's hand. Either way this decision is over; whether to eat again is the brain's to make.
        if (!player.isUsingItem()) {
            eaten = true;
            return;
        }
        body.holdUse();
    }

    /** The mouthful is down: the move is complete, not stuck, and the brain need not wait on it. */
    @Override
    public boolean isDone() {
        return eaten;
    }

    @Override
    public void stop(MobBody body) {
        if (body.player().isUsingItem()) {
            gameMode().ifPresent(mode -> mode.releaseUsingItem(body.player()));
        }
        started = false;
    }

    /** Whether the mouthful is done, which is what tells the brain this move has run its course. */
    public boolean isFinished() {
        return eaten;
    }

    /** The hotbar slot holding something edible, or -1. Only the hotbar: nothing else can be eaten from. */
    public static int hotbarSlotWithFood(LocalPlayer player) {
        if (player == null) {
            return -1;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.get(DataComponents.FOOD) != null) {
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
        return "Eat";
    }
}
