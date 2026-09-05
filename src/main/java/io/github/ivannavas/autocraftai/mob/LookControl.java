package io.github.ivannavas.autocraftai.mob;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Turns the body towards a point a few degrees at a time, the way a mob's head swings round instead of
 * snapping.
 *
 * <p>A player has one yaw for both the camera and the direction it walks, so this is the only thing in the
 * engine that writes rotation. The target is a per-tick want: a goal holding {@link MobControl#LOOK} calls
 * {@link #lookAt} on every one of its ticks, and {@link MoveControl} only offers a {@link #aimAt hint},
 * which is used when no goal asked for anything better. That way "walk there" faces where it is going,
 * while "watch that" wins over it and the walk keeps working by strafing.
 */
public final class LookControl {

    private static final float MAX_YAW_STEP = 30.0F;
    private static final float MAX_PITCH_STEP = 30.0F;

    private final MobBody body;

    private boolean hasTarget;
    private boolean explicit;
    private double targetX;
    private double targetY;
    private double targetZ;

    LookControl(MobBody body) {
        this.body = body;
    }

    /** Asks for a facing on behalf of a goal. Beats any hint offered this tick; lasts one tick. */
    public void lookAt(Vec3 target) {
        lookAt(target.x, target.y, target.z);
    }

    public void lookAt(double x, double y, double z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.hasTarget = true;
        this.explicit = true;
    }

    /** Offers a facing that only applies if no goal asked for one this tick. */
    void aimAt(double x, double y, double z) {
        if (explicit) {
            return;
        }
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.hasTarget = true;
    }

    void clear() {
        this.hasTarget = false;
        this.explicit = false;
    }

    void tick() {
        LocalPlayer player = body.player();
        if (!hasTarget || player == null) {
            clear();
            return;
        }

        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float wantedYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float wantedPitch = (float) -(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG);

        float yaw = Mth.approachDegrees(player.getYRot(), wantedYaw, MAX_YAW_STEP);
        player.setYRot(yaw);
        player.setXRot(Mth.approachDegrees(player.getXRot(), wantedPitch, MAX_PITCH_STEP));
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);

        clear();
    }
}
