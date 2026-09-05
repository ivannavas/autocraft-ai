package io.github.ivannavas.autocraftai.mob.ai;

/**
 * What kind of thing the body is attending to. This is the coarsest useful split: the brain has to
 * generalise over "a zombie" and "a skeleton", not learn each mob separately.
 */
public enum FocusKind {
    /** Nothing worth reacting to. */
    NOTHING,
    /** A block under the crosshair that the current rung has no use for. */
    BLOCK,
    /** A block the current rung wants: wood while gathering wood, stone while gathering stone. */
    RESOURCE,
    /** A dropped item lying on the ground. */
    ITEM,
    /** A living thing that does not attack. */
    PASSIVE,
    /** A living thing that does. */
    HOSTILE,
    /** Another player. */
    PLAYER
}
