package io.github.ivannavas.autocraftai.mob.ai;

/**
 * How this run learns: what its beliefs are held in, how hard it trains them, whether it keeps what it
 * has seen, and whether there is a coach.
 *
 * <p>A record rather than a handful of settings read where they are needed, because all of it has to be
 * known before the first table is opened and none of it may change while a run is up: the store decides
 * what the saved files mean, and the mentor decides which model the planner is.
 *
 * @param network     hold beliefs in a {@link QNetwork} rather than a {@link QTable}
 * @param capacity    moves kept per layer to be learned from again; ignored without a network
 * @param batch       moves drawn from that window per training pass
 * @param perDecision training passes per decision. Zero learns only from what replay already holds
 * @param log         write every move to {@link Experience}, whichever store is in use
 * @param mentor      ask the coach about a body that is stuck
 */
public record Learning(boolean network, int capacity, int batch, int perDecision,
                       boolean log, boolean mentor) {

    /** What the run did before any of this existed: one row per state, each move learned from once. */
    public static Learning tables() {
        return new Learning(false, 0, 0, 0, true, true);
    }
}
