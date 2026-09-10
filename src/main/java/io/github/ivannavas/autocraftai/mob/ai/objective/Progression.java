package io.github.ivannavas.autocraftai.mob.ai.objective;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;

import io.github.ivannavas.autocraftai.mob.ai.FocusKind;
import io.github.ivannavas.autocraftai.mob.ai.Recipes;
import io.github.ivannavas.autocraftai.mob.ai.Sighting;
import io.github.ivannavas.autocraftai.mob.goal.CraftAtTableGoal;
import io.github.ivannavas.autocraftai.mob.goal.SmeltGoal;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.ClaudePlanner;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.ObjectivePlanner;
import io.github.ivannavas.autocraftai.mob.ai.objective.planner.Situation;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What the run is after, and what that is worth.
 *
 * <p>One objective at a time. Reaching it pays a lump sum on top of whatever it was paying along the way,
 * and that lump is what makes the difference between an aimless body and one with a direction: it is the
 * only reward large enough to be worth a long detour for.
 *
 * <h2>The objectives are decided as the run goes</h2>
 * There is no fixed list any more. When the run has no objective — on arriving in a world, and every time
 * one is reached — the {@link ObjectivePlanner} is asked for the next one, and it is asked with the
 * situation the body is actually in: the biome, the bag, the time of day, what the run has already got
 * hold of. That is
 * the whole point of asking rather than scripting. A script cannot know it is midnight in a cave with two
 * hearts left, and so it asks for stone anyway; and a script that only knows how to want <em>things</em>
 * cannot tell a body standing in a desert that the problem is the desert.
 *
 * <p>Asking goes over the network and the game thread cannot wait for it, so between the question and the
 * answer the run has no objective and says so: {@link #stateKey()} reads {@code PLANNING}, nothing is
 * wanted from the landscape, and the tables get a state of their own for being between orders. It lasts a
 * few seconds.
 *
 * <h2>And nothing underneath</h2>
 * The planner is the only source of objectives. With no key, no network, or a reply that will not parse,
 * the run has no objective and says so — {@link #stateKey()} reads {@code UNPLANNED} and {@link #reason()}
 * carries the planner's own account of why — until the planner can be asked again. There used to be a
 * fixed ladder here for that case, and it did more harm than the wait it saved: a question the planner had
 * merely deferred read as a question nobody would answer, and a run three objectives in would drop back
 * to "get wood" with a bag full of planks. Better to stand still and say why than to work to a plan
 * nobody made.
 *
 * <h2>And an objective that drags on gets looked at again</h2>
 * An objective is a guess about what is worth doing, made from a situation that has since moved on. Most
 * of them are fine; the ones that are not tend to be spectacular. A body that falls into a hole while it is
 * after wood will stay after wood at the bottom of that hole for ever, because there is no tree down there
 * and nothing about "get three logs" says anything about climbing out.
 *
 * <p>So after {@link #REVIEW_AFTER_STEPS} on the same objective the planner is asked again — this time
 * with the objective it set and the last few moves the body made, which is where a rut is visible — and it
 * either replaces the objective or says to keep it. Not more often than that: it is a call over the
 * network, and one every couple of minutes is a diagnosis, while one every ten seconds is a nervous tic.
 *
 * <p>Completion is read from what the run has obtained rather than remembered, so a session that starts
 * with wood already in the bag climbs straight past the objectives it has covered — several at once, which
 * is why advancing is a loop and not an if.
 */
@Slf4j
public final class Progression {

    /** Paid once, on reaching an objective. Deliberately large: this is the point of the whole run. */
    private static final double ADVANCE_BONUS = 25.0;

    /**
     * Paid once for getting somewhere that has what the plan needs, at a height the plan wants.
     *
     * <p>Worth about two logs: enough that crossing a valley to the right forest beats wandering, and not
     * so much that finding the same forest twice is a living.
     */
    private static final double ARRIVAL_BONUS = 8.0;

    /** Key used while the planner is being waited on. */
    private static final String PLANNING = "PLANNING";

    /** Key used when nobody is going to answer for now, and nothing stands in for the planner. */
    private static final String UNPLANNED = "UNPLANNED";

    /**
     * How long an objective may run before the planner is asked whether it is still the right one.
     *
     * <p>Five minutes. Long enough that an objective which is simply taking a while — crossing a desert to
     * find trees — is left to get on with it, and short enough that a body which has fallen down a hole is
     * not still down there when the session ends.
     */
    private static final int REVIEW_AFTER_STEPS = 520;

    /**
     * How long a body that has stopped moving at all waits before the planner is asked about it.
     *
     * <p>Much shorter than the ordinary window, because it is a different question. An objective that is
     * taking a while might still be working; a body that has not moved in a minute is not working on
     * anything, and the plan it is failing at is usually the reason. This is the hole case: the objective
     * is fine and unreachable from where the body is, and nothing about pursuing it says "climb out".
     */
    private static final int REVIEW_WHEN_STUCK_STEPS = 60;

    /**
     * How long an objective short of something the bag cannot make is left before the planner is asked
     * about it.
     *
     * <p>A minute rather than the ordinary window, because the shortage was visible from the first
     * decision and nothing the body can do here will end it: two logs short of the planks, with no
     * tree in the plan, is a body that has to be sent for logs, and "8 x planks" sat for nine minutes
     * while the stall clock ran down before anyone said so. Once per objective — the planner may answer
     * that the objective stands, and it is not asked the same thing every minute after.
     */
    private static final int REVIEW_WHEN_SHORT_STEPS = 60;

    /**
     * Paid once per item on the shopping list, the moment the bag first holds as many as the plan said
     * it would need — for everything but the objective's own item, which the objective pays for itself.
     *
     * <p>This is what anticipating is worth. The list already pays per unit as they come in; this is the
     * lump for having got there, so that stocking up on what the <em>next</em> step will need reads as an
     * achievement of its own rather than as a slightly better way of passing the time. About a log and a
     * half: enough to be worth a detour for, not enough to be worth more than the objective.
     */
    private static final double READY_BONUS = 5.0;
    /**
     * Paid once, on taking an objective the run is already equipped for: every prerequisite on its list
     * in the bag and the tool its sources want in the hotbar. The reward for having anticipated, landing
     * on whatever move set the run up that way.
     */
    private static final double PREPARED_BONUS = 8.0;
    /**
     * Charged once, on taking an objective whose sources cannot be broken by anything the body carries.
     * A pursuit the body cannot complete is not a slow start, it is a mistake already made — and the
     * charge lands on the move that left the run unprepared for it.
     */
    private static final double UNPREPARED_PENALTY = 5.0;
    /**
     * Per second spent on an objective whose blocks will not drop for anything in the hotbar.
     *
     * <p>Wasted effort already charges for the swings; this charges for the seconds either side of them,
     * so that a body standing at the stone without a pickaxe is losing something every second it is not
     * making one — and the crafting table, which learns from the same reward, finds the pickaxe worth
     * making sooner. Small, because it is charged whatever the move is, and a standing charge that is
     * large teaches the timing table to commit short rather than the crafting table to craft.
     */
    private static final double UNREADY_COST = 0.2;
    /**
     * Blocks of net distance from where a journey began that count as the journey getting somewhere.
     *
     * <p>A journey has nothing to count — there is no being nearer a biome you have not found — so what
     * stands in for progress is new ground: the furthest the body has ever been from where the objective
     * was set, in steps of this many blocks. A body circling never sets a new record, which is exactly
     * the case the count exists to catch.
     */
    private static final double TRAVEL_PROGRESS_BLOCKS = 8.0;

    private final ObjectivePlanner planner;
    private final List<String> achieved = new ArrayList<>();
    /** How many times the body has died in this world, and what the server said did it last. */
    private int deaths;
    private String lastDeath = "";
    /** The mentor's word on why the last objective was given up, carried into the next question. */
    private String mentorNote = "";
    /** Where the body was when the objective in hand was taken, for measuring a journey's progress. */
    private Vec3 startedAt;
    /** The furthest along the objective the run has been, and how long since it last got further. */
    private double bestProgress = Double.NEGATIVE_INFINITY;
    private int stepsSinceProgress;
    /** How many times the objective has got nearer, ever: what says a lesson was followed by progress. */
    private long progressCount;
    /** How near the band counts as progress, and what each block of that nearness is worth. */
    private static final int BAND_PROGRESS_WITHIN = 32;
    private static final double BAND_PROGRESS = 0.05;
    /** What one piece of a smelted objective's ore is worth as progress: half the ingot it becomes. */
    private static final double ORE_PROGRESS = 0.5;
    private static final int MOST_ORE_COUNTED = 64;
    /** The items on the shopping list the bag has already reached, so each is paid for once. */
    private final Set<Resource> readied = EnumSet.noneOf(Resource.class);
    /** Whether the objective in hand has already had its early review for a shortage it cannot make up. */
    private boolean reviewedShort;
    /** Whether the planner has been asked about this objective because the body is starving. */
    private boolean reviewedStarving;

    /**
     * Told the name of each objective the moment it is reached. No-op until something wants it.
     *
     * <p>This is the only instant worth keeping a recording of, which is what listens: everything
     * either side of it is a run wandering about, and the clip is the payoff. Called on the game
     * thread, so whatever listens has to get out of the way quickly.
     */
    private Consumer<String> onReached = name -> {
    };

    /** Hands every later completion to {@code listener}. */
    public void onReached(Consumer<String> listener) {
        this.onReached = listener;
    }

    /**
     * What a broken reserve costs, as a multiple of what the thing itself is worth.
     *
     * <p>The mask is what actually stops a reserved item being spent, so this is for the ways round it the
     * mask cannot see: an item dropped, burnt, or eaten by a craft the recipe book counts differently from
     * the plan. Priced at several times the item so that no chain of small gains adds up to a reason.
     */
    private static final double RESERVE_WEIGHT = 3.0;

    private Phase current;
    /** The plan the current objective came in, whole, for the overlay; null between orders. */
    private Plan plan;
    /**
     * The last orders the planner gave, kept so an unreachable planner does not leave the body aimless.
     * A stale objective is worth more than none: the tables go on learning in a real folder instead of
     * in UNPLANNED, and the body works towards something plausible until new orders arrive.
     */
    private Plan lastPlan;
    /** Whether the last orders have already been taken up again during this spell of trouble. */
    private boolean carriedOn;
    /** What the tables are working on, as of the last look: which folder, which source, and where it is. */
    private Pursuit active = Pursuit.PLANNING;
    /** The plan's own band, which stands in for any source that came without one of its own. */
    private Bounds bounds = Bounds.anywhere();
    private Map<Resource, Integer> needs = Map.of();
    private Reserve reserved = Reserve.none();
    private boolean wasSomewhereUseful;
    private int stepsOnCurrent;

    public Progression(ObjectivePlanner planner) {
        this.planner = planner;
    }

    /** Objectives from Claude, and from nowhere else. */
    public static Progression planned(Path directory) {
        return new Progression(ClaudePlanner.create(directory));
    }

    /** What the run is after, or empty while it is between orders. */
    public Optional<Phase> current() {
        return Optional.ofNullable(current);
    }

    /**
     * The whole plan the objective came in — band, shopping list, reserve, sources — or empty between
     * orders. For the overlay, which shows the plan as the run took it; nothing in the run reads it back
     * from here, since every part of it was already unpacked into the fields around it.
     */
    public Optional<Plan> plan() {
        return Optional.ofNullable(plan);
    }

    /**
     * Which objective we are on, as it appears in the observation key. It belongs in the state because the
     * right move depends on it: the same tree is worth chopping while the run is after wood and worth
     * walking past while it is after stone.
     */
    public String stateKey() {
        if (current != null) {
            return current.name();
        }
        return planner.pending() ? PLANNING : UNPLANNED;
    }

    /**
     * The heights to stay between right now, or {@link Bounds#anywhere()} when nobody has an opinion.
     *
     * <p>The source in play comes first: the planner said where each block lives, and a body looking for
     * deepslate ore should be charged for being at the surface even if the plan as a whole was vaguer. The
     * plan's own band stands in when the source has none, and goes with the plan when the plan does.
     */
    public Bounds bounds() {
        return active.where().band();
    }

    /**
     * Works out what the tables should be learning in now, and remembers it.
     *
     * <p>Called once a decision, after the eyes have looked. A hostile in view is a pursuit of its own
     * whatever the objective; otherwise the objective says, given which of its blocks is in view and how
     * high the body is — see {@link Phase#pursuit}. Everything this class answers about height and about
     * which ways are open is answered for the pursuit chosen here until the next look.
     */
    public Pursuit focus(LocalPlayer player, Sighting sighting) {
        Pursuit errand = current == null ? idle() : current.pursuit(seen(player, sighting),
                player.getBlockY(), bounds);
        if (sighting.kind() == FocusKind.HOSTILE && sighting.entity() != null) {
            active = Pursuit.threat(EntityType.getKey(sighting.entity().getType()).getPath(),
                    errand.where());
        } else {
            active = errand;
        }
        return active;
    }

    /** What the tables are working on, as of the last {@link #focus}. */
    public Pursuit active() {
        return active;
    }

    /** The block the plan is after, if that is what is in view, for choosing among sources. */
    private static BlockState seen(LocalPlayer player, Sighting sighting) {
        if (sighting.kind() != FocusKind.RESOURCE || !sighting.isBlock()) {
            return null;
        }
        BlockPos pos = sighting.blockPos();
        return player.level().isLoaded(pos) ? player.level().getBlockState(pos) : null;
    }

    private Pursuit idle() {
        return planner.pending() ? Pursuit.PLANNING : Pursuit.UNPLANNED;
    }

    /**
     * Everything the run has to be holding for the plan to come off, and how much of each.
     *
     * <p>What the crafting table is keyed by, and what the plan charges for losing.
     */
    public Map<Resource, Integer> needs() {
        return needs;
    }

    /**
     * What the plan will not let the body spend.
     *
     * <p>Read by the legality masks rather than by the rewards: this is the half of the plan that is not
     * up for negotiation. See {@link Reserve}.
     */
    public Reserve reserved() {
        return reserved;
    }

    /**
     * Whether seeing one of the blocks the plan is after settles what to do about it.
     *
     * <p>True while gathering and false otherwise. See {@link Phase#minesWhatItSees()} for why this is a
     * rule rather than something the goal table is left to work out.
     */
    public boolean minesWhatItSees() {
        return current != null && current.minesWhatItSees();
    }

    /**
     * The height the body ought to be heading for, or empty when it is already where it should be.
     *
     * <p>Two sources, in order. An objective that is <em>about</em> a height names one outright; otherwise
     * it is the nearest edge of the band, when there is a band and the body is outside it. Both come out
     * as one number so the move that answers them does not have to know which it was.
     */
    public OptionalInt heightWanted(int y) {
        if (current != null) {
            OptionalInt named = current.height();
            if (named.isPresent()) {
                return named.getAsInt() == y ? OptionalInt.empty() : named;
            }
        }
        // The band's edge is only somewhere to head for if the planner said the body may climb or
        // scramble its way there; told to dig, it gets down by digging and the band charges the rest.
        Bounds band = bounds();
        return band.bind() && !band.contains(y) && active.where().allows(Way.CLIMB)
                ? OptionalInt.of(band.nearestEdge(y)) : OptionalInt.empty();
    }

    /**
     * Starts the run over for a world the body has just arrived in, and asks the planner about it at once.
     *
     * <p>Everything {@link #restart()} keeps, this drops as well: the objectives achieved, the rung of the
     * ladder, and the planner's memory of the conversation so far. A death is a setback in the same run
     * and the record of it stands; a new world is a new run, and a plan carried into it from the last one
     * — which is what used to happen, since nothing here noticed the world had changed — is a body
     * working towards wood it gathered somewhere else.
     *
     * <p>Asked now rather than at the first decision, so the answer is that much closer to being there
     * when the first decision comes, and so the log says in so many words that the world was entered and
     * the planner consulted. The first census stands in for the running total, which is exactly what the
     * total would be seeded from a second later.
     */
    public void arrive(LocalPlayer player) {
        restart();
        achieved.clear();
        deaths = 0;
        lastDeath = "";
        mentorNote = "";
        planner.reset();
        log.info("Arrived in a world; asking the planner what to do first");
        planner.consider(() -> situation(player, InventoryCensus.of(player.getInventory()), ""));
        active = idle();
    }

    /**
     * The body has died: the run's record says so from here on, and the plan goes with the life.
     *
     * <p>Counted, and the cause kept, because the planner reads both. A bag that reads "nothing" after a
     * death is not a run that never started, and what killed the body is the one thing about the next
     * plan that the world cannot show — a sword before dark, food before the next walk.
     *
     * @param cause the server's own sentence, or empty when none was caught
     */
    public void died(String cause) {
        deaths++;
        lastDeath = cause == null ? "" : cause.strip();
        log.info("Death {}{}", deaths, lastDeath.isEmpty() ? "" : ": " + lastDeath);
        Chronicle.get().died(lastDeath);
        restart();
    }

    /**
     * Gives the objective up on the mentor's word and asks the planner for another, with that word in
     * the question.
     *
     * <p>The one thing the mentor can do that a table cannot learn. An objective the body cannot reach
     * from here — stone with no pickaxe, a forest that is not in the loaded map — is not a block to be
     * taught out of, and the mentor saying so is worth more than any lesson it could plant.
     */
    public void replan(String why) {
        if (current == null) {
            return;
        }
        log.info("Giving up {} at the mentor's request: {}", current.name(), why);
        Chronicle.get().objectiveAbandoned(current.name(), "the coach gave it up: " + why);
        mentorNote = why == null ? "" : why.strip();
        current = null;
        plan = null;
        stepsOnCurrent = 0;
        forgetProgress();
        active = idle();
    }

    /** Seconds the objective in hand has gone without getting any nearer. Zero between orders. */
    public int stepsWithoutProgress() {
        return current == null ? 0 : stepsSinceProgress;
    }

    /** The same, in whole minutes, for telling a person. */
    public int minutesWithoutProgress() {
        return stepsWithoutProgress() / 60;
    }

    /** How many times the objective in hand has got nearer; rises on every new record and never falls. */
    public long progressCount() {
        return progressCount;
    }

    /** Everything the run knows about itself that the world does not show, attached to a situation. */
    private Situation situation(LocalPlayer player, InventoryCensus obtained, String objective) {
        Situation base = Situation.of(player, obtained, achieved, objective);
        List<String> shortOf = current == null ? List.of() : shortOf(player);
        return base.withRun(deaths, lastDeath, shortOf, minutesWithoutProgress(), mentorNote);
    }

    /**
     * The bag as the plan counts it: what is held, and a crafting table when one stands within reach.
     *
     * <p>A table is the one thing on a shopping list the body does not have to be carrying to have. It
     * is placed to be used, and a body standing beside its own workbench is not short of a table
     * however empty the bag — while a body a hundred blocks from it is, whatever the plan said. Every
     * question about readiness is asked of this census rather than the bag's own.
     */
    public InventoryCensus effective(InventoryCensus held, LocalPlayer player) {
        if (player == null) {
            return held;
        }
        InventoryCensus counted = held;
        if (counted.count(Resource.CRAFTING_TABLE) <= 0 && CraftAtTableGoal.tableInSight(player)) {
            counted = counted.atLeast(Resource.CRAFTING_TABLE, 1);
        }
        if (counted.count(Resource.FURNACE) <= 0 && SmeltGoal.furnaceInSight(player)) {
            counted = counted.atLeast(Resource.FURNACE, 1);
        }
        return counted;
    }

    /**
     * What the objective in hand needs and the bag lacks, in words the planner and the mentor can read.
     *
     * <p>Two kinds of shortage. Items on the shopping list the bag has fewer of than the plan said, less
     * the objective's own item, which is what the objective is for. And a tool: a source the planner
     * named that will not drop for anything in the hotbar, which is the shortage that makes the whole
     * objective futile rather than merely unfinished.
     */
    public List<String> shortOf(LocalPlayer player) {
        List<String> missing = new ArrayList<>();
        if (current == null || player == null) {
            return missing;
        }
        Inventory inventory = player.getInventory();
        InventoryCensus held = effective(InventoryCensus.of(inventory), player);
        Resource own = current.scores().orElse(null);
        if (own != null && own.needsFurnace()) {
            // The ore is the objective's own progress, and the planner has to be told so in words: what
            // is left is not mining but the furnace and something to burn in it.
            int ore = 0;
            List<String> ores = new ArrayList<>();
            for (Resource ingredient : own.ingredients().keySet()) {
                ore += held.count(ingredient);
                ores.add(ingredient.name());
            }
            int fuel = fuelIn(player);
            if (ore > 0) {
                missing.add("nothing left to mine for it: " + ore + " x " + String.join("/", ores)
                        + " in the bag smelt into " + own.name() + "; what is left is a furnace ("
                        + (held.count(Resource.FURNACE) > 0 ? "in hand" : "none") + ") and fuel ("
                        + (fuel > 0 ? fuel + " burnable items carried" : "none carried")
                        + "; coal, charcoal, planks, logs and sticks all burn)");
            }
        }
        for (Map.Entry<Resource, Integer> need : needs.entrySet()) {
            if (need.getKey() != own && held.count(need.getKey()) < need.getValue()) {
                missing.add((need.getValue() - held.count(need.getKey())) + " x " + need.getKey().name());
            }
        }
        if (inventory != null) {
            for (Source source : current.sources()) {
                BlockState state = source.block().defaultBlockState();
                if (state.requiresCorrectToolForDrops() && !Tool.canHarvest(inventory, state)) {
                    missing.add("a " + toolFor(source).name() + " that " + source.name()
                            + " will drop for (nothing in the hotbar does)");
                }
            }
        }
        return missing;
    }

    /** How many things a furnace would burn are in the bag: coal, charcoal, planks, logs, sticks. */
    private static int fuelIn(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && player.level().fuelValues().isFuel(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** The tool a source is broken with: the planner's word, or the game's when the planner said hands. */
    private static Tool toolFor(Source source) {
        return source.tool() == Tool.HAND ? Tool.bestFor(source.block().defaultBlockState()) : source.tool();
    }

    /**
     * The tools the objective's sources need that the hotbar does not hold.
     *
     * <p>Only the tools that are actually required: the game's own word on whether a block drops
     * anything without the right tool, not the planner's. The planner names an axe for logs, and an axe
     * is quicker, but a body without one is not unready for wood. Tier counts: a wooden pickaxe in the
     * hotbar is still a pickaxe missing when the block is iron ore.
     */
    private Set<Tool> toolsMissing(Inventory inventory) {
        Set<Tool> missing = EnumSet.noneOf(Tool.class);
        if (current == null || inventory == null) {
            return missing;
        }
        for (Source source : current.sources()) {
            BlockState state = source.block().defaultBlockState();
            if (state.requiresCorrectToolForDrops() && !Tool.canHarvest(inventory, state)) {
                missing.add(toolFor(source));
            }
        }
        return missing;
    }

    /**
     * Whether the objective asks anything of the bag beyond its own item: a prerequisite on the list, or
     * a source that will not drop without the right tool. What being prepared for it can mean at all.
     */
    private boolean demanding() {
        if (current == null) {
            return false;
        }
        Resource own = current.scores().orElse(null);
        if (needs.keySet().stream().anyMatch(item -> item != own)) {
            return true;
        }
        return current.sources().stream()
                .anyMatch(source -> source.block().defaultBlockState().requiresCorrectToolForDrops());
    }

    /** Whether every prerequisite on the list is in the bag and every tool the sources want in the hotbar. */
    private boolean prepared(InventoryCensus held, Inventory inventory) {
        if (current == null) {
            return false;
        }
        Resource own = current.scores().orElse(null);
        for (Map.Entry<Resource, Integer> need : needs.entrySet()) {
            if (need.getKey() != own && held.count(need.getKey()) < need.getValue()) {
                return false;
            }
        }
        return toolsMissing(inventory).isEmpty();
    }

    /**
     * Whether the objective is short of something the bag cannot make up from here: a thing that is
     * found rather than made, a made thing whose ingredients are not in the bag, or a tool.
     *
     * <p>What separates a shortage the crafting layer will close in a few seconds from one that is the
     * planner's to answer. Asked with the recipe book, because "makeable" is its word and not this
     * class's.
     */
    private boolean shortOfWhatItCannotMake(StepContext context) {
        if (current == null || context.player() == null) {
            return false;
        }
        Inventory inventory = context.player().getInventory();
        if (!toolsMissing(inventory).isEmpty()) {
            return true;
        }
        InventoryCensus held = effective(context.after(), context.player());
        Resource own = current.scores().orElse(null);
        Set<Resource> makeable = null;
        for (Map.Entry<Resource, Integer> need : needs.entrySet()) {
            Resource item = need.getKey();
            if (item == own || held.count(item) >= need.getValue()) {
                continue;
            }
            if (item.ingredients().isEmpty()) {
                return true;
            }
            if (makeable == null) {
                makeable = Recipes.craftableNow(context.player());
            }
            if (!makeable.contains(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the plan, so the next decision asks for a new one.
     *
     * <p>Called when an episode ends. A body that has just died is standing somewhere else with an empty
     * bag, and the objective it was pursuing was chosen for a situation that no longer exists — carrying it
     * over means the run spends its first minutes back working towards a plan made for a corpse.
     */
    public void restart() {
        current = null;
        plan = null;
        lastPlan = null;
        carriedOn = false;
        // Objectives queued for the body that just died are for a bag it no longer has.
        planner.forgetQueued();
        active = idle();
        wasSomewhereUseful = false;
        bounds = Bounds.anywhere();
        needs = Map.of();
        reserved = Reserve.none();
        stepsOnCurrent = 0;
        forgetProgress();
    }

    /** The progress record belongs to an objective, and goes when it does. */
    private void forgetProgress() {
        startedAt = null;
        bestProgress = Double.NEGATIVE_INFINITY;
        stepsSinceProgress = 0;
        readied.clear();
        reviewedShort = false;
        reviewedStarving = false;
    }

    /**
     * Whether sinking a shaft is a route to what the plan wants, or a way of leaving it behind.
     *
     * <p>This is a rule and not a lesson, and it is a rule because of what the mistake costs. Digging down
     * while after wood pays nothing, which the tables could in principle learn — but by the time they have
     * had the experience the body is at the bottom of a hole where nothing else works either, and the only
     * move that still does anything is the one that made it. A body cannot learn its way out of a place
     * its learning cannot reach.
     *
     * <p>Down is a route when the planner said the source in play may be dug towards — see
     * {@link Way#DIG} — or when the band wants the body lower than it is. Otherwise it is not offered.
     */
    public boolean worthDigging(int y) {
        return active.where().allows(Way.DIG) || y > bounds().ceiling();
    }

    /** Why the run is after this, in a sentence, or empty when nobody said. For the overlay only. */
    /**
     * Why the run is doing what it is doing: the planner's reason for the objective, or — with no objective
     * and no answer coming — the planner's account of why not, so the overlay can say it.
     */
    public String reason() {
        return current == null ? planner.trouble().orElse("") : current.reason();
    }

    /** What the current objective wants noticed in the landscape, if anything. */
    public Optional<Predicate<BlockState>> wanted() {
        return current().flatMap(Phase::wanted);
    }

    /**
     * What to break this block with: what the objective said, or what the game says when it said nothing.
     *
     * <p>Never empty, so no caller has to decide what to do without an answer — the worst case is
     * {@link Tool#HAND}, which is what the body was doing anyway.
     */
    public Tool toolFor(BlockState state) {
        return current().flatMap(phase -> phase.toolFor(state)).orElseGet(() -> Tool.bestFor(state));
    }

    /**
     * What the step was worth towards the plan: the objective, less whatever being at the wrong height
     * cost.
     *
     * <p>The charge is here rather than among the general objectives because the band is not a general
     * truth about Minecraft — it is this plan's opinion about where this objective should be pursued, and
     * it goes away when the plan does.
     */
    public double score(StepContext context) {
        double total = (current == null ? 0.0 : current.score(context))
                + shoppingList(context) + reserveBroken(context) + readiness(context);

        // Height is the only part that needs a body to read it off. With no body the band cannot be
        // charged for, and "at the right height" is true exactly when there is no band to be at odds with.
        OptionalInt y = context.player() == null
                ? OptionalInt.empty() : OptionalInt.of(context.player().getBlockY());
        Bounds band = bounds();
        total += arrived(context, y.isPresent() ? band.contains(y.getAsInt()) : !band.bind());
        if (y.isPresent()) {
            total += band.charge(y.getAsInt(), context.steps());
            // And what the step did about it. Without this the band was all stick and no carrot: the
            // plan said where the thing is and the only move that went there was the one that paid least.
            if (context.positionBefore() != null && context.positionAfter() != null) {
                total += band.closed((int) Math.floor(context.positionBefore().y),
                        (int) Math.floor(context.positionAfter().y));
            }
        }
        return total;
    }

    /**
     * Banks every objective the inventory says is done, takes the next one, and returns what that earned.
     *
     * <p>Taking and completing are the same loop because they feed each other: an objective adopted here
     * may already be satisfied — the planner asked for three logs and the run has five — and that is a
     * completion like any other, not a state to sit in until the next step notices.
     *
     * @return the lump sum for every objective reached on this step, or zero if none were
     */
    public double advanceIfComplete(StepContext context) {
        double bonus = 0.0;
        while (true) {
            bonus += adopt(context);
            if (current == null || !current.isComplete(context)) {
                break;
            }
            log.info("Reached {}", current.name());
            Chronicle.get().objectiveReached(current.name());
            achieved.add(current.name());
            onReached.accept(current.name());
            current = null;
            plan = null;
            stepsOnCurrent = 0;
            forgetProgress();
            bonus += ADVANCE_BONUS;
        }
        reviewIfStale(context);
        return bonus;
    }

    /**
     * What the step was worth for being, or becoming, equipped for the objective.
     *
     * <p>The reward for anticipating, in two parts. A lump the first time the bag holds as many of a
     * listed item as the plan will need — the sticks before the pickaxe, the cobblestone before the
     * furnace — and a standing charge for every second spent on an objective whose blocks nothing in
     * the hotbar will break. Both reach the crafting table through the same reward as everything else,
     * which is where a body short of a pickaxe learns to make one before walking to the stone.
     */
    private double readiness(StepContext context) {
        if (current == null) {
            return 0.0;
        }
        double total = 0.0;
        Resource own = current.scores().orElse(null);
        InventoryCensus held = effective(context.after(), context.player());
        for (Map.Entry<Resource, Integer> need : needs.entrySet()) {
            Resource item = need.getKey();
            if (item == own || readied.contains(item)) {
                continue;
            }
            if (held.count(item) >= need.getValue()) {
                readied.add(item);
                total += READY_BONUS;
            }
        }
        if (context.player() != null && !toolsMissing(context.player().getInventory()).isEmpty()) {
            total -= UNREADY_COST * Math.max(1, context.steps());
        }
        return total;
    }

    /**
     * How far along the objective the run is, as a number to compare with its own earlier readings.
     *
     * <p>The objective's own measure, plus what the shopping list has got towards it, plus — for a
     * journey, which cannot measure itself — new ground away from where it started. See
     * {@link Phase#progress}.
     */
    private double progressOf(StepContext context) {
        if (current == null) {
            return 0.0;
        }
        double along = current.progress(context);
        Resource own = current.scores().orElse(null);
        InventoryCensus held = effective(context.after(), context.player());
        for (Map.Entry<Resource, Integer> need : needs.entrySet()) {
            if (need.getKey() != own) {
                along += Math.min(need.getValue(), held.count(need.getKey()));
            }
        }
        // A smelted objective's ore is progress towards it, at half an ingot each so that smelting
        // one is progress too. Seven raw iron in the bag read as eight minutes of "no progress" to
        // the planner, which gave the objective up as hopeless with the ore already mined.
        if (own != null && own.needsFurnace()) {
            for (Resource ore : own.ingredients().keySet()) {
                along += ORE_PROGRESS * Math.min(MOST_ORE_COUNTED, held.count(ore));
            }
        }
        // Getting to the height the plan named is progress on the objective, so a body climbing towards
        // it is working rather than stalling, and the planner is not told it has been stuck for minutes
        // while it climbs.
        Bounds band = bounds();
        if (band.bind() && context.positionAfter() != null) {
            along += BAND_PROGRESS * Math.max(0,
                    BAND_PROGRESS_WITHIN - band.outside((int) Math.floor(context.positionAfter().y)));
        }
        if (startedAt != null && context.positionAfter() != null && "GO".equals(current.shape())) {
            along += Math.floor(flat(startedAt, context.positionAfter()) / TRAVEL_PROGRESS_BLOCKS);
        }
        return along;
    }

    /**
     * Notes whether the step got the objective any nearer, and how long it has been since one did.
     *
     * <p>A record, not a reward: what counts is beating the best the objective has ever managed, so a
     * body that gathers a log, loses it and gathers it again has not progressed twice.
     */
    private void noteProgress(StepContext context) {
        if (current == null) {
            return;
        }
        double now = progressOf(context);
        if (now > bestProgress + 1.0E-6) {
            bestProgress = now;
            stepsSinceProgress = 0;
            progressCount++;
        } else {
            stepsSinceProgress += Math.max(1, context.steps());
        }
    }

    private static double flat(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Asks the planner whether an objective that has gone on a long time is still the right one.
     *
     * <p>The clock is reset by the asking rather than by the answer, so a planner that says to keep going
     * is not asked again until the next whole window has passed. Without that, an objective it had just
     * approved would be put to it again on the very next decision.
     */
    private void reviewIfStale(StepContext context) {
        if (current == null) {
            return;
        }
        stepsOnCurrent += Math.max(1, context.steps());
        // Whether this step got anywhere, before anything below decides what to do about it not having.
        noteProgress(context);

        // A pinned body is a block, and a block is the mentor's to answer, not the planner's — see the
        // brain, which owns the tables the mentor teaches. There was a self-rescue here — twelve blocks
        // up, then twelve down, by turns, whenever the body read as pinned — and it built a tower to
        // y=127 in an open savanna: a body climbing straight up reads as pinned, and every pinned
        // decision set the escape twelve higher. Getting out of a hole is a lesson and a skill now.
        boolean pinned = context.pinned();

        // Not stuck, just long: ask the planner whether the objective is still right. Rarely, and the
        // planner's own cache drops the call when nothing about the situation has changed since last time.
        // Sooner, once, when the objective is short of something the bag cannot make: that is not a
        // slow objective, it is one that needs another objective in front of it.
        boolean shortEarly = !reviewedShort && stepsOnCurrent >= REVIEW_WHEN_SHORT_STEPS
                && shortOfWhatItCannotMake(context);
        // Starving is not a slow objective either: it is the one thing that ends the run whatever the
        // objective was. Asked at once, once, unless the objective is already food.
        boolean starving = !reviewedStarving && context.player() != null
                && context.player().getFoodData().getFoodLevel() <= Situation.STARVING_AT
                && Resource.FOOD.countIn(context.player().getInventory()) == 0
                && current.scores().orElse(null) != Resource.FOOD;
        // Pinned used to end it here, leaving a pinned body to the mentor alone; with the mentor's
        // questions for the pursuit spent, a body stuck in a hole went thirty-seven minutes without
        // anyone being asked. The short-of review still waits for a free body; starving and the
        // long review do not.
        if (pinned && !starving && stepsOnCurrent < REVIEW_AFTER_STEPS) {
            return;
        }
        if (stepsOnCurrent < REVIEW_AFTER_STEPS && !shortEarly && !starving) {
            return;
        }
        if (starving) {
            reviewedStarving = true;
            log.info("Starving on {}; asking the planner for the way to food", current.name());
        } else if (shortEarly) {
            reviewedShort = true;
            log.info("{} is short of something it cannot make; asking the planner to look at it",
                    current.name());
        } else {
            log.info("{} has been going a while; asking the planner to look at it", current.name());
        }
        stepsOnCurrent = 0;
        String objective = current.toString();
        planner.consider(() -> situation(context.player(), context.obtained(), objective));
    }

    /** What the mentor is told about a block: the run as it stands, with the objective in hand. */
    public Situation blockSituation(net.minecraft.client.player.LocalPlayer player,
                                    InventoryCensus obtained) {
        return situation(player, obtained, current == null ? "" : current.toString());
    }

    /**
     * Finds the run something to do: the planner's answer if it has arrived, and a question if it has not
     * been asked. With no answer and none coming the run stays without an objective — there is nothing
     * else to take one from — and the question is put again at every decision until one is.
     *
     * <p>Bounded, which is what lets the caller loop on it: the planner hands over at most one answer per
     * question and asks at most one question at a time.
     */
    /**
     * @return what taking the objective was worth: a lump for being already equipped for it, a charge for
     *         being unable to break its blocks, and nothing when no objective was taken
     */
    private double adopt(StepContext context) {
        // Taken before the early return, because an answer may be a review's: the planner was asked about
        // the objective in hand and came back with a different one, and that is meant to take effect.
        Optional<Plan> planned = planner.take();
        if (planned.isPresent()) {
            if (current != null) {
                log.info("Planner swapped {} for {}", current.name(), planned.get().objective().name());
                Chronicle.get().objectiveAbandoned(current.name(),
                        "the planner swapped it for " + planned.get().objective().name());
            }
            current = planned.get().objective();
            Chronicle.get().objectiveStarted(current.name(), current.toString());
            plan = planned.get();
            lastPlan = planned.get();
            carriedOn = false;
            bounds = planned.get().bounds();
            needs = planned.get().needs();
            reserved = planned.get().reserved();
            stepsOnCurrent = 0;
            // The note was for this question, and the question has been answered.
            mentorNote = "";
            refocus(context);
            return taken(context);
        }
        if (current != null) {
            return 0.0;
        }
        // Nothing in hand: the objectives the last answer queued behind itself come first, in order,
        // and cost nothing to take.
        Optional<Plan> next = planner.takeQueued();
        if (next.isPresent()) {
            current = next.get().objective();
            Chronicle.get().objectiveStarted(current.name(), current.toString());
            plan = next.get();
            lastPlan = next.get();
            carriedOn = false;
            bounds = next.get().bounds();
            needs = next.get().needs();
            reserved = next.get().reserved();
            stepsOnCurrent = 0;
            mentorNote = "";
            refocus(context);
            return taken(context);
        }
        // A supplier rather than a situation: reading the world costs an inventory walk and an entity
        // query, and there is no sense paying for either when the planner is going to ignore the question.
        planner.consider(() -> situation(context.player(), context.obtained(), ""));
        // Nobody to ask and nothing to do: rather than wander unplanned for the minute the planner is
        // resting, take up the last orders again. Once per spell of trouble, and never orders that are
        // already finished.
        if (!carriedOn && lastPlan != null && planner.trouble().isPresent()
                && !lastPlan.objective().isComplete(context)) {
            carriedOn = true;
            current = lastPlan.objective();
            plan = lastPlan;
            bounds = lastPlan.bounds();
            needs = lastPlan.needs();
            reserved = lastPlan.reserved();
            stepsOnCurrent = 0;
            log.info("No planner ({}); carrying on with the last orders: {}",
                    planner.trouble().orElse("?"), current.name());
            Chronicle.get().objectiveStarted(current.name(),
                    "the planner could not be reached; carrying on with the last orders");
            refocus(context);
            return taken(context);
        }
        // Whether that put a question, owes one, or could do neither is what the idle pursuit reads.
        refocus(context);
        return 0.0;
    }

    /**
     * Starts the objective's own record — where the run was, how far along it already is, which of the
     * list it already holds — and says what arriving so equipped, or not, was worth.
     *
     * <p>What is already held is marked as reached so it is not paid for again as it comes in; what
     * counts for it is the lump here. A body holding every prerequisite as the objective arrives has
     * anticipated it, and that is the thing worth paying for. One that cannot break the objective's
     * blocks has been sent on a pursuit it cannot finish, and the charge lands on whatever move left it
     * without the tool.
     */
    private double taken(StepContext context) {
        forgetProgress();
        startedAt = context.positionAfter();
        Resource own = current.scores().orElse(null);
        InventoryCensus held = effective(context.after(), context.player());
        for (Map.Entry<Resource, Integer> need : needs.entrySet()) {
            if (need.getKey() != own && held.count(need.getKey()) >= need.getValue()) {
                readied.add(need.getKey());
            }
        }
        bestProgress = progressOf(context);
        Inventory inventory = context.player() == null ? null : context.player().getInventory();
        if (inventory == null || !demanding()) {
            // Nothing to have anticipated: a plain gather with nothing behind it is neither.
            return 0.0;
        }
        if (prepared(held, inventory)) {
            log.info("Prepared for {}: everything it needs is in hand", current.name());
            return PREPARED_BONUS;
        }
        Set<Tool> missing = toolsMissing(inventory);
        if (!missing.isEmpty()) {
            log.info("Unprepared for {}: no {} in the hotbar", current.name(), missing);
            return -UNPREPARED_PENALTY;
        }
        return 0.0;
    }

    /**
     * Brings the pursuit into line with a plan that has just changed, before the eyes have looked again.
     *
     * <p>Without this the step scored right after a new plan arrives would be charged against the old
     * plan's band, and the ways open to the body would be the old plan's until the next decision. With
     * nothing in view yet the objective answers for its likeliest source, which {@link #focus} refines the
     * moment there is a view.
     */
    private void refocus(StepContext context) {
        int y = context.player() == null ? 0 : context.player().getBlockY();
        active = current == null ? idle() : current.pursuit(null, y, bounds);
    }

    /**
     * What the rest of the shopping list did over the step: paid for what turned up, charged for what left.
     *
     * <p>The same arithmetic a gathering objective applies to its own resource, applied to everything else
     * the plan is going to need. It is what makes putting a needed block into a wall cost something, and
     * crafting one away too — neither of which the objective's own scoring can see, because neither is
     * about the thing the objective is named after.
     *
     * <p>What the objective already scores for itself is left out, so nothing is counted twice and a
     * {@link Build} is not charged for the very blocks it is trying to put down.
     */
    private double shoppingList(StepContext context) {
        if (needs.isEmpty()) {
            return 0.0;
        }
        Resource own = current == null ? null : current.scores().orElse(null);
        double total = 0.0;
        for (Resource needed : needs.keySet()) {
            if (needed != own) {
                total += context.netChange(needed) * needed.worth();
            }
        }
        return total;
    }

    /**
     * What eating into the reserve costs.
     *
     * <p>Only the part of a loss that falls below the line is charged. Spending the fourth of four logs
     * when three were being held back costs nothing here — that log was spare and spending it was allowed.
     * Spending the third is what this is about.
     *
     * <h2>One direction only</h2>
     * It charges for going down and pays nothing for coming back up, and the asymmetry is the point: a
     * reserve is a prohibition, not a bounty. Obeying it is the baseline rather than an achievement.
     *
     * <p>Paying both ways looked symmetrical and was a hole. A reserve does not have to be about something
     * the body is carrying — the usual case is the opposite, ten obsidian put aside before a single one
     * has been found — and a body starting ten short would have collected the reserve's whole weight for
     * every one it picked up, on top of what the thing is worth and what the shopping list already pays.
     * Three times the worth of an obsidian is more than finishing an objective pays. Gathering is already
     * rewarded for being gathering; this term has no business paying for it twice.
     *
     * <p>Something both needed and reserved is charged twice on a loss, once by each, and that is meant:
     * the plan is making two different statements about it and both of them are true.
     */
    private double reserveBroken(StepContext context) {
        if (reserved.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (Resource resource : reserved.kept().keySet()) {
            // A block put down in the world is still held, as far as a reserve is concerned: it is not
            // spent, it is a metre away. Only what actually left counts against the line.
            int deeper = reserved.shortfall(resource,
                            context.after().count(resource) + context.placed(resource))
                    - reserved.shortfall(resource, context.before().count(resource));
            total -= Math.max(0, deeper) * resource.worth() * RESERVE_WEIGHT;
        }
        return total;
    }

    /**
     * What being in the right place is worth, paid once on getting there.
     *
     * <p>The one thing the position table had no way to learn. Going somewhere paid only for the ground
     * covered, so every direction was worth the same and arriving was worth nothing at all — the body could
     * walk past the forest it had been sent to find and be no worse off for it. This pays for the arrival:
     * something the plan needs in view, at a height the plan approves of.
     *
     * <p>Paid on the transition rather than for every second of standing there, which is the difference
     * between a reason to travel and a reason to stare at a tree. Losing sight of it and finding it again
     * costs a walk, so the going rate for farming this is worse than the rate for doing the job.
     */
    private double arrived(StepContext context, boolean atTheRightHeight) {
        boolean somewhereUseful = context.resourceInSight() && atTheRightHeight;
        boolean paid = somewhereUseful && !wasSomewhereUseful;
        wasSomewhereUseful = somewhereUseful;
        return paid ? ARRIVAL_BONUS : 0.0;
    }

    /** Lets go of the planner's thread. Called on the way out of the game. */
    public void close() {
        planner.close();
    }
}
