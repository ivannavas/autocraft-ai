package io.github.ivannavas.autocraftai.mob.ai.objective.mentor;

import java.util.List;

/**
 * What the mentor answered about one block: the rows it is teaching, in two tables, and the lessons for
 * each.
 *
 * <p>Two tables because a block has two halves. Which move to make is the goal table's row for the stuck
 * state; how to get past the terrain — break through, break the ceiling, stack a block, dig, go round — is
 * the passage table's row for the ground. Most real blocks are the second kind, and a lesson planted only
 * in the first was a lesson the body never looked up when it mattered.
 *
 * <p>Carries the keys so the brain seeds the right rows even though the answer arrives a few seconds after
 * the question, by when the body may have moved on.
 *
 * @param pursuit        the folder of tables the stuck state belongs to (a {@code Pursuit} name)
 * @param state          the goal-table state key the body was stuck in
 * @param lessons        the goal moves to teach there
 * @param terrain        the passage-table key for the ground it was stuck on
 * @param passageLessons the passage moves to teach there
 * @param craftKey       the crafting table's row at the time
 * @param craftLessons   the craft choices to teach there — usually that a craft holding the body is not
 *                       worth making now
 */
public record Rescue(String pursuit, String state, List<Lesson> lessons,
                     String terrain, List<Lesson> passageLessons,
                     String craftKey, List<Lesson> craftLessons) {
    public Rescue {
        lessons = List.copyOf(lessons);
        passageLessons = List.copyOf(passageLessons);
        craftLessons = List.copyOf(craftLessons);
    }

    public boolean isEmpty() {
        return lessons.isEmpty() && passageLessons.isEmpty() && craftLessons.isEmpty();
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
        return out.toString();
    }
}
