package io.github.ivannavas.autocraftai.mob.ai.objective.planner;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import io.github.ivannavas.autocraftai.mob.ai.objective.Plan;

/**
 * What was asked of the planner and what it said back.
 *
 * <p>The planner is the one part of the run that happens somewhere else. Everything else the overlay shows
 * is a number the brain worked out in front of you; this is a question that left the machine and an answer
 * that came back a few seconds later, and without a record of it a viewer has no way to tell a planner that
 * is thinking from one that is broken, or a run of objectives that were reasoned about from a fallback
 * ladder quietly doing its job.
 *
 * <p>So both halves are kept, in the order they happened: the call, with a line of the situation that
 * prompted it and the whole prompt behind it, and then the reply — an objective, a decision to leave the
 * objective alone, or the reason there is no answer at all.
 *
 * <p>Written from two threads, which is the reason for the lock: the question is recorded on the game
 * thread as the request goes out, and the answer on the planner's own thread when it lands.
 */
public final class PlannerLog {

    /** Enough to see the last few exchanges without the panel turning into a transcript. */
    /**
     * How much of the conversation is kept.
     *
     * <p>Generous, because this is the record of every objective the run was given and why, and it is
     * read by someone trying to understand a session after the fact — a window of the last handful
     * answers "what is it doing now", which the panel already shows, and nothing else. The entries are
     * a few kilobytes each and they are never sent with the per-decision snapshot, so the cost of
     * keeping them is memory nobody misses.
     */
    private static final int KEEP = 200;

    private static final PlannerLog INSTANCE = new PlannerLog();

    private final Deque<Entry> entries = new ArrayDeque<>();

    private PlannerLog() {
    }

    public static PlannerLog get() {
        return INSTANCE;
    }

    /** Which half of an exchange an entry is, and how it went. */
    public enum Kind {
        /** A question going out. */
        ASKED,
        /** An objective coming back. */
        ANSWERED,
        /** The planner looked and left the objective alone. */
        KEPT,
        /** No answer: no network, a refusal, or a reply that was not an objective. */
        FAILED
    }

    /**
     * One line of the conversation.
     *
     * @param at        when it happened, so the page can age it
     * @param kind      which half, and how it went
     * @param objective the objective's key when the entry is about one, so the panel can name it in the
     *                  player's own language rather than in the one this code is written in; else empty
     * @param text      the rest of the line, which is the planner's own sentence and stays as it wrote it
     * @param detail    the whole prompt or the whole reply, shown on request rather than up front
     * @param plan      the plan an answer amounted to, as the run adopted it — the band, the shopping
     *                  list, the reserve, the sources and where each is found — or null for every other
     *                  kind of entry. The reply says the same in the model's words; this is what the run
     *                  actually took from them, which is the half a viewer cannot check by reading.
     */
    public record Entry(long at, Kind kind, String objective, String text, String detail, Plan plan) {
    }

    /** A question going out, with the line of situation that goes with it. */
    public void asked(String summary, String prompt) {
        add(new Entry(System.currentTimeMillis(), Kind.ASKED, "", summary, prompt, null));
    }

    /** An objective coming back, with the whole plan the run made of it. */
    public void answered(Plan plan, String reason, String reply) {
        add(new Entry(System.currentTimeMillis(), Kind.ANSWERED, plan.objective().name(), reason, reply,
                plan));
    }

    public void kept(String reason, String reply) {
        add(new Entry(System.currentTimeMillis(), Kind.KEPT, "", reason, reply, null));
    }

    public void failed(String why, String detail) {
        add(new Entry(System.currentTimeMillis(), Kind.FAILED, "", why, detail, null));
    }

    /**
     * Bumped on every entry, and never reset.
     *
     * <p>What the page watches to know the log has moved. A count would not do: once the log is full
     * it stops changing, and the history would silently stop updating at exactly the point there is
     * most of it.
     */
    private volatile long revision;

    /** @return a number that changes whenever anything was added, for spotting a stale copy */
    public long revision() {
        return revision;
    }

    private synchronized void add(Entry entry) {
        revision++;
        entries.addLast(entry);
        while (entries.size() > KEEP) {
            entries.removeFirst();
        }
    }

    /** The exchanges so far, oldest first. A copy: the web server's threads read it. */
    public synchronized List<Entry> recent() {
        return List.copyOf(entries);
    }

    public synchronized void clear() {
        entries.clear();
    }
}
