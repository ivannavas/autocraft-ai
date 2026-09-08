package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One place a resource comes from: a block, what to break it with, and where to look for it.
 *
 * <p>This is the planner telling the body where to look. "Get three logs" leaves the body to work out for
 * itself that logs come off {@code oak_log} and that punching them works; "get three logs, from
 * {@code oak_log} or {@code birch_log}, with an axe" is the same objective with the search already done.
 * The eyes then look for exactly those blocks, and the hand holds the right thing before it swings.
 *
 * <p>A list rather than one, because most things come from more than one place — logs from six kinds of
 * tree, iron from ore in stone and in deepslate — and a body told to look for only the first will walk
 * past the others.
 *
 * <h2>The one open vocabulary</h2>
 * Everything else the planner may say is a closed list this code defines. Blocks are not: there are a
 * thousand of them, they change every version, and the model knows their names better than any list here
 * would. So the name is looked up in the game's own registry and an unknown one is simply dropped — which
 * is the same check a closed list performs, done against the authority instead of against a copy of it.
 *
 * <h2>And where it is</h2>
 * {@link #where()} is the planner saying how to find the block as well as what it is: the heights it lives
 * at, the kinds of place it is common in, and how the body may move to get there. The tables for the
 * resource key on it and the legality masks read it — see {@link Whereabouts}. A source the planner named
 * without saying gets what the run always assumed about the resource, so nothing downstream has to ask
 * whether it was told.
 *
 * @param where where the block is to be found, never null
 */
public record Source(Block block, Tool tool, Whereabouts where) {

    public Source {
        tool = tool == null ? Tool.HAND : tool;
        where = where == null
                ? Whereabouts.of(Resource.yieldedBy(block.defaultBlockState()).orElse(null)) : where;
    }

    /** A source with only what the run always assumed about where its resource is. */
    public Source(Block block, Tool tool) {
        this(block, tool, null);
    }

    /**
     * The source named by a block id, or empty if no such block exists.
     *
     * @param id   a block id, with or without the {@code minecraft:} namespace
     * @param tool what to break it with; when null, whatever the game says
     */
    public static Optional<Source> of(String id, Tool tool) {
        return of(id, tool, null);
    }

    /**
     * The source named by a block id, with the planner's word on where to find it.
     *
     * @param where where the block lives and how to reach it; when null, what the run always assumed
     */
    public static Optional<Source> of(String id, Tool tool, Whereabouts where) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(Identifier.tryParse(id.strip().toLowerCase(Locale.ROOT)))
                .flatMap(BuiltInRegistries.BLOCK::getOptional)
                .map(block -> new Source(block,
                        tool == null ? Tool.bestFor(block.defaultBlockState()) : tool, where));
    }

    /** The same block and tool, with this said about where it is. */
    public Source with(Whereabouts where) {
        return new Source(block, tool, where);
    }

    /** The block's own name without its namespace — {@code oak_log} — which is how it appears in a key. */
    public String name() {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    public boolean matches(BlockState state) {
        return state.is(block);
    }

    @Override
    public String toString() {
        return BuiltInRegistries.BLOCK.getKey(block).getPath()
                + (tool == Tool.HAND ? "" : " (" + tool.name().toLowerCase(Locale.ROOT) + ")");
    }
}
