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
        int steps) {

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
}
