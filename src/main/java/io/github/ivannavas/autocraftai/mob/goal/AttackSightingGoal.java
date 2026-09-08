package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.Sighting;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

/**
 * Walks up to something living and hits it.
 *
 * <p>The body could see a zombie, walk towards it, watch it and run from it, and it could not lay a finger
 * on it — there was no action that swung at anything but a block. So a creeper was a thing to be endured,
 * a cow was scenery, and the only answer the table could ever learn to a mob was to leave. This is the
 * missing verb.
 *
 * <h2>Waiting for the swing to charge</h2>
 * A player who clicks as fast as possible does about a third of the damage of one who waits for the
 * cooldown, and the body has no reason to be worse at this than a person. It swings only when
 * {@link net.minecraft.world.entity.player.Player#getAttackStrengthScale} says the blow is fully charged,
 * which for a wooden sword is about once every three-fifths of a second.
 *
 * <p>The weapon is chosen the same way {@link MineSightingGoal} chooses a pickaxe: a sword if the hotbar
 * has one, an axe if not, fists if neither. Picked once on arrival, because switching slots mid-fight only
 * costs the charge that was building.
 */
public final class AttackSightingGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** Kept under the server's reach so a blow is never thrown from too far to land. */
    private static final double REACH_MARGIN = 0.5;
    /** A fight that has not been settled in this long is one to walk away from. */
    private static final int GIVE_UP_TICKS = 400;
    /** Below this the blow is a tap; vanilla pays full damage only at the top of the swing. */
    private static final float CHARGED = 0.95F;
    private static final float SPEED = 1.0F;

    private final Sighting sighting;
    private final Entity target;

    private int ticksRunning;
    private boolean armed;
    private final Advance advance = new Advance();

    public AttackSightingGoal(Sighting sighting) {
        this.sighting = sighting;
        this.target = sighting.entity();
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return target instanceof LivingEntity living && living.isAlive();
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return canUse(body) && ticksRunning < GIVE_UP_TICKS;
    }

    /**
     * Ground covered while closing, and blows landed once close. Waiting out the cooldown is not stalling
     * — it is what makes the next blow worth three of a hurried one — and it never lasts long enough to
     * register, since a full swing charges in well under a second.
     */
    @Override
    public int stalledTicks() {
        return advance.stalledTicks();
    }

    @Override
    public void start(MobBody body) {
        ticksRunning = 0;
        armed = false;
        advance.reset();
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        Vec3 aim = target.getEyePosition();
        body.lookControl().lookAt(aim);

        if (!withinReach(body, aim)) {
            body.moveControl().moveTo(target.position(), SPEED);
            advance.walking(body);
            return;
        }

        // Close enough to hit: stand and fight. Walking into it would only push the body past the target
        // and turn the fight into a chase around it.
        body.moveControl().stop();
        arm(body);
        if (body.player().getAttackStrengthScale(0.0F) >= CHARGED) {
            gameMode().ifPresent(mode -> mode.attack(body.player(), target));
            body.player().swing(InteractionHand.MAIN_HAND);
            body.player().resetAttackStrengthTicker();
            advance.progress();
        }
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
    }

    /** The best thing in the hotbar to hit with, chosen once. */
    private void arm(MobBody body) {
        if (armed) {
            return;
        }
        armed = true;
        Inventory inventory = body.player().getInventory();
        int slot = Tool.SWORD.hotbarSlot(inventory);
        if (slot < 0) {
            slot = Tool.AXE.hotbarSlot(inventory);
        }
        if (slot >= 0) {
            inventory.setSelectedSlot(slot);
        }
    }

    private boolean withinReach(MobBody body, Vec3 aim) {
        double reach = body.player().entityInteractionRange() - REACH_MARGIN;
        return body.player().getEyePosition().distanceToSqr(aim) <= reach * reach;
    }

    private Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        return "Attack(" + sighting.kind() + ")";
    }
}
