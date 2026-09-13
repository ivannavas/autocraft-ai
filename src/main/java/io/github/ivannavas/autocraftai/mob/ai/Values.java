package io.github.ivannavas.autocraftai.mob.ai;

import java.nio.file.Path;
import java.util.List;

/**
 * What a layer believes each of its moves is worth, however it happens to hold that belief.
 *
 * <p>There were two ideas tangled together in {@link QTable}: <em>what</em> is learned — the semi-Markov
 * update, the legality mask, the per-row exploration rate, the priors a skill is seeded with — and
 * <em>how</em> it is stored, which was one row of doubles per state key. The first is the design; the
 * second was only ever the simplest thing that could hold it, and it is the reason a body that has spent
 * an hour learning about an oak log knows nothing at all about a birch one. Two keys that differ in a
 * single field are two unrelated rows, and with fifteen to twenty-eight decisions a minute there are never
 * enough visits to fill them all in.
 *
 * <p>So the store is an interface and there are two of them. {@link QTable} is the one that has always
 * been here, unchanged in what it does and still written out as a file a person can read. {@link QNetwork}
 * is the same learning rule over a small network, which spreads what one key teaches to every key that
 * looks like it, and which keeps the experience it is shown so that one decision can be learned from many
 * times rather than once. Which one a run uses is {@code learning.brain} in {@code control.properties};
 * nothing else about the brain knows the difference.
 *
 * <p>Everything here is called from the game thread and from nowhere else, except {@link #rows} which the
 * overlay's thread reads through a copy.
 */
public interface Values {

    /**
     * Epsilon-greedy pick among the legal moves.
     *
     * @return the chosen column, or -1 when nothing is legal
     */
    int choose(String state, boolean[] allowed);

    /**
     * One learning step with a successor to bootstrap from.
     *
     * @param reward everything the move earned across its whole run
     * @param steps  how many decisions it ran for, which is what the discount is raised to
     */
    void update(String state, int action, double reward, int steps, String nextState, boolean[] nextAllowed);

    /** One learning step for a move with no successor, such as the one the body died on. */
    void updateTerminal(String state, int action, double reward);

    /** Plants a value outright, as a lesson taught rather than a reward earned. */
    void seed(String state, int action, double value);

    /** Moves one belief by a delta: credit that arrived after the fact, such as a death traced back. */
    void nudge(String state, int action, double delta);

    /** What a column is worth in a state nothing is known about yet — a skill's prior. */
    void initial(int column, double value);

    /** Gives the store another column: a skill just written. */
    void resize(int columns);

    /** The width from now on. Narrower only while nothing is held. */
    void width(int columns);

    /** Takes a column out, closing the gap: a skill let go from the book. */
    void dropColumn(int index);

    /** A store this one starts from and reports into, where the store has such a notion. */
    default void inherit(Values parent) {
    }

    /** The exploration rate of the row last chosen from, for the overlay. */
    double epsilon();

    /** How many choices have been made. */
    long decisions();

    /** How many distinct states have been seen. */
    int states();

    /**
     * Every state and what is believed about it, copied out for the overlay's thread.
     *
     * @param prefix when a store is shared between folders, the folder's own rows and no others; the
     *               prefix is stripped from the keys, so the page renders the same row it always did
     */
    List<QTableSnapshot.Row> rows(String prefix);

    /** Everything learned, thrown away. */
    void clear();

    void load(Path path, List<String> columns);

    void save(Path path, List<String> columns);
}
