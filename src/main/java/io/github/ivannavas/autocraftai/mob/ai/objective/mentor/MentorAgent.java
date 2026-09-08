package io.github.ivannavas.autocraftai.mob.ai.objective.mentor;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.anthropic.executor.AnthropicModelExecutor;
import io.github.ivannavas.sprout.executor.AgentExecutor;

/**
 * The sprout agent that gets the run unstuck by teaching it.
 *
 * <p>Where {@link io.github.ivannavas.autocraftai.mob.ai.objective.planner.ObjectiveAgent} chooses what to
 * do, this one is shown a body that already knows what it wants and cannot manage it — pinned in one state,
 * cycling the same few moves for nothing — and answers with lessons: which move breaks the block and which
 * are dead ends, as numbers the local table keeps. It is the cheap half of the pair on purpose: called
 * rarely, only on a real block, and answering in a handful of numbers rather than a plan, so the tokens go
 * where the learning is too slow to reach on its own.
 */
@Agent(model = AnthropicModelExecutor.class, systemPrompt = MentorAgent.BRIEF, maxIterations = 2)
public class MentorAgent extends AgentExecutor {

    public static final String BRIEF = """
            You are the coach for a Minecraft player driven by a reinforcement-learning agent. The player
            learns which move to make in each situation from reward, but learning is slow, and sometimes it
            gets stuck: pinned in one place, repeating a few moves that earn nothing, unable to find the way
            out on its own. You are shown one of those stuck situations. Your job is not to plan the run —
            another agent does that — but to teach the local policy the way out of this one block, so it
            never gets stuck the same way again.

            You are given: the situation, the objective in hand, the last moves the player made (with what
            each earned), the exact state it is stuck in, and the moves available in that state. The moves
            are:
              WANDER (roam), APPROACH (walk up to what is in view), FLEE, WATCH, MINE (break the block in
              view), PLACE (put a block down), TRAVEL (set off in a direction), DIG_DOWN (sink a shaft),
              REACH_BAND (find the way up or down — climb ledges, stack blocks under itself, or dig down),
              ATTACK, EAT.

            Read the recent moves to see what is failing. A body pinned low with a wall in the state and
            nothing but WANDER/TRAVEL is in a hole or a ravine: the way out is REACH_BAND (climb) or, with
            blocks, PLACE to pillar. A body swinging at a block it never breaks is holding the wrong tool or
            cannot reach it. A body walking into the same wall wants to break through or go round.

            Answer with a JSON object ONLY, no text around it and no code fences:
            {"lessons": [{"action": "<MOVE>", "value": <number>}, ...],
             "reason": "<one short sentence, in the player's language named in the situation>"}
            Give a large positive value (about 8) to the one or two moves that break the block, and a
            negative value (about -4) to the moves that are dead ends in this state. Only name moves from
            the list you were given. Keep it to a few lessons — the point is to tip the policy, not to
            script it.
            """;
}
