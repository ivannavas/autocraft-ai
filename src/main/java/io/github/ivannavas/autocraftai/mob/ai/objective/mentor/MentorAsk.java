package io.github.ivannavas.autocraftai.mob.ai.objective.mentor;

import java.util.List;

import io.github.ivannavas.autocraftai.mob.ai.objective.planner.Situation;

/**
 * Everything the mentor is told about a block: where the body is and how the run is going (the same
 * {@link Situation} the planner reads, which already carries the objective and the last few moves), plus
 * the two things only a block has — which folder and state key the body is stuck in, and the moves it
 * could make there.
 *
 * @param situation the world and the run, including the recent decisions that show the rut
 * @param pursuit   the folder the stuck state lives in
 * @param stuckState the goal-table state key it cannot get out of
 * @param actions   the move names available, so the mentor answers in the vocabulary the tables use
 */
public record MentorAsk(Situation situation, String pursuit, String stuckState, List<String> actions) {

    public MentorAsk {
        actions = List.copyOf(actions);
    }

    /** One line for the log, so a reader can tell one block apart from the next. */
    public String summary() {
        return pursuit + " stuck at " + stuckState;
    }

    /** The prompt: the situation as the planner states it, then the block and the moves on offer. */
    public String describe() {
        return situation.describe()
                + "\n\nBLOCK. Working on " + pursuit + ", the body is stuck and getting nowhere in this"
                + " state:\n  " + stuckState
                + "\nThe moves it can make there: " + String.join(", ", actions)
                + "\nTeach it which move breaks the block, and which are dead ends.";
    }
}
