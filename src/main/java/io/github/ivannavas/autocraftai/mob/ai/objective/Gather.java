package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    public Map<Resource, Integer> needs() {
        return Map.of(resource, amount);
    }

    /**
     * Everything it gathers, held back.
     *
     * <p>Not a precaution: it is what the objective means. {@link #isComplete} asks whether the body is
     * <em>holding</em> this much, so anything that spends one takes the run backwards, and the commonest
     * way to spend one is the crafting table turning it into the next thing up the chain. The planner may
     * add to this; it cannot take it away, because taking it away would leave an objective that undoes
     * itself.
     */
    @Override
    public Map<Resource, Integer> reserved() {
        return Map.of(resource, amount);
    }

    /** Told where the resource comes from, seeing one of those blocks is not a decision. */
    @Override
    public boolean minesWhatItSees() {
        return true;
    }

    @Override
    public Optional<Resource> scores() {
        return Optional.of(resource);
    }

    @Override
    public boolean wantsDepth() {
        return resource.underground();
    }

    @Override
    public String shape() {
        return "GET";
    }

    @Override
    public String name() {
        return "GET_" + amount + "_" + resource.name();
    }

    /**
     * Reached when the body is holding this much. Not when it once did.
     *
     * <p>This used to be a running total of everything the run had ever picked up, because measuring the
     * bag made the objective unreachable: the crafting table turned logs into planks as fast as they were
     * cut, so three logs were never in hand at once. That fixed the symptom and kept the nonsense — an
     * objective that reports success over resources that have already been spent has not been met, it has
     * been accounted around. What the plan wanted was three logs, and the run does not have three logs.
     *
     * <p>What makes holding them achievable is the other half of this class: {@link #score} now charges
     * for the objective's resource leaving the bag, so a craft that eats what the plan is after is a move
     * the crafting table can learn not to make. The running total is still kept and still shown to the
     * planner, because "has had thirty logs and holds none" is worth knowing — it is simply not the same
     * thing as being done.
     */
    @Override
    public boolean isComplete(StepContext context) {
        return context.after().count(resource) >= amount;
    }

    /**
     * Two terms: net progress towards the thing, less whatever was made that the plan has no use for.
     *
     * <p>The first is symmetric. What turned up pays and what left costs, at the same rate, because a log
     * that leaves the bag is a log the objective no longer has. There is no extra sting on the leaving:
     * the time it took is already charged by the standing cost of a decision, and pricing the undoing
     * above the doing would be a number picked to make a point.
     *
     * <p>The second is about the craft itself. Making something this plan cannot use costs what it was
     * worth making — a sword is ten points of work that leaves the run no nearer a pickaxe. Planks on the
     * way to a pickaxe cost nothing, because planks are what a pickaxe is made of; see
     * {@link Resource#contributesTo}.
     *
     * <p>Both can land on one craft, and when they do the craft is worth less than not bothering. Turning
     * a log into planks while the plan wants logs loses the log (four) and gains planks the plan cannot
     * use (two): six against the four that cutting the log paid, so the whole round trip is a loss. That
     * is the point — it was not a neutral detour, it undid the work.
     */
    @Override
    public double score(StepContext context) {
        double towards = context.netChange(resource) * resource.worth();
        double astray = context.crafted().stream()
                .filter(made -> !made.contributesTo(resource))
                .mapToDouble(Resource::worth)
                .sum();
        return towards - astray;
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
