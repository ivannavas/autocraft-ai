package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.List;

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
 *
 * <p>{@link #stalledSteps()} is the same kind of fact and the more general one: how many of this move's
 * seconds the goal running it had nothing at all to show for. A block that finished breaking ten seconds
 * ago, a wall that will not be walked through, a spot that cannot be reached — from a pair of snapshots
 * all of them read as a step where nothing much happened, which is also what a slow but perfectly good
 * step reads as. Only the goal itself knows the difference, and this is it saying so. See
 * {@link io.github.ivannavas.autocraftai.mob.MobGoal#stalledTicks()}.
 *
 * <p>{@link #pinned()} reaches further back than one step, and has to: whether the body is stuck is not a
 * fact about the last second but about the last minute of them, which is what
 * {@link io.github.ivannavas.autocraftai.mob.ai.Territory} is for.
 *
 * <p>{@link #resourceInSight()} is the eyes' answer, passed through because nothing else can see: whether
 * the body ended the step somewhere that has what the plan is after.
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
        int wastedTicks,
        int stalledSteps,
        int foodBefore,
        int foodAfter,
        int airBefore,
        int airAfter,
        List<Resource> crafted,
        boolean pinned,
        boolean resourceInSight) {

    /** Twenty ticks to the second, which is Minecraft's clock and not this class's opinion. */
    private static final double TICKS_PER_SECOND = 20.0;

    public StepContext {
        crafted = crafted == null ? List.of() : List.copyOf(crafted);
    }

    /** Points of hunger restored; zero when it only ticked down, which is not this term's business. */
    public int foodGained() {
        return Math.max(0, foodAfter - foodBefore);
    }

    /**
     * Breath gained, and lost when it went the other way.
     *
     * <p>Counted both ways, unlike hunger. Hunger ticks down all day whatever the body is doing, so
     * charging for the fall would be charging for the passage of time; air only falls while the head is
     * under water, and that fall is the whole danger. A dive that comes back up nets to nothing, and one
     * that does not is a run of charges leading up to the damage rather than a surprise when it starts.
     */
    public int airDelta() {
        return airAfter - airBefore;
    }

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

    /**
     * How much more of this the body holds than it did, and how much less when it went the other way.
     *
     * <p>The signed version of {@link #gained}, for the one objective that has to care about losses:
     * gathering. Everything else is measured by what turned up, and turning up is the only direction
     * those things go.
     */
    public int netChange(Resource resource) {
        return after.count(resource) - before.count(resource);
    }

    /** Seconds spent swinging at blocks the thing in hand was never going to get a drop out of. */
    public double wastedSeconds() {
        return wastedTicks / TICKS_PER_SECOND;
    }
}
