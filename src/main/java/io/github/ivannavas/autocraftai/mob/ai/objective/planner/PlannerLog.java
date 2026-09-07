package io.github.ivannavas.autocraftai.mob.ai.objective.planner;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

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
    private static final int KEEP = 8;

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
     * @param detail    the whole prompt or the whole reply, for a tooltip; never shown up front
     */
    public record Entry(long at, Kind kind, String objective, String text, String detail) {
    }

    /** A question going out, with the line of situation that goes with it. */
    public void asked(String summary, String prompt) {
        add(new Entry(System.currentTimeMillis(), Kind.ASKED, "", summary, prompt));
    }

    public void answered(String objective, String reason, String reply) {
        add(new Entry(System.currentTimeMillis(), Kind.ANSWERED, objective, reason, reply));
    }

    public void kept(String reason, String reply) {
        add(new Entry(System.currentTimeMillis(), Kind.KEPT, "", reason, reply));
    }

    public void failed(String why, String detail) {
        add(new Entry(System.currentTimeMillis(), Kind.FAILED, "", why, detail));
    }

    private synchronized void add(Entry entry) {
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
