package io.github.ivannavas.autocraftai.mob.ai.skill;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.github.ivannavas.autocraftai.mob.ai.Perception;
import io.github.ivannavas.autocraftai.mob.ai.Surroundings;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a skill's conditions can see: the surroundings as the tactics layer reads them, a few facts
 * about the body and its bag, what screen is open, and the blocks around it in the body's own frame.
 *
 * <p>Everything here is something the run already reads for its own tables. A skill is written in
 * words like {@code cover == PIT and blocks == 0 and solid(1,0,0)} or
 * {@code has(RAW_IRON) > 0 and near(furnace) and menu == NONE}, and this is what those words are
 * looked up in. Nothing is invented for skills; a skill can only condition on what the tables can
 * already be keyed by, which is the point — a skill is a new answer, not a new question.
 *
 * <h2>The body's frame</h2>
 * Block positions are given as {@code (forward, up, right)} from the feet, along whichever of the four
 * compass directions the body faces. So {@code solid(1,0,0)} is the block in front at foot height,
 * {@code air(0,2,0)} the block over the head, {@code breakable(1,1,0)} the one in front of the face.
 * A skill that says "break the block ahead and step onto it" means the same thing whichever way the
 * body happens to be looking, which is what lets one skill be learned in every corridor there is.
 *
 * <h2>Things by name</h2>
 * {@code has}, {@code short} and {@code near} take a word, and the word is looked up two ways: as one
 * of the plan's own resources ({@code LOG}, {@code RAW_IRON}) and, failing that, as a Minecraft item or
 * block id ({@code ender_pearl}, {@code water_bucket}, {@code furnace}). The plan's words come first
 * because they are what the objective is written in; the game's are there for everything the plan has
 * no word for.
 */
public final class Readings {

    /** How far out {@code near(x)} looks, in blocks. What a walk to a table or a furnace is worth. */
    private static final int NEAR = 8;
    private static final int NEAR_UP = 3;

    private final LocalPlayer player;
    private final Level level;
    private final BlockPos feet;
    private final Direction facing;
    private final Surroundings around;
    private final boolean wet;
    private final Map<Resource, Integer> needs;

    private Readings(LocalPlayer player, Surroundings around, boolean wet, Map<Resource, Integer> needs) {
        this.player = player;
        this.level = player.level();
        this.feet = player.blockPosition();
        this.facing = player.getDirection();
        this.around = around;
        this.wet = wet;
        this.needs = needs == null ? Map.of() : needs;
    }

    /**
     * Reads the body and its surroundings as they stand. Must be called on the client thread.
     *
     * @param needs what the plan says the run has to be holding, for {@code short(x)}
     */
    public static Readings of(LocalPlayer player, Surroundings around, boolean wet,
                              Map<Resource, Integer> needs) {
        return new Readings(player, around, wet, needs);
    }

    public Surroundings around() {
        return around;
    }

    public Direction facing() {
        return facing;
    }

    /**
     * The value of a named reading, or null when there is no reading of that name. Words come back as
     * upper-case strings, counts as integers, facts as booleans.
     */
    public Object value(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "cover" -> around.cover().name();
            case "light" -> around.light().name();
            case "threat" -> around.threat();
            case "hostiles" -> around.count();
            case "nearest" -> around.nearest().name();
            case "armed" -> around.armed();
            case "armour", "armor" -> io.github.ivannavas.autocraftai.mob.ai.Armoury.worn(player);
            case "health" -> around.health().name();
            case "blocks" -> around.blocks();
            case "candig" -> around.canDig();
            case "roomabove" -> around.roomAbove();
            case "stuck" -> around.stuck();
            case "surface" -> around.surface();
            case "y" -> feet.getY();
            case "depth" -> around.surface() - feet.getY();
            case "sky" -> level.canSeeSky(feet.above());
            // The world's clock, wherever the body is. The light reading is what the body sees, and a
            // body sealed in a hole underground sees DARK all day: a wait for "day" there never ended.
            case "night" -> level.isDarkOutside();
            case "day" -> !level.isDarkOutside();
            case "wet" -> wet;
            case "hungry" -> Perception.isHungry(player);
            case "food" -> player.getFoodData().getFoodLevel();
            case "fuel" -> fuel();
            case "pickaxe" -> Tool.PICKAXE.hotbarSlot(player.getInventory()) >= 0;
            case "sword" -> Tool.SWORD.hotbarSlot(player.getInventory()) >= 0;
            case "onground" -> player.onGround();
            case "menu" -> menu();
            default -> null;
        };
    }

    /** What screen is open: a crafting table's, a furnace's, a chest's, something else's, or none. */
    public String menu() {
        if (player.containerMenu == null || player.containerMenu == player.inventoryMenu) {
            return "NONE";
        }
        if (player.containerMenu instanceof AbstractCraftingMenu) {
            return "CRAFTING";
        }
        if (player.containerMenu instanceof AbstractFurnaceMenu) {
            return "FURNACE";
        }
        return player.containerMenu instanceof ChestMenu ? "CHEST" : "OTHER";
    }

    /** How many of a thing the bag holds, by the plan's word for it or the game's. */
    /** How many things a furnace would burn are carried: coal, charcoal, planks, logs, sticks. */
    public int fuel() {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && level.fuelValues().isFuel(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public int count(String word) {
        Inventory inventory = player.getInventory();
        Resource resource = resource(word);
        if (resource != null) {
            return resource.countIn(inventory);
        }
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && isCalled(stack, word)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** How many more of a plan resource the list wants than the bag holds; zero for anything else. */
    public int shortOf(String word) {
        Resource resource = resource(word);
        if (resource == null) {
            return 0;
        }
        return Math.max(0, needs.getOrDefault(resource, 0) - resource.countIn(player.getInventory()));
    }

    /**
     * Whether a block of that kind stands within a few blocks: a plan resource's block, or a block id.
     * {@code table} is a crafting table and {@code water} and {@code lava} are the fluids.
     */
    public boolean near(String word) {
        return find(word).isPresent();
    }

    /** The nearest block of that kind within a few blocks, for a step that walks to it or uses it. */
    public Optional<BlockPos> find(String word) {
        String wanted = switch (word) {
            case "table", "workbench" -> "crafting_table";
            default -> word;
        };
        Resource resource = resource(word);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-NEAR, -NEAR_UP, -NEAR),
                feet.offset(NEAR, NEAR_UP, NEAR))) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            double distance = pos.distSqr(feet);
            if (distance >= bestDistance) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            boolean matches = (resource != null && resource.inWorld().map(test -> test.test(state)).orElse(false))
                    || ("water".equals(wanted) && level.getFluidState(pos).is(FluidTags.WATER))
                    || ("lava".equals(wanted) && level.getFluidState(pos).is(FluidTags.LAVA))
                    || (!state.isAir() && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals(wanted));
            if (matches) {
                best = pos.immutable();
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The plan resource a word names, or null when it names none. */
    public static Resource resource(String word) {
        String upper = word.toUpperCase(Locale.ROOT);
        for (Resource candidate : Resource.values()) {
            if (candidate.name().equals(upper)) {
                return candidate;
            }
        }
        return null;
    }

    /** Whether a stack is the item a word names: by the plan's word, or by the item's own id. */
    public static boolean isCalled(ItemStack stack, String word) {
        Resource resource = resource(word);
        if (resource != null) {
            return resource.matches(stack);
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals(word);
    }

    /** The block at an offset in the body's frame: forward along the facing, up, and to the right. */
    public BlockPos at(int forward, int up, int right) {
        return feet.relative(facing, forward).above(up).relative(facing.getClockWise(), right);
    }

    /** Whether the block at the offset stops a body: anything with a collision box. */
    public boolean solid(int forward, int up, int right) {
        BlockPos pos = at(forward, up, right);
        return level.isLoaded(pos) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Whether the block at the offset is nothing at all, or something a placed block pushes aside. */
    public boolean air(int forward, int up, int right) {
        BlockPos pos = at(forward, up, right);
        return level.isLoaded(pos) && level.getBlockState(pos).canBeReplaced()
                && level.getFluidState(pos).isEmpty();
    }

    /** Whether the block at the offset could be broken with what is in the hotbar, in reasonable time. */
    public boolean breakable(int forward, int up, int right) {
        BlockPos pos = at(forward, up, right);
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        return Tool.canHarvest(player.getInventory(), state);
    }

    /** Whether the block at the offset is water. */
    public boolean water(int forward, int up, int right) {
        BlockPos pos = at(forward, up, right);
        return level.isLoaded(pos) && level.getFluidState(pos).is(FluidTags.WATER);
    }

    /** Whether the block at the offset is lava, which no skill should ever open onto. */
    public boolean lava(int forward, int up, int right) {
        BlockPos pos = at(forward, up, right);
        return level.isLoaded(pos) && level.getFluidState(pos).is(FluidTags.LAVA);
    }
}
