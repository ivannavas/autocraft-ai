package io.github.ivannavas.autocraftai.mob.ai;

import java.util.OptionalDouble;

import net.minecraft.client.player.LocalPlayer;

/**
 * Which way to take the body, for the moves that are about going somewhere.
 *
 * <p>Wandering and travelling both had a heading and neither had a reason for it: one re-rolled a random
 * spot every few seconds, the other set off the way the body happened to be facing. Over a minute that is
 * a body that crosses its own path six times and calls it exploring. What was missing is not a better
 * heuristic but a decision — something that can be told apart, scored, and learned from — and that is what
 * these are. They are the columns of the position table, and {@link Territory} is what makes most of them
 * mean anything: without a memory of where the body has been, "somewhere else" is not a direction.
 *
 * <p>Height is deliberately not here. Where the body should be vertically is the plan's business, set as a
 * {@link io.github.ivannavas.autocraftai.mob.ai.objective.Bounds} band and charged for when it is outside;
 * the moves that answer it — digging down, building up — already exist and already have a table choosing
 * between them. This one answers the flat question, which is the one nothing was answering.
 */
public enum Ground {

    /** No push at all: the move goes wherever it was going to go. */
    STAY,

    /** Hold the line the body is already on. */
    ONWARD,

    /** Towards the least-trodden ground next door, which is the only cure for walking a loop. */
    FRESH,

    /** The way it came. Sometimes the way out is the way in. */
    BACK,

    LEFT,

    RIGHT;

    /** A quarter turn, which is what LEFT and RIGHT are. */
    private static final double QUARTER = Math.PI / 2.0;

    /**
     * The heading this means right now, as a yaw in radians, or empty when it means nothing.
     *
     * <p>Empty is a real answer and not a failure: {@link #STAY} always means it, and {@link #BACK} means
     * it when the body has not been anywhere yet. A move handed no heading does what it always did.
     */
    public OptionalDouble headingFor(LocalPlayer player, Territory territory) {
        // The body is only asked about by the choices that are relative to it, so the ones that are not —
        // staying put, and turning back along a trail — answer without needing one at all.
        return switch (this) {
            case STAY -> OptionalDouble.empty();
            case BACK -> territory.back();
            case ONWARD -> OptionalDouble.of(facing(player));
            case FRESH -> territory.towardsFresh(player.position());
            case LEFT -> OptionalDouble.of(facing(player) - QUARTER);
            case RIGHT -> OptionalDouble.of(facing(player) + QUARTER);
        };
    }

    private static double facing(LocalPlayer player) {
        return Math.toRadians(player.getYRot());
    }
}
