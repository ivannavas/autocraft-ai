package io.github.ivannavas.autocraftai.mob;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Walks the body towards a destination.
 *
 * <p>There is no pathfinding here: a client-side player has no {@code PathNavigation}, so this steers in a
 * straight line and hops when it walks into something, which is enough to wander over ordinary terrain. The
 * destination is a standing want, kept until it is reached or {@link #stop} is called.
 *
 * <p>The impulse is worked out in the body's own frame from the world direction to the destination rather
 * than assuming forward always points at it, so movement stays correct while the body is still turning, and
 * while a goal holding {@link MobControl#LOOK} has it facing somewhere else entirely.
 */
public final class MoveControl {

    /** Horizontal distance at which the destination counts as reached. */
    private static final double ARRIVAL_DISTANCE = 0.6;

    private final MobBody body;

    private boolean hasDestination;
    private double destinationX;
    private double destinationY;
    private double destinationZ;
    private float speed;

    MoveControl(MobBody body) {
        this.body = body;
    }

    public void moveTo(Vec3 destination, float speed) {
        moveTo(destination.x, destination.y, destination.z, speed);
    }

    /** @param speed fraction of walking speed, in {@code (0, 1]} */
    public void moveTo(double x, double y, double z, float speed) {
        this.destinationX = x;
        this.destinationY = y;
        this.destinationZ = z;
        this.speed = Mth.clamp(speed, 0.0F, 1.0F);
        this.hasDestination = true;
    }

    public boolean hasDestination() {
        return hasDestination;
    }

    public Vec3 destination() {
        return new Vec3(destinationX, destinationY, destinationZ);
    }

    public void stop() {
        this.hasDestination = false;
    }

    /** Offers the destination as a facing, so the body looks where it walks unless a goal says otherwise. */
    void aimLook(LookControl lookControl) {
        LocalPlayer player = body.player();
        if (!hasDestination || player == null) {
            return;
        }
        // Aim at eye height: the destination's own Y would have it staring at its feet on every step down.
        lookControl.aimAt(destinationX, player.getEyeY(), destinationZ);
    }

    void tick() {
        LocalPlayer player = body.player();
        if (!hasDestination || player == null) {
            return;
        }

        double dx = destinationX - player.getX();
        double dz = destinationZ - player.getZ();
        double distanceSqr = dx * dx + dz * dz;
        if (distanceSqr < ARRIVAL_DISTANCE * ARRIVAL_DISTANCE) {
            stop();
            return;
        }

        // Decompose the world direction into the (strafe, forward) pair the movement code expects. This is
        // the inverse of the rotation LivingEntity applies to the input vector: with yaw y, forward points
        // at (-sin y, cos y) and left at (cos y, sin y).
        double distance = Math.sqrt(distanceSqr);
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        float sin = Mth.sin(yaw);
        float cos = Mth.cos(yaw);
        double nx = dx / distance;
        double nz = dz / distance;
        body.setImpulse(
                (float) (nz * cos - nx * sin) * speed,
                (float) (nx * cos + nz * sin) * speed);

        // Nothing plans a route, so obstacles are handled the way a mob handles them: hop at them, and swim
        // up rather than sink when the way through is water.
        if (player.isInWater() || (player.horizontalCollision && player.onGround())) {
            body.setJumping(true);
        }
    }
}
