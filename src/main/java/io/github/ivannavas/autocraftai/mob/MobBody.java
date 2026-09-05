package io.github.ivannavas.autocraftai.mob;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * The body the goals drive: everything they are allowed to sense, and the only place they are allowed to
 * write.
 *
 * <p>Goals never touch the player directly. They set a wanted destination on the {@link MoveControl} or a
 * wanted facing on the {@link LookControl}; those two turn the wants into a command buffer, and the input
 * mixin hands the buffer to the game in place of the keyboard. Everything is rebuilt from scratch each
 * tick, so a goal that stops writing simply goes limp instead of leaving the last command latched on.
 */
public final class MobBody {

    private final LookControl lookControl = new LookControl(this);
    private final MoveControl moveControl = new MoveControl(this);

    private LocalPlayer player;

    private float forwardImpulse;
    private float strafeImpulse;
    private boolean jumping;
    private boolean sneaking;
    private boolean sprinting;

    /** Points the body at the player of the tick about to run and drops the previous tick's commands. */
    void beginTick(LocalPlayer player) {
        this.player = player;
        this.forwardImpulse = 0.0F;
        this.strafeImpulse = 0.0F;
        this.jumping = false;
        this.sneaking = false;
        this.sprinting = false;
    }

    /** Runs the two controls in the order that makes movement follow the new facing, not the old one. */
    void driveControls() {
        moveControl.aimLook(lookControl);
        lookControl.tick();
        moveControl.tick();
    }

    /** Drops every want, so releasing the player leaves nothing behind. */
    void reset() {
        moveControl.stop();
        lookControl.clear();
        beginTick(null);
    }

    // --- what goals can sense -------------------------------------------------------------------

    public LocalPlayer player() {
        return player;
    }

    public Level level() {
        return player.level();
    }

    public RandomSource random() {
        return player.getRandom();
    }

    public Vec3 position() {
        return player.position();
    }

    public boolean onGround() {
        return player.onGround();
    }

    public boolean againstWall() {
        return player.horizontalCollision;
    }

    public MoveControl moveControl() {
        return moveControl;
    }

    public LookControl lookControl() {
        return lookControl;
    }

    // --- what the controls write ----------------------------------------------------------------

    void setImpulse(float forward, float strafe) {
        this.forwardImpulse = forward;
        this.strafeImpulse = strafe;
    }

    void setJumping(boolean jumping) {
        this.jumping = jumping;
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
    }

    // --- what the input mixin reads --------------------------------------------------------------

    /**
     * The key state the game and the server see. The game reads jump, shift and sprint from here and takes
     * the analogue movement from {@link #moveVector()}, so the directional flags only have to agree in sign
     * with the impulses for sprinting and the tutorial hints to behave.
     */
    public Input keyPresses() {
        return new Input(
                forwardImpulse > 0.0F,
                forwardImpulse < 0.0F,
                strafeImpulse > 0.0F,
                strafeImpulse < 0.0F,
                jumping,
                sneaking,
                sprinting);
    }

    /**
     * The movement impulse, in the same frame the keyboard would produce it: {@code x} strafes left,
     * {@code y} walks forward. Clamped to unit length rather than normalised, so a goal asking for half
     * speed gets half speed instead of being rounded back up to a sprint.
     */
    public Vec2 moveVector() {
        float lengthSqr = forwardImpulse * forwardImpulse + strafeImpulse * strafeImpulse;
        if (lengthSqr <= 1.0F) {
            return new Vec2(strafeImpulse, forwardImpulse);
        }
        float scale = 1.0F / (float) Math.sqrt(lengthSqr);
        return new Vec2(strafeImpulse * scale, forwardImpulse * scale);
    }
}
