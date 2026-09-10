package io.github.ivannavas.autocraftai.mob.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The terrain between the body and where it is trying to go: which way it wants, what is in front of it,
 * what is over its head, and what it could do about any of that.
 *
 * <p>This is the state of the passage table, and it exists because nothing else in the brain could see the
 * problem it answers. A body that wants to go up and finds no slope, or walks into a wall it could break or
 * build over, looked to every other table like a body in a field: the goal table keys on what is in view
 * and what is in view is a tree; the position table keys on where it has been. Neither has a word for
 * "there is a two-block wall in front of you and you are carrying dirt". This does.
 *
 * <h2>A fact and the places to act on it, together</h2>
 * The words are what the table keys on. The block positions are what a goal needs to act on them, and they
 * come out of the same pass so the word and the place can never disagree — the same arrangement as
 * {@link Water}, for the same reason.
 *
 * @param wanted         which way the body is trying to go
 * @param ahead          what is in its way in the direction it is facing
 * @param above          whether there is room over its head to jump or to stack a block
 * @param hasBlocks      whether it is carrying something it could put down
 * @param aheadBreakable whether what is ahead could be broken at all — bedrock cannot; stone can, by hand
 *                       if it comes to that
 * @param ceilingBreakable the same for what is overhead
 * @param aheadByHand    whether breaking what is ahead would be slow: nothing in the bag is the tool for
 *                       it, so the hand does it at a block every several seconds and gets no drop
 * @param ceilingByHand  the same for what is overhead
 * @param canDig         whether the block under its feet could be broken without opening a drop
 * @param canStack       whether a block could go where its feet are: not while it stands in a slab, a
 *                       carpet or anything else a placed block will not push aside
 * @param aheadBlocks    the solid blocks in its way ahead, head height first, for breaking through
 * @param ceilingBlocks  the solid blocks over its head, for breaking upward
 * @param under          the block under its feet
 * @param facing         the way it is facing, as a yaw in radians
 * @param side           which side a detour should try first, chosen at random so a body does not always
 *                       sidestep the same way and walk the same wall for ever
 * @param target         where its legs were taking it, if anywhere, for measuring progress on the flat
 * @param sought         the block it is trying to get at, when that is what it wants, else null
 */
public record Obstruction(Wanted wanted, Ahead ahead, Above above, boolean hasBlocks,
                          boolean aheadBreakable, boolean ceilingBreakable,
                          boolean aheadByHand, boolean ceilingByHand, boolean canDig,
                          boolean canStack, List<BlockPos> aheadBlocks, List<BlockPos> ceilingBlocks,
                          BlockPos under, double facing, int side, Vec3 target, BlockPos sought) {

    /** Which way the body is trying to go. */
    public enum Wanted {
        /** Higher than it is: the plan's band is above, or a climb was asked for. */
        UP,
        /** Lower than it is. */
        DOWN,
        /** Somewhere on the level: a destination its legs were taking it to. */
        FLAT,
        /**
         * At a block it is already in reach of, with something between them: the log behind the leaves.
         * The one want that is about the hands rather than the legs, and the reason this layer can learn
         * which blocks are worth breaking to get at the one it is after.
         */
        TOWARD
    }

    /**
     * What is in the way: in the direction the body is facing for a body that wants to move, or along the
     * line to the block for one that wants to get at it. The three kinds of block are told apart because
     * they cost very different seconds — leaves come away at a touch, dirt and wood in a moment by hand,
     * and anything that wants a tool takes the tool's time or does not come away at all.
     */
    public enum Ahead {
        /** Clear. */
        NONE,
        /** One block high with room above it: a hop, which the legs already take on their own. */
        STEP,
        /** Two or more blocks high, or one with a ceiling over it — nothing the legs can do. */
        WALL,
        /** A drop long enough to hurt. */
        GAP,
        /** Leaves between the body and the block it is after. */
        LEAVES,
        /** Something that needs no tool between the body and the block it is after: dirt, wood, sand. */
        SOFT,
        /** Something that wants a tool between the body and the block it is after: stone, ore. */
        HARD
    }

    /** Whether there is room over the body's head. */
    public enum Above {
        OPEN,
        /** Something solid within two blocks of the head: no jumping, and no stacking. */
        CEILING
    }

    /** How far down the ground ahead has to be before stepping off it is a fall rather than a step. */
    private static final int DROP = 5;
    /** How finely the line to a block is sampled for what is on it. A fifth of a block misses nothing. */
    private static final double SAMPLE = 0.2;

    public Obstruction {
        aheadBlocks = List.copyOf(aheadBlocks);
        ceilingBlocks = List.copyOf(ceilingBlocks);
    }

    /**
     * Reads the terrain around the body. Must be called on the client thread.
     *
     * @param wanted    which way the body is trying to go, which the terrain alone cannot say
     * @param hasBlocks whether it has something to build with, which the bag says rather than the ground
     * @param target    where its legs were taking it, or null
     */
    public static Obstruction around(LocalPlayer player, Wanted wanted, boolean hasBlocks, Vec3 target) {
        Level level = player.level();
        Direction facing = player.getDirection();
        BlockPos feet = player.blockPosition();
        BlockPos feetAhead = feet.relative(facing);
        BlockPos headAhead = feetAhead.above();
        BlockPos head = feet.above();

        boolean feetSolid = obstructs(level, feetAhead);
        boolean headSolid = obstructs(level, headAhead);
        boolean overStepSolid = obstructs(level, headAhead.above());
        // A fence, a wall or a shut gate is one block on the map and a block and a half to the legs:
        // the hop that clears a step does not clear it, and read as a step it was a step the body hopped
        // at for the rest of the commitment while the tree stood behind it. What is in the way is
        // measured by what the legs meet, not by what the map says is there.
        boolean tall = feetSolid && collisionTop(level, feetAhead) > 1.0;
        Ahead ahead;
        if (headSolid || tall || (feetSolid && overStepSolid)) {
            ahead = Ahead.WALL;
        } else if (feetSolid) {
            ahead = Ahead.STEP;
        } else {
            ahead = dropAhead(level, feetAhead) ? Ahead.GAP : Ahead.NONE;
        }

        List<BlockPos> wall = new ArrayList<>(2);
        if (headSolid) {
            wall.add(headAhead.immutable());
        }
        if (feetSolid) {
            wall.add(feetAhead.immutable());
        }
        List<BlockPos> ceiling = new ArrayList<>(2);
        for (int up = 1; up <= 2; up++) {
            BlockPos pos = head.above(up);
            if (solid(level, pos)) {
                ceiling.add(pos.immutable());
            }
        }

        return new Obstruction(wanted, ahead, ceiling.isEmpty() ? Above.OPEN : Above.CEILING, hasBlocks,
                !wall.isEmpty() && wall.stream().allMatch(pos -> breakable(player, pos)),
                !ceiling.isEmpty() && ceiling.stream().allMatch(pos -> breakable(player, pos)),
                wall.stream().anyMatch(pos -> byHand(player, pos)),
                ceiling.stream().anyMatch(pos -> byHand(player, pos)),
                Perception.canDigDown(player), canStack(level, feet), wall, ceiling,
                feet.below().immutable(),
                Math.toRadians(player.getYRot()), ThreadLocalRandom.current().nextBoolean() ? 1 : -1,
                target, null);
    }

    /**
     * Whether a block could be put where the feet are.
     *
     * <p>Pillaring drops a block into the space the feet have just left, and that only works when the
     * space is one a placed block goes into: air, grass, a snow layer. A body standing on a bottom slab
     * has its feet inside the slab's own block, and every click to pillar there was refused — for as
     * long as the lesson that said to pillar stood, which was for ever.
     */
    private static boolean canStack(Level level, BlockPos feet) {
        return level.isLoaded(feet) && level.getBlockState(feet).canBeReplaced();
    }

    /**
     * Reads what stands between the body and a block it is in reach of. Must be called on the client
     * thread.
     *
     * <p>The blocks in the way are what is ahead here, nearest first, which is the order to break them
     * in: each one gone puts the next on the line. The kind of the nearest is what the key says, because
     * that is the one about to be dealt with.
     */
    public static Obstruction toward(LocalPlayer player, BlockPos sought, boolean hasBlocks) {
        Level level = player.level();
        List<BlockPos> between = occluders(player, sought);
        Ahead ahead = Ahead.NONE;
        if (!between.isEmpty()) {
            BlockState first = level.getBlockState(between.get(0));
            ahead = first.is(BlockTags.LEAVES) ? Ahead.LEAVES
                    : first.requiresCorrectToolForDrops() ? Ahead.HARD : Ahead.SOFT;
        }
        BlockPos head = player.blockPosition().above();
        List<BlockPos> ceiling = new ArrayList<>(2);
        for (int up = 1; up <= 2; up++) {
            BlockPos pos = head.above(up);
            if (solid(level, pos)) {
                ceiling.add(pos.immutable());
            }
        }
        return new Obstruction(Wanted.TOWARD, ahead, ceiling.isEmpty() ? Above.OPEN : Above.CEILING,
                hasBlocks,
                !between.isEmpty() && between.stream().allMatch(pos -> breakable(player, pos)),
                !ceiling.isEmpty() && ceiling.stream().allMatch(pos -> breakable(player, pos)),
                between.stream().anyMatch(pos -> byHand(player, pos)),
                ceiling.stream().anyMatch(pos -> byHand(player, pos)),
                Perception.canDigDown(player), canStack(level, player.blockPosition()), between, ceiling,
                player.blockPosition().below().immutable(),
                Math.toRadians(player.getYRot()), ThreadLocalRandom.current().nextBoolean() ? 1 : -1,
                Vec3.atCenterOf(sought), sought.immutable());
    }

    /**
     * The blocks on the line from the eye to a block, nearest first, not counting the block itself.
     *
     * <p>Sampled the way the mining goal's own raycast sees them — by outline, so a tuft of grass that
     * stops the swing counts as being in the way, because it is. Read again after each second of work,
     * which is how the passage table is paid by the block for clearing the line.
     */
    public static List<BlockPos> occluders(LocalPlayer player, BlockPos sought) {
        Level level = player.level();
        Vec3 from = player.getEyePosition();
        Vec3 to = Vec3.atCenterOf(sought);
        double length = from.distanceTo(to);
        Vec3 step = to.subtract(from).normalize().scale(SAMPLE);
        List<BlockPos> found = new ArrayList<>();
        Vec3 at = from;
        for (double gone = 0.0; gone < length; gone += SAMPLE, at = at.add(step)) {
            BlockPos pos = BlockPos.containing(at);
            if (pos.equals(sought) || found.contains(pos)) {
                continue;
            }
            if (level.isLoaded(pos) && !level.getBlockState(pos).getShape(level, pos).isEmpty()) {
                found.add(pos.immutable());
            }
        }
        return found;
    }

    /**
     * Whether there is anything here worth a decision. On the flat with nothing in front and nothing
     * overhead the body is not obstructed by terrain, whatever else is wrong with it, and asking the
     * table would only teach it to answer a question nobody put.
     */
    public boolean matters() {
        if (wanted == Wanted.TOWARD) {
            return ahead != Ahead.NONE;
        }
        return wanted != Wanted.FLAT || ahead != Ahead.NONE || above == Above.CEILING;
    }

    /**
     * The state key, in a fixed order so a table written today still reads tomorrow. Three letters may
     * follow: {@code B} carrying blocks, {@code T} the way ahead can be broken with a tool it carries
     * ({@code t}: only by hand, slowly), {@code D} the ground under the feet can be dug.
     */
    /**
     * The same reading in plain words, for the mentor, which is shown the block rather than the table and
     * has to be told what a wall is and whether the body could do anything about it.
     */
    public String words() {
        StringBuilder out = new StringBuilder();
        out.append(switch (wanted) {
            case UP -> "wants to get higher";
            case DOWN -> "wants to get lower";
            case FLAT -> "wants to walk on";
            case TOWARD -> "wants to get at a block already in reach";
        });
        out.append("; ahead: ").append(switch (ahead) {
            case NONE -> "clear";
            case STEP -> "a one-block step (its legs take that on their own)";
            case WALL -> "a wall two or more blocks high" + breaking(aheadBreakable, aheadByHand);
            case GAP -> "a drop long enough to hurt";
            case LEAVES -> "leaves between it and the block it is after";
            case SOFT -> "soft blocks (dirt, wood, sand) between it and the block it is after";
            case HARD -> "hard blocks (stone, ore) between it and the block it is after"
                    + breaking(aheadBreakable, aheadByHand);
        });
        out.append("; overhead: ").append(above == Above.OPEN ? "open, it can jump and stack"
                : "a ceiling within two blocks" + breaking(ceilingBreakable, ceilingByHand));
        out.append(hasBlocks ? "; carrying blocks it could put down" : "; nothing to put down");
        out.append(canDig ? "; the block underfoot could be dug out without opening a drop"
                : "; digging straight down here is not safe or not possible");
        return out.toString();
    }

    /**
     * Whether, and how readily, the body could break something. Told plainly because the coach was told
     * "cannot break with what it holds" of a stone wall round a body with no pickaxe, believed it, and
     * spent its lessons looking for dirt to punch instead of the wall.
     */
    private static String breaking(boolean breakable, boolean byHand) {
        if (!breakable) {
            return " it cannot break at all";
        }
        return byHand ? " it could break by hand, slowly — several seconds a block, and no drop from it"
                : " it could break with a tool it carries";
    }

    public String key() {
        StringBuilder tags = new StringBuilder();
        if (hasBlocks) {
            tags.append('B');
        }
        if (aheadBreakable) {
            // Two letters for the two prices: a wall that comes down in a second with the pickaxe and one
            // that takes the hand ten are different questions, and a table that could not tell them apart
            // learned the tool's answer for the hand.
            tags.append(aheadByHand ? 't' : 'T');
        }
        if (canDig) {
            tags.append('D');
        }
        return wanted.name() + '|' + ahead.name() + '|' + above.name() + '|'
                + (tags.isEmpty() ? "-" : tags);
    }

    private static boolean solid(Level level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).isSolid();
    }

    /**
     * Whether the body cannot walk through this block: anything with a collision box, which is what
     * the legs are stopped by. Solid by the map's word is not the test — a fence and a pane of glass
     * are thin on the map and stop a body dead, and a tall grass is drawn as a block and does not.
     */
    private static boolean obstructs(Level level, BlockPos pos) {
        return level.isLoaded(pos) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** How high the block's collision box reaches above its own base: one for a cube, more for a fence. */
    private static double collisionTop(Level level, BlockPos pos) {
        var shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
    }

    /** Whether the ground ahead is far enough down that walking on would be a fall. */
    private static boolean dropAhead(Level level, BlockPos feetAhead) {
        for (int down = 1; down <= DROP; down++) {
            BlockPos pos = feetAhead.below(down);
            if (!level.isLoaded(pos)) {
                return false;
            }
            if (level.getBlockState(pos).isSolid() || !level.getFluidState(pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a block could be broken with what is in hand in the time a passage is worth.
     *
     * <p>Unbreakable blocks never; blocks that drop nothing without the right tool only with it, because
     * stone punched bare-handed takes the better part of ten seconds a block and a passage that takes a
     * minute is not a passage. Dirt, wood, sand and their like need no tool and always count.
     */
    /**
     * Whether the block could come apart at all. Bedrock cannot; everything else can, and the hand does
     * it when nothing better is carried.
     *
     * <p>It used to ask for the tool as well, and that made a body with no pickaxe a body the terrain
     * layer could do nothing for: shut in stone, the only moves it was offered were a stroll to the
     * left and a stroll to the right, and it took them by turns for an hour. Breaking stone by hand is
     * slow and drops nothing, and whether the seconds are worth it is the table's lesson to learn from
     * the seconds — which is what {@link io.github.ivannavas.autocraftai.mob.goal.BreakGoal} was written
     * for in the first place. The price is in the key, so the lesson learned with a pickaxe in the bag
     * is not the one taught to the hand.
     */
    private static boolean breakable(LocalPlayer player, BlockPos pos) {
        Level level = player.level();
        return level.getBlockState(pos).getDestroySpeed(level, pos) >= 0.0F;
    }

    /** Whether nothing in the hotbar is the tool for this block, so breaking it means the hand's slow way. */
    private static boolean byHand(LocalPlayer player, BlockPos pos) {
        // Anything in the hotbar, not only what is in the hand: the goal that breaks it will pick the tool.
        return !Tool.canHarvest(player.getInventory(), player.level().getBlockState(pos));
    }
}
