package io.github.ivannavas.autocraftai.mob;

/**
 * The parts of the body a goal can claim. Two goals never hold the same control at the same time, so a
 * goal that walks somewhere is not fighting another goal that also wants to walk somewhere else.
 *
 * <p>Vanilla splits this further (it has a separate JUMP flag), but a player has a single yaw driving both
 * where it looks and where it walks, and jumping is only ever a side effect of trying to move. Two flags
 * are all this engine has to arbitrate.
 */
public enum MobControl {
    /** The impulse fed to the movement code: where the body walks. */
    MOVE,
    /** Yaw and pitch: where the body faces. */
    LOOK
}
