package io.github.ivannavas.autocraftai.mob.ai.objective.mentor;

import java.util.List;

import io.github.ivannavas.autocraftai.mob.ai.skill.Skill;

/**
 * What the mentor answered about one block: the rows it is teaching, in several tables, and the lessons
 * for each.
 *
 * <p>Several tables because a block has several halves. Which move to make is the goal table's row for
 * the stuck state; how to get past the terrain — break through, break the ceiling, stack a block, dig, go
 * round — is the passage table's row for the ground; what to make meanwhile is the crafting table's row;
 * and what to do about the surroundings as a whole — fight, wall off, hole up, climb to the sky — is the
 * tactics table's row. Most real blocks are the second kind, and a lesson planted only in the first was a
 * lesson the body never looked up when it mattered.
 *
 * <p>Carries the keys so the brain seeds the right rows even though the answer arrives a few seconds after
 * the question, by when the body may have moved on.
 *
 * <p>And two answers that are not lessons at all. Asked about an objective that has got nowhere for
 * minutes, the mentor may decide the objective is the problem — the tool is missing, the place is wrong —
 * and say so in {@link #replan()}. And when no move on any list is the way out, it may write one: a
 * {@link Skill}, which becomes a column of its layer's table and is seeded in the row it was written
 * for with the value the mentor gave it.
 *
 * @param reason         what the mentor was asked about: a body pinned, or one getting nowhere
 * @param pursuit        the folder of tables the stuck state belongs to (a {@code Pursuit} name)
 * @param state          the goal-table state key the body was stuck in
 * @param lessons        the goal moves to teach there
 * @param terrain        the passage-table key for the ground it was stuck on
 * @param passageLessons the passage moves to teach there
 * @param craftKey       the crafting table's row at the time
 * @param craftLessons   the craft choices to teach there — usually that a craft holding the body is not
 *                       worth making now
 * @param tacticKey      the tactics table's row at the time
 * @param tacticLessons  the tactics to teach there
 * @param skill          a move the mentor wrote, or null
 * @param skillProblem   why the move the mentor wrote could not be taken, or empty
 * @param replan         why the objective should be given up and the planner asked again, or empty
 */
public record Rescue(MentorAsk.Reason reason, String pursuit, String state, List<Lesson> lessons,
                     String terrain, List<Lesson> passageLessons,
                     String craftKey, List<Lesson> craftLessons,
                     String tacticKey, List<Lesson> tacticLessons,
                     String waterKey, List<Lesson> waterLessons,
                     Skill skill, String skillProblem, String replan,
                     String objective, long askedAt) {
    public Rescue {
        objective = objective == null ? "" : objective;
        reason = reason == null ? MentorAsk.Reason.BLOCK : reason;
        lessons = List.copyOf(lessons);
        passageLessons = List.copyOf(passageLessons);
        craftLessons = List.copyOf(craftLessons);
        tacticKey = tacticKey == null ? "" : tacticKey;
        tacticLessons = tacticLessons == null ? List.of() : List.copyOf(tacticLessons);
        waterKey = waterKey == null ? "" : waterKey;
        waterLessons = waterLessons == null ? List.of() : List.copyOf(waterLessons);
        skillProblem = skillProblem == null ? "" : skillProblem.strip();
        replan = replan == null ? "" : replan.strip();
    }

    public boolean isEmpty() {
        return lessons.isEmpty() && passageLessons.isEmpty() && craftLessons.isEmpty()
                && tacticLessons.isEmpty() && waterLessons.isEmpty() && skill == null && replan.isEmpty();
    }

    /** Whether this answers a stall rather than a block, which changes how its outcome is judged. */
    public boolean stalled() {
        return reason == MentorAsk.Reason.STALL;
    }

    /** Whether the mentor gave the objective up rather than teaching a way to it. */
    public boolean asksToReplan() {
        return !replan.isEmpty();
    }

    /** The lessons in a line, for the log and for reminding the mentor what it already said. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        lessons.forEach(l -> out.append(out.isEmpty() ? "" : ", ").append(l.action()).append('=')
                .append(Math.round(l.value())));
        passageLessons.forEach(l -> out.append(out.isEmpty() ? "" : ", ").append("passage ")
                .append(l.action()).append('=').append(Math.round(l.value())));
        craftLessons.forEach(l -> out.append(out.isEmpty() ? "" : ", ").append("craft ")
                .append(l.action()).append('=').append(Math.round(l.value())));
        tacticLessons.forEach(l -> out.append(out.isEmpty() ? "" : ", ").append("tactic ")
                .append(l.action()).append('=').append(Math.round(l.value())));
        waterLessons.forEach(l -> out.append(out.isEmpty() ? "" : ", ").append("water ")
                .append(l.action()).append('=').append(Math.round(l.value())));
        if (skill != null) {
            out.append(out.isEmpty() ? "" : ", ").append("skill ").append(skill.name());
        }
        if (!skillProblem.isEmpty()) {
            out.append(out.isEmpty() ? "" : ", ").append("skill refused: ").append(skillProblem);
        }
        if (asksToReplan()) {
            out.append(out.isEmpty() ? "" : ", ").append("replan: ").append(replan);
        }
        return out.toString();
    }
}
