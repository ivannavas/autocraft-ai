package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Go and get N of something.
 *
 * <p>The oldest of the objective shapes and still the commonest: everything the run makes and most of what
 * it does is in service of having enough of something. Completion is a count, which is what makes it the
 * easiest shape to be sure about.
 *
 * <h2>And where to find it</h2>
 * The objective may also say which blocks the thing comes off and what to break them with — see
 * {@link Source}. It is optional and it changes nothing about what the objective <em>is</em>; what it
 * changes is how long the body spends discovering it. Told to get cobblestone, a body has to learn from
 * scratch that stone is the block and a pickaxe is the tool, and it learns that by swinging its fists at
 * stone for nothing. Told "cobblestone, from stone or deepslate, with a pickaxe", it looks for the right
 * block and holds the right thing on the first swing.
 *
 * <p>With no sources given it falls back to the resource's own idea of what it looks like in the world,
 * which is what the fixed ladder has always used.
 */
public record Gather(Resource resource, int amount, List<Source> sources, String reason) implements Phase {

    /** The most of one thing worth asking for in a single objective — a stack, and not more. */
    public static final int MOST = 64;

    public Gather {
        amount = Math.max(1, Math.min(MOST, amount));
        sources = sources == null ? List.of() : List.copyOf(sources);
        reason = reason == null ? "" : reason.strip();
    }

    /** A gathering objective with nothing to say for itself, which is what the fallback ladder's rungs are. */
    public static Gather of(Resource resource, int amount) {
        return new Gather(resource, amount, List.of(), "");
    }

    @Override
    public String name() {
        return "GET_" + amount + "_" + resource.name();
    }

    /**
     * Reached once the run has held this much at any point, not only while it still is.
     *
     * <p>Measuring the bag as it stands made the first objective unreachable in practice: the crafting
     * table turns logs into planks as soon as it can, so the log count never got to three at once and
     * everything downstream stayed frozen behind it — a body with a sword and a crafting table still being
     * told to go and find wood.
     */
    @Override
    public boolean isComplete(StepContext context) {
        return context.obtained().count(resource) >= amount;
    }

    /** Paid per unit picked up, so the objective pays on the way and not only at the end. */
    @Override
    public double score(StepContext context) {
        return context.gained(resource) * resource.worth();
    }

    /** Whichever blocks the planner named, or failing that whatever the resource looks like in the world. */
    @Override
    public Optional<Predicate<BlockState>> wanted() {
        if (sources.isEmpty()) {
            return resource.inWorld();
        }
        return Optional.of(state -> sources.stream().anyMatch(source -> source.matches(state)));
    }

    /** What the planner said to break this block with, or nothing if it did not mention this block. */
    @Override
    public Optional<Tool> toolFor(BlockState state) {
        return sources.stream()
                .filter(source -> source.matches(state))
                .map(Source::tool)
                .findFirst();
    }

    @Override
    public String toString() {
        String what = String.format(Locale.ROOT, "%d x %s", amount, resource.name().toLowerCase(Locale.ROOT));
        return sources.isEmpty() ? what : what + " from " + sources;
    }
}
