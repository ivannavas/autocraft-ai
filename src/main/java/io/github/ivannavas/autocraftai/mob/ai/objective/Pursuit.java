package io.github.ivannavas.autocraftai.mob.ai.objective;

/**
 * What the body is working on right now, as the tables see it: which set of tables, and which source
 * within that set.
 *
 * <p>The goal table used to be one file with the objective's name in every key, and the planner inventing
 * names was the end of that: {@code GET_3_LOG} and {@code GET_5_LOG} were different rows about the same
 * problem, and a row visited once teaches nothing. What a body learns about getting wood does not depend
 * on how much wood the plan asked for. It depends on the wood — and on which tree it comes off, which is
 * the {@link #source()}.
 *
 * <h2>One folder per thing, the source in the key</h2>
 * The {@link #name()} is a folder of tables: {@code LOG}, {@code IRON}, {@code GO}, {@code THREAT}. Every
 * gathering objective for the same resource shares it, however it was worded, so a lesson learned on the
 * third run is there on the thirtieth. The source is a field inside each key rather than a folder of its
 * own, so that two trees can share what they have in common and still tell each other apart where it
 * matters: an oak and a birch live in the same forest; an iron ore and a deepslate iron ore do not.
 *
 * <p>{@link #where()} is what the planner said about finding the source — see {@link Whereabouts} — and it
 * is carried here because the tables key on it and the legality masks read it, and both want the answer
 * for the source in play rather than for the objective in general.
 *
 * @param name   the folder of tables this is learned in
 * @param source the source within it, or {@link #NO_SOURCE} when there is not one
 * @param where  where that source is to be found and how the body may go about reaching it
 */
public record Pursuit(String name, String source, Whereabouts where) {

    /** What the key says when the pursuit has no source to name: the ladder's rungs, a climb, a build. */
    public static final String NO_SOURCE = "-";

    /** Between orders, with an answer on its way. */
    public static final Pursuit PLANNING = new Pursuit("IDLE", "PLANNING", Whereabouts.anywhere());
    /** Nothing left to want and nobody left to ask. */
    public static final Pursuit DONE = new Pursuit("IDLE", "DONE", Whereabouts.anywhere());

    public Pursuit {
        source = source == null || source.isBlank() ? NO_SOURCE : source;
        where = where == null ? Whereabouts.anywhere() : where;
    }

    /**
     * Something hostile in view, which is the one pursuit that is not about the objective at all.
     *
     * <p>Learned in a folder of its own and keyed by the mob, because a creeper is a creeper on every
     * objective there is; keying threats by what the run happened to be after would split one lesson
     * across every plan it ever has. What is kept from the objective is only where the body may go — the
     * plan's band and ways still apply while it deals with the mob.
     */
    public static Pursuit threat(String mob, Whereabouts where) {
        return new Pursuit("THREAT", mob, where);
    }

    /** For the overlay and the log: {@code LOG/oak_log}, or just {@code UP} when there is no source. */
    public String label() {
        return NO_SOURCE.equals(source) ? name : name + '/' + source;
    }
}
