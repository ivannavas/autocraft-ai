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
public record MentorAsk(Situation situation, String pursuit, String stuckState, List<String> actions,
                        String terrain, String terrainWords, List<String> passageMoves, int y,
                        String driver, String craftKey, List<String> craftMoves) {

    public MentorAsk {
        actions = List.copyOf(actions);
        passageMoves = List.copyOf(passageMoves);
        craftMoves = List.copyOf(craftMoves);
    }

    /** One line for the log, so a reader can tell one block apart from the next. */
    public String summary() {
        return pursuit + " stuck at " + stuckState + " on " + terrain;
    }

    /** The prompt: the situation as the planner states it, then the block, the ground and the moves. */
    public String describe() {
        return situation.describe()
                + "\n\nBLOCK. Working on " + pursuit + " at Y " + y + ", the body has not moved for a while"
                + " and is stuck in this state:\n  " + stuckState
                + "\n  (state key: source | what is in view | how far | health | flags: W walled in,"
                + " B carrying blocks, H hungry)"
                + "\nThe ground where it stands: " + terrainWords
                + "\n  (terrain key: " + terrain + " = wanted | ahead | overhead | tags: B blocks,"
                + " T ahead breakable, D can dig)"
                + "\nWhat is driving the body right now: " + driver
                + "\nGoal moves it can make there: " + String.join(", ", actions)
                + "\nPassage moves it can make there: " + String.join(", ", passageMoves)
                + "\nCraft choices it can make now (row " + craftKey + "): " + String.join(", ", craftMoves)
                + "\nTeach it the way out of this block, and which moves are dead ends here.";
    }
}
