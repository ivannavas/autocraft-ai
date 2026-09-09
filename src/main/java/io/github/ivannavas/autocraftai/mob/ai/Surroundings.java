package io.github.ivannavas.autocraftai.mob.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import io.github.ivannavas.autocraftai.mob.ai.objective.InventoryCensus;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The body's situation, read as a whole: what is hostile around it and how much of it, whether it is
 * armed, how hurt, what is over its head, whether it is night, and what it could build with.
 *
 * <p>This is the state of the tactics table, and it exists because every other table sees one thing at
 * a time. The goal table is keyed on the one sighting the eyes settled on — a zombie, say — and cannot
 * tell a zombie by day from a zombie and a skeleton at night in a cave with nothing in the bag. The
 * passage table sees terrain and nothing alive. Neither can represent the question "what do I do about
 * all of this at once", and that question is where the run kept dying: thirteen deaths in one night,
 * every one of them a body with nothing in hand fleeing across a beach from something faster than it.
 *
 * <h2>A map, boiled down</h2>
 * What is read is a map of the surroundings — every hostile within range and what kind, the sky or the
 * roof, the walls, the light, the blocks in hand — and what the table keys on is that map boiled down to
 * a handful of words. The hostiles are kept as well, sorted by how dangerous each is, because the goals
 * that act on the state need the mobs and not the words: a fight is with the skeleton first, and a wall
 * goes up on the side the nearest one is coming from.
 *
 * <h2>When it is worth asking about</h2>
 * Nearly always the answer is "nothing", and a table asked every second about nothing would learn to
 * answer nothing. So the layer only wakes when the surroundings call for it — see {@link #demanding()}:
 * something hostile in range, or night with no sword and no roof, or an objective that has stopped
 * getting anywhere while the body is under a roof or down a pit, which is what being trapped looks like.
 *
 * @param hostiles  everything hostile within range, most dangerous first and then nearest
 * @param threat    the kinds of hostile, as a word for the key: {@code ZOMBIE}, {@code SKELETON+ZOMBIE}…
 * @param count     how many, capped at three
 * @param nearest   how far the nearest is
 * @param armed     whether there is a sword or an axe in the hotbar
 * @param health    how hurt the body is
 * @param cover     what is over the body: open sky, a pit, or a roof
 * @param light     whether it is night, or dark where the body is
 * @param blocks    how many blocks it could put down, tables not counted
 * @param canDig    whether the ground under its feet could be dug out without opening a drop
 * @param roomAbove whether there is room over its head to stack three blocks
 * @param surface   the height of the surface in this column, for finding the sky
 * @param stuck     whether the objective has stopped getting anywhere
 * @param at        where the body was when it looked, for measuring the hostiles from
 */
public record Surroundings(List<LivingEntity> hostiles, String threat, int count,
                           Perception.Distance nearest, boolean armed, Perception.Health health,
                           Cover cover, Light light, int blocks, boolean canDig, boolean roomAbove,
                           int surface, boolean stuck, Vec3 at) {

    /** What is over the body's head, in the three words that change what is worth doing. */
    public enum Cover {
        /** Open sky. */
        SKY,
        /** Sky overhead, walls all round: a hole, a ravine floor, a shaft. */
        PIT,
        /** No sky: a cave, a tunnel, a room. */
        ROOF
    }

    /** Whether the world is dark, which is when the surface is dangerous. */
    public enum Light {
        DAY,
        NIGHT,
        /** Day outside and dark here: underground, or deep under trees. */
        DARK
    }

    /** How far out hostiles are counted. What a skeleton shoots from. */
    private static final double THREAT_RANGE = 16.0;
    /** Within this, a hostile counts even unseen: it is round the corner, not behind the rock. */
    private static final double CLOSE_ANYWAY = 3.0;
    /** Light below which the body's own spot counts as dark: what mobs spawn in. */
    private static final int DARK_BELOW = 8;
    /** How far under the surface a body with the sky over it is still in a pit. */
    private static final int PIT_DEPTH = 3;
    /** The most kinds of hostile named in the key before it says "many". */
    private static final int KINDS_NAMED = 2;
    /** Blocks in hand at or above which the key says the body is well stocked. */
    private static final int WELL_STOCKED = 3;

    public Surroundings {
        hostiles = List.copyOf(hostiles);
        threat = threat == null || threat.isEmpty() ? "NONE" : threat;
    }

    /**
     * Reads the surroundings. Must be called on the client thread.
     *
     * @param reserve what the plan is holding back, which is never what gets built with
     * @param stuck   whether the objective has stopped getting anywhere, which the surroundings alone
     *                cannot say
     */
    public static Surroundings around(LocalPlayer player, Reserve reserve, boolean stuck) {
        Level level = player.level();
        BlockPos feet = player.blockPosition();

        // Only what the body can see, or what is right on top of it. Underground the box reaches into
        // every cave within sixteen blocks, and three mobs behind solid rock had the tactics layer
        // choosing FIGHT against things it could not get to, then RETREAT from them, then FIGHT again,
        // while the coal in front of it went unmined.
        List<LivingEntity> hostiles = new ArrayList<>();
        for (Entity candidate : level.getEntities(player, player.getBoundingBox().inflate(THREAT_RANGE),
                entity -> entity instanceof Enemy && entity instanceof LivingEntity living && living.isAlive()
                        && (player.distanceTo(living) < CLOSE_ANYWAY || player.hasLineOfSight(living)))) {
            hostiles.add((LivingEntity) candidate);
        }
        hostiles.sort(Comparator.comparingInt(Surroundings::danger)
                .thenComparingDouble(player::distanceToSqr));

        TreeSet<String> kinds = new TreeSet<>();
        for (LivingEntity hostile : hostiles) {
            kinds.add(kindOf(hostile));
        }
        String threat = kinds.isEmpty() ? "NONE"
                : kinds.size() > KINDS_NAMED ? "MANY" : String.join("+", kinds);

        Perception.Distance nearest = Perception.Distance.NONE;
        if (!hostiles.isEmpty()) {
            double closest = hostiles.stream().mapToDouble(player::distanceTo).min().orElse(Double.MAX_VALUE);
            nearest = closest <= 4.0 ? Perception.Distance.CLOSE
                    : closest <= 10.0 ? Perception.Distance.NEAR : Perception.Distance.FAR;
        }

        // The surface is read off the rim rather than the body's own column: at the bottom of a pit the
        // body's column tops out at the body's feet, and a pit two blocks wide read as open ground.
        int surface = rimSurface(level, feet);
        Cover cover;
        if (!level.canSeeSky(feet.above())) {
            cover = Cover.ROOF;
        } else if (walledIn(level, feet) || feet.getY() <= surface - PIT_DEPTH) {
            cover = Cover.PIT;
        } else {
            cover = Cover.SKY;
        }

        Light light = level.isDarkOutside() ? Light.NIGHT
                : level.getMaxLocalRawBrightness(feet) < DARK_BELOW ? Light.DARK : Light.DAY;

        Inventory inventory = player.getInventory();
        boolean armed = Tool.SWORD.hotbarSlot(inventory) >= 0 || Tool.AXE.hotbarSlot(inventory) >= 0;

        return new Surroundings(hostiles, threat, Math.min(3, hostiles.size()), nearest, armed,
                Perception.healthOf(player), cover, light, blocks(player, reserve),
                Perception.canDigDown(player), roomAbove(level, feet), surface, stuck,
                player.position());
    }

    /**
     * Whether the surroundings call for a decision at all.
     *
     * <p>Something hostile in range always does. So does being out under the sky at night with nothing
     * to fight with, whether or not anything has turned up yet: by the time it has, the choice is made.
     * And so does an objective that has stopped getting anywhere while the body is under a roof or down
     * a pit, which is what being trapped looks like from inside — the thing to do about that is to get
     * back to the sky, and nothing else in the brain has that move.
     */
    public boolean demanding() {
        return count > 0
                || (light == Light.NIGHT && !armed && cover == Cover.SKY)
                || (cover != Cover.SKY && stuck);
    }

    /** The state key, in a fixed order so a table written today still reads tomorrow. */
    public String key() {
        // Coarser at night. The question then is shelter, and a lesson learned against a zombie at
        // mid range with half health has to serve against a skeleton close by at full: with every
        // distinction kept, each night was two hundred decisions spread over rows the last night
        // never visited, and the deaths taught nothing that carried over.
        boolean night = light == Light.NIGHT;
        String kinds = night ? (count == 0 ? "NONE" : "ANY") : threat;
        int howMany = night ? Math.min(2, count) : count;
        String near = night && (nearest == Perception.Distance.CLOSE || nearest == Perception.Distance.NEAR)
                ? "NEAR" : nearest.name();
        String hurt = night && health != Perception.Health.LOW ? "OK" : health.name();
        return kinds + howMany + '|' + near + '|' + (armed ? "A" : "-") + '|' + hurt
                + '|' + cover.name() + '|' + light.name() + '|' + stock();
    }

    /** {@code B} well stocked with blocks, {@code b} a few, {@code -} none. */
    private String stock() {
        return blocks >= WELL_STOCKED ? "B" : blocks > 0 ? "b" : "-";
    }

    /** The same reading in plain words, for the mentor. */
    public String words() {
        StringBuilder out = new StringBuilder();
        if (hostiles.isEmpty()) {
            out.append("nothing hostile in range");
        } else {
            out.append(hostiles.size()).append(" hostile").append(hostiles.size() == 1 ? "" : "s")
                    .append(" in range (").append(threat.toLowerCase(Locale.ROOT).replace('+', ' '))
                    .append("), the nearest ").append(nearest.name().toLowerCase(Locale.ROOT));
        }
        out.append("; ").append(armed ? "armed with a sword or an axe" : "nothing to fight with");
        out.append("; health ").append(health.name().toLowerCase(Locale.ROOT));
        out.append("; ").append(switch (cover) {
            case SKY -> "under open sky";
            case PIT -> "down a pit with the sky above";
            case ROOF -> "under a roof, no sky";
        });
        out.append("; ").append(switch (light) {
            case DAY -> "daytime";
            case NIGHT -> "night";
            case DARK -> "day outside but dark here";
        });
        out.append("; ").append(blocks == 0 ? "no blocks to build with"
                : blocks + " block" + (blocks == 1 ? "" : "s") + " to build with");
        out.append(canDig ? "; could dig down safely" : "; digging down here is not safe");
        out.append(roomAbove ? "; room overhead to stack up" : "; no room overhead to stack up");
        if (stuck) {
            out.append("; the objective has stopped getting anywhere");
        }
        return out.toString();
    }

    /** The hostile worth hitting first, or null when there is nothing worth hitting: creepers never. */
    public LivingEntity worstToFight() {
        for (LivingEntity hostile : hostiles) {
            if (!(hostile instanceof Creeper) && hostile.isAlive()) {
                return hostile;
            }
        }
        return null;
    }

    /** The nearest hostile of any kind still alive, measured from where the body was when it looked. */
    public LivingEntity nearestHostile() {
        LivingEntity best = null;
        double closest = Double.MAX_VALUE;
        for (LivingEntity hostile : hostiles) {
            if (!hostile.isAlive()) {
                continue;
            }
            double distance = hostile.position().distanceToSqr(at);
            if (distance < closest) {
                closest = distance;
                best = hostile;
            }
        }
        return best;
    }

    /** Whether anything on the list is a creeper, which changes what standing and fighting mean. */
    public boolean creeperAbout() {
        return hostiles.stream().anyMatch(hostile -> hostile instanceof Creeper && hostile.isAlive());
    }

    /**
     * How dangerous a kind of hostile is, lowest first. Skeletons shoot from where the body cannot reach
     * them and never stop, so they are dealt with first; creepers are second because they end fights;
     * zombies and spiders have to walk up to the body, which is where a sword works best.
     */
    private static int danger(LivingEntity hostile) {
        if (hostile instanceof AbstractSkeleton) {
            return 0;
        }
        if (hostile instanceof Creeper) {
            return 1;
        }
        if (hostile instanceof Zombie) {
            return 2;
        }
        return hostile instanceof Spider ? 3 : 4;
    }

    private static String kindOf(LivingEntity hostile) {
        if (hostile instanceof AbstractSkeleton) {
            return "SKELETON";
        }
        if (hostile instanceof Creeper) {
            return "CREEPER";
        }
        if (hostile instanceof Zombie) {
            return "ZOMBIE";
        }
        return hostile instanceof Spider ? "SPIDER" : "OTHER";
    }

    /** Solid on all four sides at both the feet and the head: nowhere to walk out. */
    private static boolean walledIn(Level level, BlockPos feet) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos beside = feet.relative(side);
            if (!solid(level, beside) || !solid(level, beside.above())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Three clear blocks over the head, and a spot under the feet a block can go into: enough to jump
     * and stack three times without hitting anything, and not standing in a slab where the first block
     * is refused before it starts.
     */
    private static boolean roomAbove(Level level, BlockPos feet) {
        if (!level.getBlockState(feet).canBeReplaced()) {
            return false;
        }
        for (int up = 2; up <= 4; up++) {
            BlockPos pos = feet.above(up);
            if (!level.isLoaded(pos) || !level.getBlockState(pos).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    /** How far out the rim of a pit is looked for, in blocks. */
    private static final int RIM = 3;

    /**
     * The highest surface around the body: the tallest column within a few blocks, read off the
     * heightmap. From the bottom of a hole that is the ground the hole is dug in.
     */
    private static int rimSurface(Level level, BlockPos feet) {
        int highest = level.getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ());
        for (int dx = -RIM; dx <= RIM; dx += RIM) {
            for (int dz = -RIM; dz <= RIM; dz += RIM) {
                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                if (level.hasChunk(x >> 4, z >> 4)) {
                    highest = Math.max(highest, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
                }
            }
        }
        return highest;
    }

    private static boolean solid(Level level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).isSolid();
    }

    /**
     * How many blocks the body could put down: building material the plan lets go of, counted across
     * the hotbar. Tables are not counted — a table is for crafting at, and walling a corridor with the
     * only table is a wall that cost a pickaxe.
     */
    private static int blocks(LocalPlayer player, Reserve reserve) {
        Inventory inventory = player.getInventory();
        InventoryCensus held = reserve.isEmpty() ? InventoryCensus.empty() : InventoryCensus.of(inventory);
        int total = 0;
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!Resource.buildsWith(stack) || !reserve.allowsPlacing(stack, held)) {
                continue;
            }
            if (Resource.of(stack).orElse(null) == Resource.CRAFTING_TABLE) {
                continue;
            }
            total += stack.getCount();
        }
        return total;
    }
}
