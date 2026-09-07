package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One place a resource comes from: a block, and what to break it with.
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
 */
public record Source(Block block, Tool tool) {

    public Source {
        tool = tool == null ? Tool.HAND : tool;
    }

    /**
     * The source named by a block id, or empty if no such block exists.
     *
     * @param id   a block id, with or without the {@code minecraft:} namespace
     * @param tool what to break it with; when null, whatever the game says
     */
    public static Optional<Source> of(String id, Tool tool) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(Identifier.tryParse(id.strip().toLowerCase(Locale.ROOT)))
                .flatMap(BuiltInRegistries.BLOCK::getOptional)
                .map(block -> new Source(block, tool == null ? Tool.bestFor(block.defaultBlockState()) : tool));
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
