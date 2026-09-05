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
 */
public record StepContext(
        LocalPlayer player,
        float healthBefore,
        float healthAfter,
        Vec3 positionBefore,
        Vec3 positionAfter,
        InventoryCensus before,
        InventoryCensus after) {

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
