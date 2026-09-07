package io.github.ivannavas.autocraftai.mob.ai.objective;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Everything that happened between one decision and the next, which is all an {@link Objective} is ever
 * allowed to look at.
 *
 * <p>Objectives score the step, they do not observe the world at large: handing them a before-and-after
 * pair keeps every reward term a function of what actually changed, so two objectives can never disagree
 * about when "now" was.
 *
 * <p>{@link #obtained()} is the running total of everything the run has ever got hold of, which is what a
 * rung means by having got something. {@link #after()} is what is in the bag right now, which is what pays
 * for gaining it — spend it on planks and the total stands while the bag empties.
 *
 * <p>A step here is one whole move, which may have been held for one second or fifteen. {@link #steps()} is
 * how many, and any objective that charges or pays by the second has to use it — otherwise a long move
 * would pay the same standing costs as a short one and the table would learn to dawdle.
 *
 * <p>{@link #wastedTicks()} is the one thing here that no pair of snapshots could show: swinging at stone
 * bare-handed changes nothing about the body, the bag or the ground it stands on, which is exactly why it
 * had to be counted as it happened rather than worked out afterwards.
 */
public record StepContext(
        LocalPlayer player,
        float healthBefore,
        float healthAfter,
        Vec3 positionBefore,
        Vec3 positionAfter,
        InventoryCensus before,
        InventoryCensus after,
        InventoryCensus obtained,
        int steps,
        int wastedTicks) {

    /** Twenty ticks to the second, which is Minecraft's clock and not this class's opinion. */
    private static final double TICKS_PER_SECOND = 20.0;

    /** Half-hearts gained; negative when the body took a hit. */
    public float healthDelta() {
        return healthAfter - healthBefore;
    }

    /** Ground covered on the flat, ignoring the drop down a cliff. */
    public double distanceCovered() {
        double dx = positionAfter.x - positionBefore.x;
        double dz = positionAfter.z - positionBefore.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public int gained(Resource resource) {
        return after.gainedSince(before, resource);
    }

    /** Seconds spent swinging at blocks the thing in hand was never going to get a drop out of. */
    public double wastedSeconds() {
        return wastedTicks / TICKS_PER_SECOND;
    }
}
