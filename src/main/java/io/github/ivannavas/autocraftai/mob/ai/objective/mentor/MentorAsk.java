package io.github.ivannavas.autocraftai.mob.ai.objective.mentor;

import java.util.List;

import io.github.ivannavas.autocraftai.mob.ai.objective.planner.Situation;

/**
 * Everything the mentor is told about a block: where the body is and how the run is going (the same
 * {@link Situation} the planner reads, which already carries the objective and the last few moves), the
 * folder and state key the body is stuck in, the ground it is standing on as the passage layer reads it,
 * and the moves — goal and passage — it could make there, so the mentor answers in the tables' own words.
 *
 * <p>The ground is the half the first mentor never had. A goal-table state says what is in view and how
 * far; it says nothing about the wall in front or the ceiling overhead, and a coach told only "it is
 * pinned near an oak log" can do no better than guess. The terrain key is exactly what the body cannot
 * get past, and it is also the row the passage table learns in — so a lesson about it lands where the
 * body will actually consult it.
 *
 * <h2>Two kinds of trouble</h2>
 * A {@link Reason#BLOCK} is a body that has stopped moving: pinned in one place, and the question is what
 * is in its way. A {@link Reason#STALL} is the opposite and was invisible for a long time: a body that
 * moves all day and gets the objective no nearer — circling a forest it cannot find the trees in, walking
 * past the same stone without the pickaxe to break it. The situation carries how long that has gone on
 * and what the objective is short of, and the mentor may answer either with lessons, as for a block, or
 * by giving the objective up and having the planner asked again.
 *
 * @param reason       whether the body is pinned or merely getting nowhere
 * @param situation    the world and the run, including the recent decisions that show the rut
 * @param pursuit      the folder the stuck state lives in
 * @param stuckState   the goal-table state key it cannot get out of
 * @param actions      the goal move names available, in the tables' vocabulary
 * @param terrain      the passage-table key for the ground it is stuck on ({@code wanted|ahead|above|tags})
 * @param terrainWords the same ground in plain words
 * @param passageMoves the passage move names available
 * @param y            how high the body is, since most blocks are about height
 * @param driver       what is actually moving the body right now — which may not be the goal move at all
 * @param craftKey     the crafting table's row for the moment, for a lesson about what to make
 * @param craftMoves   the craft choice names available
 */
public record MentorAsk(Reason reason, Situation situation, String pursuit, String stuckState,
                        List<String> actions, String terrain, String terrainWords,
                        List<String> passageMoves, int y, String driver, String craftKey,
                        List<String> craftMoves) {

    /** Why the mentor is being asked. */
    public enum Reason {
        /** The body has not moved for a while: something is in its way. */
        BLOCK,
        /** The body moves and the objective gets no nearer, for minutes on end. */
        STALL
    }

    public MentorAsk {
        reason = reason == null ? Reason.BLOCK : reason;
        actions = List.copyOf(actions);
        passageMoves = List.copyOf(passageMoves);
        craftMoves = List.copyOf(craftMoves);
    }

    public boolean stalled() {
        return reason == Reason.STALL;
    }

    /**
     * What one asking is about, for not asking it twice.
     *
     * <p>A block is a state: the same state again is the same block. A stall is an objective: the body
     * passes through every state there is while it circles, and keyed by state a stall would be a fresh
     * question every minute for as long as it lasted.
     */
    public String key() {
        return stalled()
                ? pursuit + "|stall|" + situation.objective()
                : pursuit + '|' + stuckState;
    }

    /** One line for the log, so a reader can tell one block apart from the next. */
    public String summary() {
        if (stalled()) {
            return pursuit + " stalled " + situation.minutesWithoutProgress() + " min on "
                    + situation.objective() + " at " + stuckState;
        }
        return pursuit + " stuck at " + stuckState + " on " + terrain;
    }

    /** The prompt: the situation as the planner states it, then the block, the ground and the moves. */
    public String describe() {
        StringBuilder text = new StringBuilder(situation.describe());
        if (stalled()) {
            text.append("\n\nSTALL. Working on ").append(pursuit).append(" at Y ").append(y)
                    .append(", the body is not pinned — it moves — but the objective has got no nearer for ")
                    .append(situation.minutesWithoutProgress()).append(" minutes. ")
                    .append(situation.readiness())
                    .append("Its state as it stands now:\n  ").append(stuckState);
        } else {
            text.append("\n\nBLOCK. Working on ").append(pursuit).append(" at Y ").append(y)
                    .append(", the body has not moved for a while and is stuck in this state:\n  ")
                    .append(stuckState);
        }
        text.append("\n  (state key: source | what is in view | how far | health | flags: W walled in,")
                .append(" B carrying blocks, H hungry)")
                .append("\nThe ground where it stands: ").append(terrainWords)
                .append("\n  (terrain key: ").append(terrain).append(" = wanted | ahead | overhead | tags: B blocks,")
                .append(" T ahead breakable, D can dig)")
                .append("\nWhat is driving the body right now: ").append(driver)
                .append("\nGoal moves it can make there: ").append(String.join(", ", actions))
                .append("\nPassage moves it can make there: ").append(String.join(", ", passageMoves))
                .append("\nCraft choices it can make now (row ").append(craftKey).append("): ")
                .append(String.join(", ", craftMoves));
        if (stalled()) {
            text.append("\nTeach it what would get the objective moving — or, if the objective cannot be")
                    .append(" reached from here with what it has, say so with \"replan\" and the strategist")
                    .append(" will be asked for another.");
        } else {
            text.append("\nTeach it the way out of this block, and which moves are dead ends here.");
        }
        return text.toString();
    }
}
