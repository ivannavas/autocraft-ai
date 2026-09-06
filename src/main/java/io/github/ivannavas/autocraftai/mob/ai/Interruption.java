package io.github.ivannavas.autocraftai.mob.ai;

/**
 * What to do about a move that is going nowhere.
 *
 * <p>Deliberately a short list rather than every goal there is. This table is only ever asked when the body
 * is stuck or walled in, so the question is not "what should I be doing" — the goal table answered that —
 * but "what gets me moving again". Offering it the whole action set would spend its exploration on answers
 * to a question nobody asked.
 */
public enum Interruption {

    /** Leave the move alone. The commitment was fine; being stuck for a second is not being stuck. */
    CONTINUE,
    /** Dig through whatever is in the way. Needs something in the way to dig. */
    MINE_WALL,
    /** Build over it. Needs something to build with. */
    PLACE,
    /** Give up on the spot and go somewhere else. Always available, and the fallback that makes one legal. */
    WANDER
}
