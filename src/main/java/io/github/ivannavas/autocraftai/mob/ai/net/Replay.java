package io.github.ivannavas.autocraftai.mob.ai.net;

import java.util.Random;

/**
 * The moves a layer has made lately, kept so that each one can be learned from more than once.
 *
 * <h2>Why this is the point of the whole exercise</h2>
 * The body makes fifteen to twenty-eight decisions a minute. Every one of them costs a second or ten of
 * a real run, and under the tables every one was used exactly once: a single update at a rate of fifteen
 * hundredths, and then it was gone for ever. Nothing else about a run is so cheap to fix. Keeping the
 * last twenty thousand moves and replaying a batch of them at every decision uses each one perhaps fifty
 * times, and fifty times the learning out of the same hour of daylight is not a tuning change, it is the
 * difference between a body that learns overnight and one that does not.
 *
 * <p>It is also what makes learning from a network stable at all. Consecutive decisions are almost the
 * same situation — the body is still in the same corner of the same forest — and a network trained on
 * them in the order they arrive chases whatever it has been looking at for the last minute and forgets
 * the rest. Drawing the batch at random from a long window is what breaks that.
 *
 * <h2>A ring, and nothing cleverer</h2>
 * Oldest out first, drawn uniformly. Prioritised replay would draw the surprising moves more often and
 * would be worth having later; it also needs its own importance weights to stay unbiased, and the first
 * thing to find out is whether replay at all is what the run was missing.
 */
public final class Replay {

    /** One move, as everything the learning rule needs to score it again later. */
    public record Move(int[] on, double scale, int action, double reward, int steps,
                       int[] nextOn, double nextScale, boolean[] nextAllowed, boolean terminal) {
    }

    private final Move[] moves;
    private final Random random = new Random();
    private int at;
    private int held;

    public Replay(int capacity) {
        this.moves = new Move[Math.max(1, capacity)];
    }

    public int held() {
        return held;
    }

    public void add(Move move) {
        moves[at] = move;
        at = (at + 1) % moves.length;
        if (held < moves.length) {
            held++;
        }
    }

    /** One move drawn at random, or null while nothing has been kept. */
    public Move sample() {
        return held == 0 ? null : moves[random.nextInt(held)];
    }

    public void clear() {
        java.util.Arrays.fill(moves, null);
        at = 0;
        held = 0;
    }
}
