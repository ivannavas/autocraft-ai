package io.github.ivannavas.autocraftai.mob.ai;

import java.util.OptionalDouble;

import net.minecraft.core.BlockPos;

/**
 * What was decided about a move after it was chosen: which block it acts on, and which way it goes.
 *
 * <p>Neither is part of the situation the move was picked from, because neither question exists until
 * there is a verb. "Which block" means nothing until something is going to be done to one, and "which way"
 * means nothing until the body is going somewhere. So they are not on the {@link ActionContext}; they are
 * what the placement and position tables answer once the goal table has spoken, and this is how the answers
 * reach the goal that carries them out.
 *
 * <p>Both are usually empty. Most moves take neither, and a move handed neither does exactly what it did
 * before either table existed.
 *
 * @param spot    the block a mine or a build acts on, or null
 * @param heading the yaw to set off on, in radians, or empty
 */
public record Aim(BlockPos spot, OptionalDouble heading) {

    private static final Aim NOWHERE = new Aim(null, OptionalDouble.empty());

    public Aim {
        heading = heading == null ? OptionalDouble.empty() : heading;
    }

    /** For the moves that need no answer from either table. */
    public static Aim nowhere() {
        return NOWHERE;
    }

    public static Aim at(BlockPos spot) {
        return new Aim(spot, OptionalDouble.empty());
    }

    public static Aim towards(OptionalDouble heading) {
        return new Aim(null, heading);
    }
}
