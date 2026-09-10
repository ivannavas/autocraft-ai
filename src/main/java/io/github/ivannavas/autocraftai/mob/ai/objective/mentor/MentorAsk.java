package io.github.ivannavas.autocraftai.mob.ai.objective.mentor;

import java.util.List;

import io.github.ivannavas.autocraftai.mob.ai.objective.planner.Situation;

/**
 * Everything the mentor is told about a block: where the body is and how the run is going (the same
 * {@link Situation} the planner reads, which already carries the objective and the last few moves), the
 * folder and state key the body is stuck in, the ground it is standing on as the passage layer reads it,
 * the surroundings as the tactics layer reads them, and the moves — goal, passage and tactic — it could
 * make there, so the mentor answers in the tables' own words.
 *
 * <p>The moves listed are the ones the body may actually make in that state, not every column. The
 * first mentor was handed every move and taught {@code TRAVEL} to a body the threat rule had already
 * forbidden to travel, and {@code MINE} where mining was already forced; a lesson about a move the
 * mask has taken away is a lesson nobody consults.
 *
 * <p>The ground is the half the first mentor never had. A goal-table state says what is in view and how
 * far; it says nothing about the wall in front or the ceiling overhead, and a coach told only "it is
 * pinned near an oak log" can do no better than guess. The terrain key is exactly what the body cannot
 * get past, and it is also the row the passage table learns in — so a lesson about it lands where the
 * body will actually consult it. The surroundings are the same idea one level up: what is hostile and
 * how many, night or day, sky or roof, which is the row the tactics table learns in.
 *
 * <h2>Two kinds of trouble</h2>
 * A {@link Reason#BLOCK} is a body that has stopped moving: pinned in one place, and the question is what
 * is in its way. A {@link Reason#STALL} is the opposite and was invisible for a long time: a body that
 * moves all day and gets the objective no nearer — circling a forest it cannot find the trees in, walking
 * past the same stone without the pickaxe to break it. The situation carries how long that has gone on
 * and what the objective is short of, and the mentor may answer either with lessons, as for a block, or
 * by giving the objective up and having the planner asked again.
 *
 * <h2>And a third answer</h2>
 * When no move on any list is the way out, the mentor may write one — see
 * {@link io.github.ivannavas.autocraftai.mob.ai.skill.Skill} — and {@link #skills} is what it already
 * wrote, with how each has done, so it does not write the same one twice.
 *
 * @param reason       whether the body is pinned or merely getting nowhere
 * @param situation    the world and the run, including the recent decisions that show the rut
 * @param pursuit      the folder the stuck state lives in
 * @param stuckState   the goal-table state key it cannot get out of
 * @param actions      the goal moves it may make in that state, in the tables' vocabulary
 * @param terrain      the passage-table key for the ground it is stuck on ({@code wanted|ahead|above|tags})
 * @param terrainWords the same ground in plain words
 * @param passageMoves the passage moves it may make there
 * @param y            how high the body is, since most blocks are about height
 * @param driver       what is actually moving the body right now — which may not be the goal move at all
 * @param craftKey     the crafting table's row for the moment, for a lesson about what to make
 * @param craftMoves   the craft choice names available
 * @param tacticKey    the tactics table's row for the moment: hostiles, arms, health, cover, light, blocks
 * @param tacticWords  the same surroundings in plain words
 * @param tacticMoves  the tactics it may choose there
 * @param skills       the skills written so far and their records, one per line, or empty
 * @param skillProblem what was wrong with the last skill the mentor wrote, if it was refused
 */
public record MentorAsk(Reason reason, Situation situation, String pursuit, String stuckState,
                        List<String> actions, String terrain, String terrainWords,
                        List<String> passageMoves, int y, String driver, String craftKey,
                        List<String> craftMoves, String tacticKey, String tacticWords,
                        List<String> tacticMoves, String waterKey, List<String> waterMoves, String dwell,
                        boolean holding, String skills, String skillProblem) {

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
        tacticKey = tacticKey == null ? "" : tacticKey;
        tacticWords = tacticWords == null ? "" : tacticWords;
        tacticMoves = tacticMoves == null ? List.of() : List.copyOf(tacticMoves);
        waterKey = waterKey == null ? "" : waterKey;
        waterMoves = waterMoves == null ? List.of() : List.copyOf(waterMoves);
        dwell = dwell == null ? "" : dwell;
        skills = skills == null ? "" : skills;
        skillProblem = skillProblem == null ? "" : skillProblem;
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
                .append(" T ahead breakable with a tool it carries, t only by hand (slow, no drop), D can dig)");
        if (!tacticKey.isEmpty()) {
            text.append("\nThe surroundings as a whole: ").append(tacticWords)
                    .append("\n  (surroundings key: ").append(tacticKey)
                    .append(" = hostiles and count | nearest | A armed | health | cover | light |")
                    .append(" B many blocks, b a few, - none)");
        }
        text.append("\nWhat is driving the body right now: ").append(driver)
                .append("\nGoal moves it may make there (the rest are ruled out by the rules): ")
                .append(String.join(", ", actions))
                .append("\nPassage moves it may make there: ").append(String.join(", ", passageMoves))
                .append("\nCraft choices it can make now (row ").append(craftKey).append("): ")
                .append(String.join(", ", craftMoves));
        if (!tacticMoves.isEmpty()) {
            text.append("\nTactics it may choose (row ").append(tacticKey).append("): ")
                    .append(String.join(", ", tacticMoves));
        }
        if (!waterKey.isEmpty()) {
            text.append("\nIN THE WATER (row ").append(waterKey)
                    .append(" = how deep | how much air | where air is | where land is): the swim moves it")
                    .append(" may make: ").append(String.join(", ", waterMoves))
                    .append(". This is the block if it is: a body in the water is drowning, not stuck.");
        }
        if (!dwell.isEmpty()) {
            text.append("\nDwelling: ").append(dwell).append('.');
        }
        if (holding) {
            text.append("\nThe body is HOLDING STILL for your answer, for up to 45 seconds: nothing about it")
                    .append(" will have changed when your lessons land, so teach this spot as it stands.");
        }
        text.append("\nSkills written so far: ").append(skills.isEmpty() ? "none" : "\n" + skills);
        if (!skillProblem.isEmpty()) {
            text.append("\nThe last skill you wrote was refused: ").append(skillProblem)
                    .append(". Fix it if you write one again.");
        }
        if (stalled()) {
            text.append("\nTeach it what would get the objective moving — or, if the objective cannot be")
                    .append(" reached from here with what it has, say so with \"replan\" and the strategist")
                    .append(" will be asked for another.");
        } else {
            text.append("\nTeach it the way out of this block, and which moves are dead ends here. If no")
                    .append(" move on these lists is the way out, write the move as a skill.");
        }
        return text.toString();
    }
}
