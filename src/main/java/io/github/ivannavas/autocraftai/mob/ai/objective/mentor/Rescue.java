package io.github.ivannavas.autocraftai.mob.ai.objective.mentor;

import java.util.List;

/**
 * What the mentor answered about one block: the folder and state it is teaching, and the lessons to plant
 * there.
 *
 * <p>Carries the pursuit and the state key so the brain seeds the right table at the right row even though
 * the answer arrives a few seconds after the question, by when the body may have moved on. An empty lesson
 * list is a mentor that looked and had nothing to add.
 *
 * @param pursuit the folder of tables the stuck state belongs to (a {@code Pursuit} name)
 * @param state   the goal-table state key the body was stuck in
 * @param lessons the moves to teach there
 */
public record Rescue(String pursuit, String state, List<Lesson> lessons) {
    public Rescue {
        lessons = List.copyOf(lessons);
    }
}
