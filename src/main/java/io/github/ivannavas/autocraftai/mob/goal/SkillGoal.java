package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.CraftLog;
import io.github.ivannavas.autocraftai.mob.ai.FocusKind;
import io.github.ivannavas.autocraftai.mob.ai.Recipes;
import io.github.ivannavas.autocraftai.mob.ai.Sighting;
import io.github.ivannavas.autocraftai.mob.ai.objective.Reserve;
import io.github.ivannavas.autocraftai.mob.ai.objective.Resource;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import io.github.ivannavas.autocraftai.mob.ai.skill.Condition;
import io.github.ivannavas.autocraftai.mob.ai.skill.Readings;
import io.github.ivannavas.autocraftai.mob.ai.skill.Skill;
import io.github.ivannavas.autocraftai.mob.ai.skill.Skills;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runs a {@link Skill}: one step at a time, in the body's own frame, until the skill says it is done
 * or its budget is spent.
 *
 * <p>Every verb is something a goal here already did — the same strike as the mining goals, the same
 * placement, the same attack and retreat, the same clicks in a furnace or a crafting grid — so a skill
 * can do nothing a built-in move could not; what it can do is put them in an order nobody wrote. The
 * frame is re-read at the start of every step, from where the body stands and faces then, so "the
 * block ahead" is the block ahead now.
 *
 * <p>A step that is not finished in {@link Skill#STEP_TICKS} fails the skill, and a skill that runs
 * out its {@link Skill#BUDGET_TICKS} fails too. Failing is reported: the run counts it against the
 * skill, and a skill that keeps failing is retired.
 */
public final class SkillGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** How often the finishing condition is read. Every tick would be an entity query a tick. */
    private static final int UNTIL_EVERY = 10;
    /** Close enough to a walked-to spot to call the step done. */
    private static final double ARRIVED = 0.8;
    /** Close enough to a walked-to block of a named kind, which cannot be stood in: next to it. */
    private static final double BESIDE = 2.2;
    /** Ticks between clicks at a block or a menu, so a refused click is tried again and not spammed. */
    private static final int CLICK_EVERY = 8;
    /** The furnace's output slot, as vanilla numbers them. */
    private static final int FURNACE_OUTPUT = 2;

    private final Skill skill;
    private final Reserve reserve;
    private final Supplier<Readings> readings;

    private int step;
    private int stepTicks;
    private int totalTicks;
    /** Ticks charged to the budget: all of them but those spent waiting for a condition on purpose. */
    private int budgetTicks;
    private int sinceProgress;
    /** What the current step waits for, when it is a wait for a condition. */
    private Condition waitFor;
    private boolean done;
    private boolean failed;
    private String failure = "";

    private Direction facing;
    private BlockPos target;
    /** Whether the target is a block found by its kind rather than an offset, so it is stood beside. */
    private boolean named;
    /** Whether any step so far changed the world: struck a block, placed one, moved an item, hit a mob. */
    private boolean acted;
    /** How close the eyes must be to a named block before swinging at it. */
    private static final double REACH = 4.0;
    private MobGoal inner;
    private LivingEntity quarry;
    private boolean clicked;

    /**
     * @param readings how to read the surroundings afresh, for the finishing condition and the frame
     */
    public SkillGoal(Skill skill, Reserve reserve, Supplier<Readings> readings) {
        this.skill = skill;
        this.reserve = reserve == null ? Reserve.none() : reserve;
        this.readings = readings;
    }

    public Skill skill() {
        return skill;
    }

    /** Whether the skill gave up, and why, for the record. */
    public boolean failed() {
        return failed;
    }

    public String failure() {
        return failure;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        if (done || failed) {
            return false;
        }
        if (totalTicks > 0) {
            return true;
        }
        Readings now = readings.get();
        return now != null && skill.when().test(now);
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        return !done && !failed;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    /** Whether the run did anything at all, as opposed to finishing through steps that found nothing. */
    public boolean acted() {
        return acted;
    }

    @Override
    public int stalledTicks() {
        return sinceProgress;
    }

    @Override
    public void start(MobBody body) {
        if (totalTicks == 0) {
            // Counted here and not when the goal is installed: a goal the engine never gave the body to
            // was not a use of the skill, and counting it was what made an unstarted skill look retired.
            Skills.get().used(skill.name());
            begin(body, 0);
        }
    }

    @Override
    public void tick(MobBody body) {
        totalTicks++;
        Skill.Step what = skill.steps().get(step);
        boolean waiting = waitFor != null;
        if (waiting) {
            // Waiting for the day sealed in a hole is the skill doing what it says, not stalling, and
            // a night is longer than any budget: the minute is for the steps that do things.
            sinceProgress = 0;
        } else {
            sinceProgress++;
            budgetTicks++;
        }
        if (totalTicks % UNTIL_EVERY == 0 && !"true".equals(skill.until().text())) {
            Readings now = readings.get();
            if (now != null && skill.until().test(now)) {
                finish(body);
                return;
            }
        }
        if (budgetTicks > Skill.BUDGET_TICKS) {
            fail(body, "ran out its budget");
            return;
        }
        int allowed = waiting ? Skill.WAIT_FOR_TICKS
                : what.verb() == Skill.Verb.WAIT || what.verb() == Skill.Verb.HOLD
                ? Math.max(Skill.STEP_TICKS, what.amount() + 20) : Skill.STEP_TICKS;
        if (++stepTicks > allowed) {
            fail(body, "step " + (step + 1) + " (" + what.describe() + ") did not finish");
            return;
        }
        if (run(body, what)) {
            sinceProgress = 0;
            next(body);
        }
    }

    @Override
    public void stop(MobBody body) {
        body.moveControl().stop();
        Digging.stopBreaking();
        if (inner != null) {
            inner.stop(body);
            inner = null;
        }
    }

    /** Sets the frame for a step: where the body stands and faces as the step begins. */
    private void begin(MobBody body, int index) {
        step = index;
        stepTicks = 0;
        clicked = false;
        facing = body.player().getDirection();
        Skill.Step next = skill.steps().get(step);
        target = next.at() == null ? null : at(body, next.at());
        waitFor = next.verb() == Skill.Verb.WAIT && !next.word().isEmpty() ? Condition.parse(next.word()) : null;
        named = false;
        if (next.at() == null && (next.verb() == Skill.Verb.WALK || next.verb() == Skill.Verb.LOOK
                || next.verb() == Skill.Verb.BREAK
                || (next.verb() == Skill.Verb.USE && !"hand".equals(next.word())))) {
            Readings now = readings.get();
            target = now == null ? null : now.find(next.word()).orElse(null);
            if (target == null && now != null
                    && ("nearest".equals(next.word()) || "worst".equals(next.word()))) {
                // A hostile rather than a block: where it stands right now.
                LivingEntity hostile = "nearest".equals(next.word())
                        ? now.around().nearestHostile() : now.around().worstToFight();
                target = hostile == null ? null : hostile.blockPosition();
            }
            named = true;
            if (target == null) {
                fail(body, "no " + next.word() + " within reach");
            }
        }
        if (inner != null) {
            inner.stop(body);
            inner = null;
        }
        quarry = null;
    }

    private void next(MobBody body) {
        int following = step + 1;
        if (following >= skill.steps().size()) {
            if (skill.repeat()) {
                // Round again: a repeating skill with no finishing condition goes until its budget.
                begin(body, 0);
                return;
            }
            finish(body);
            return;
        }
        begin(body, following);
    }

    private void finish(MobBody body) {
        done = true;
        stop(body);
    }

    private void fail(MobBody body, String why) {
        failed = true;
        failure = why;
        stop(body);
    }

    /** One tick of a step. True when the step is finished. */
    private boolean run(MobBody body, Skill.Step what) {
        return switch (what.verb()) {
            case BREAK -> breakBlock(body);
            case PLACE -> place(body, what);
            case WALK -> walk(body);
            case LOOK -> look(body);
            case JUMP -> jump(body);
            case WAIT -> wait(body, what);
            case ATTACK -> attack(body, what.word());
            case FLEE -> flee(body, what.amount());
            case DIG -> dig(body);
            case SELECT -> select(body, what.word());
            case USE -> use(body, what);
            case HOLD -> hold(body, what.amount());
            case RECIPE -> recipe(body, what.word());
            case TAKE -> take(body, what.word());
            case PUT -> put(body, what.word());
            case CLOSE -> close(body);
        };
    }

    private boolean breakBlock(MobBody body) {
        Level level = body.level();
        if (!level.isLoaded(target) || level.getBlockState(target).isAir()
                || level.getBlockState(target).canBeReplaced()) {
            return true;
        }
        if (!Digging.breakable(body, target)) {
            fail(body, "cannot break " + level.getBlockState(target).getBlock().getName().getString());
            return false;
        }
        Vec3 centre = Vec3.atCenterOf(target);
        if (named && body.player().getEyePosition().distanceTo(centre) > REACH) {
            // A block named rather than pointed at may be across the clearing: walk up to it first.
            body.lookControl().lookAt(centre);
            body.moveControl().moveTo(Vec3.atBottomCenterOf(target), 1.0F);
            return false;
        }
        body.moveControl().stop();
        if (stepTicks == 1) {
            Digging.equip(body, target);
        }
        if (Digging.strike(body, target)) {
            sinceProgress = 0;
            acted = true;
        }
        return false;
    }

    private boolean place(MobBody body, Skill.Step what) {
        if (inner == null) {
            BlockPos where = what.at() == null ? null : target;
            if (where != null && !body.level().getBlockState(where).canBeReplaced()) {
                return true;
            }
            // What is in hand when it is a block — a table a select step put there — else a building block.
            LocalPlayer player = body.player();
            boolean holdingBlock = player.getMainHandItem().getItem() instanceof BlockItem;
            inner = holdingBlock ? new PlaceBlockGoal(where, player.getInventory().getSelectedSlot())
                    : new PlaceBlockGoal(where, reserve, false);
            if (!inner.canUse(body)) {
                fail(body, "nothing to place");
                return false;
            }
            inner.start(body);
        }
        inner.tick(body);
        if (inner.isDone()) {
            boolean stood = target == null || !body.level().getBlockState(target).canBeReplaced();
            inner.stop(body);
            inner = null;
            if (!stood) {
                fail(body, "the block would not go at " + target.toShortString());
                return false;
            }
            sinceProgress = 0;
            acted = true;
            return true;
        }
        if (!inner.canContinueToUse(body)) {
            fail(body, "placing gave up");
        }
        return false;
    }

    private boolean walk(MobBody body) {
        Vec3 there = Vec3.atBottomCenterOf(target);
        double dx = there.x - body.position().x;
        double dz = there.z - body.position().z;
        // Level with it as well as over it: a step up the pit wall is not climbed by standing under it.
        double dy = Math.abs(there.y - body.position().y);
        if (Math.sqrt(dx * dx + dz * dz) <= (named ? BESIDE : ARRIVED) && dy <= (named ? 1.5 : 0.6)) {
            body.moveControl().stop();
            return true;
        }
        body.lookControl().lookAt(there.add(0.0, 1.0, 0.0));
        body.moveControl().moveTo(there, 1.0F);
        return false;
    }

    private boolean look(MobBody body) {
        body.lookControl().lookAt(Vec3.atCenterOf(target));
        return stepTicks >= 5;
    }

    private boolean jump(MobBody body) {
        if (stepTicks == 1) {
            body.jump();
            return false;
        }
        return !body.onGround() || stepTicks >= 12;
    }

    private boolean wait(MobBody body, Skill.Step what) {
        body.moveControl().stop();
        if (waitFor != null) {
            if (stepTicks % UNTIL_EVERY != 0) {
                return false;
            }
            Readings now = readings.get();
            return now != null && waitFor.test(now);
        }
        return stepTicks >= what.amount();
    }

    private boolean attack(MobBody body, String which) {
        if (inner == null) {
            Readings now = readings.get();
            quarry = now == null ? null
                    : "nearest".equals(which) ? now.around().nearestHostile() : now.around().worstToFight();
            if (quarry == null) {
                return true;
            }
            inner = new AttackSightingGoal(Sighting.of(FocusKind.HOSTILE, quarry));
            inner.start(body);
        }
        if (!quarry.isAlive()) {
            inner.stop(body);
            inner = null;
            sinceProgress = 0;
            acted = true;
            return true;
        }
        inner.tick(body);
        if (inner.stalledTicks() < 20) {
            sinceProgress = 0;
            acted = true;
        }
        return false;
    }

    private boolean flee(MobBody body, int ticks) {
        if (inner == null) {
            Readings now = readings.get();
            quarry = now == null ? null : now.around().nearestHostile();
            if (quarry == null) {
                return true;
            }
            inner = new FleeSightingGoal(Sighting.of(FocusKind.HOSTILE, quarry));
            inner.start(body);
        }
        inner.tick(body);
        if (inner.stalledTicks() < 20) {
            // Getting away is doing something: a retreat that moved was "did nothing" twice and
            // forgotten by the coach for it before the night was out.
            sinceProgress = 0;
            acted = true;
        }
        if (stepTicks >= ticks) {
            inner.stop(body);
            inner = null;
            return true;
        }
        return false;
    }

    private boolean dig(MobBody body) {
        if (!body.onGround()) {
            return false;
        }
        BlockPos under = Digging.standingOn(body);
        Level level = body.level();
        if (target == null) {
            target = under;
        } else if (level.getBlockState(target).isAir()) {
            return true;
        }
        BlockPos below = target.below();
        if (!Digging.breakable(body, target) || !level.getFluidState(target).isEmpty()
                || !level.isLoaded(below) || !level.getBlockState(below).isSolid()
                || !level.getFluidState(below).isEmpty()) {
            fail(body, "digging down here is not safe");
            return false;
        }
        body.moveControl().stop();
        if (stepTicks == 1) {
            Digging.equip(body, target);
        }
        if (Digging.strike(body, target)) {
            sinceProgress = 0;
            acted = true;
        }
        return false;
    }

    /**
     * Puts a thing in the hand: a kind of tool, a building block, or an item by name — from the hotbar,
     * or swapped into it from the rest of the bag the way a player drags one down.
     */
    private boolean select(MobBody body, String what) {
        LocalPlayer player = body.player();
        Inventory inventory = player.getInventory();
        int slot = switch (what) {
            case "sword" -> Tool.SWORD.hotbarSlot(inventory);
            case "pickaxe" -> Tool.PICKAXE.hotbarSlot(inventory);
            case "axe" -> Tool.AXE.hotbarSlot(inventory);
            case "block" -> {
                // Building material from the hotbar first; failing that any block the plan lets go of,
                // from the hotbar or swapped in from the bag. The "blocks" reading counts the bag, and
                // a skill that read "blocks > 0" failed here with "nothing called block in the bag".
                int building = PlaceBlockGoal.hotbarSlotWithBuildingBlock(player, reserve);
                yield building >= 0 ? building
                        : PlaceBlockGoal.bringBlockToHotbar(player, reserve)
                                ? PlaceBlockGoal.hotbarSlotWithBlock(player, reserve) : -1;
            }
            case "hand" -> emptyHotbarSlot(inventory);
            default -> hotbarSlotCalled(inventory, what);
        };
        if (slot >= 0) {
            inventory.setSelectedSlot(slot);
            return true;
        }
        if ("hand".equals(what)) {
            return true;
        }
        int elsewhere = inventorySlotCalled(inventory, what);
        if (elsewhere < 0) {
            fail(body, "nothing called " + what + " in the bag");
            return false;
        }
        // Swap it into the selected hotbar slot: the same click a player makes with a number key over
        // an inventory slot. The inventory screen need not be open for the swap to be taken.
        int chosen = inventory.getSelectedSlot();
        gameMode().ifPresent(mode -> mode.handleContainerInput(player.inventoryMenu.containerId,
                elsewhere, chosen, ContainerInput.SWAP, player));
        return true;
    }

    private static int emptyHotbarSlot(Inventory inventory) {
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static int hotbarSlotCalled(Inventory inventory, String word) {
        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && Readings.isCalled(stack, word)) {
                return slot;
            }
        }
        return -1;
    }

    /** A main-inventory slot holding the thing, as the inventory menu numbers it, or -1. */
    private static int inventorySlotCalled(Inventory inventory, String word) {
        for (int slot = Inventory.SELECTION_SIZE; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && Readings.isCalled(stack, word)) {
                return slot;
            }
        }
        return -1;
    }

    /** Right-clicks a block, or the air with what is in hand. */
    private boolean use(MobBody body, Skill.Step what) {
        LocalPlayer player = body.player();
        if (what.at() == null && !named) {
            if (stepTicks % CLICK_EVERY != 1) {
                return clicked;
            }
            InteractionResult result = gameMode().map(mode -> mode.useItem(player, InteractionHand.MAIN_HAND))
                    .orElse(InteractionResult.PASS);
            clicked = true;
            acted = true;
            return result.consumesAction() || stepTicks > CLICK_EVERY * 3;
        }
        Vec3 centre = Vec3.atCenterOf(target);
        body.lookControl().lookAt(centre);
        body.moveControl().stop();
        if (stepTicks < 3 || stepTicks % CLICK_EVERY != 3) {
            return false;
        }
        BlockHitResult hit = body.level().clip(new ClipContext(player.getEyePosition(), centre,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        AbstractContainerMenu before = player.containerMenu;
        InteractionResult result = gameMode()
                .map(mode -> mode.useItemOn(player, InteractionHand.MAIN_HAND, hit))
                .orElse(InteractionResult.PASS);
        if (result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return player.containerMenu != before;
    }

    /** Keeps the use button down: eating, drinking, drawing a bow. */
    private boolean hold(MobBody body, int ticks) {
        LocalPlayer player = body.player();
        body.moveControl().stop();
        if (stepTicks == 1) {
            gameMode().ifPresent(mode -> mode.useItem(player, InteractionHand.MAIN_HAND));
        }
        body.holdUse();
        return stepTicks >= ticks || (stepTicks > 5 && !player.isUsingItem());
    }

    /**
     * Lays a recipe out from the recipe book, into the open grid or the body's own two-by-two, and is
     * done when the result slot holds it.
     */
    private boolean recipe(MobBody body, String word) {
        LocalPlayer player = body.player();
        Resource resource = Readings.resource(word);
        if (resource == null) {
            fail(body, "no plan resource called " + word);
            return false;
        }
        AbstractCraftingMenu menu = player.containerMenu instanceof AbstractCraftingMenu open
                ? open : player.inventoryMenu;
        if (resource.matches(menu.getResultSlot().getItem())) {
            return true;
        }
        if (stepTicks % CLICK_EVERY == 1) {
            Optional<net.minecraft.world.item.crafting.display.RecipeDisplayId> found =
                    Recipes.find(player, resource);
            if (found.isEmpty()) {
                fail(body, "the recipe book cannot make " + word + " from the bag");
                return false;
            }
            gameMode().ifPresent(mode -> mode.handlePlaceRecipe(menu.containerId, found.get(), false));
            acted = true;
        }
        return false;
    }

    /** Takes what a grid or a furnace made, with the same shift-click a player uses. */
    private boolean take(MobBody body, String what) {
        LocalPlayer player = body.player();
        AbstractContainerMenu menu = player.containerMenu;
        int index;
        if ("output".equals(what)) {
            if (!(menu instanceof AbstractFurnaceMenu)) {
                fail(body, "no furnace is open");
                return false;
            }
            index = FURNACE_OUTPUT;
        } else {
            AbstractCraftingMenu grid = menu instanceof AbstractCraftingMenu open ? open : player.inventoryMenu;
            menu = grid;
            index = grid.getResultSlot().index;
        }
        Slot slot = menu.getSlot(index);
        if (slot.getItem().isEmpty()) {
            return clicked;
        }
        if (stepTicks % CLICK_EVERY == 1) {
            CraftLog.get().record(slot.getItem().copy());
            AbstractContainerMenu target = menu;
            gameMode().ifPresent(mode -> mode.handleContainerInput(target.containerId, index, 0,
                    ContainerInput.QUICK_MOVE, player));
            clicked = true;
            acted = true;
        }
        return false;
    }

    /** Shifts a thing from the bag into the open furnace or chest, where its own rules put it. */
    private boolean put(MobBody body, String word) {
        LocalPlayer player = body.player();
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu) {
            fail(body, "no screen is open to put " + word + " into");
            return false;
        }
        Slot from = null;
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory() && !slot.getItem().isEmpty()
                    && Readings.isCalled(slot.getItem(), word)) {
                from = slot;
                break;
            }
        }
        if (from == null) {
            return clicked;
        }
        if (stepTicks % CLICK_EVERY == 1) {
            int index = from.index;
            gameMode().ifPresent(mode -> mode.handleContainerInput(menu.containerId, index, 0,
                    ContainerInput.QUICK_MOVE, player));
            clicked = true;
            acted = true;
            sinceProgress = 0;
        }
        return false;
    }

    private boolean close(MobBody body) {
        LocalPlayer player = body.player();
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
            Minecraft.getInstance().setScreenAndShow(null);
        }
        return true;
    }

    /** A position in the frame set when the step began. */
    private BlockPos at(MobBody body, int[] offset) {
        return body.player().blockPosition().relative(facing, offset[0]).above(offset[1])
                .relative(facing.getClockWise(), offset[2]);
    }

    private static Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        String where = skill.name() + "(" + (step + 1) + "/" + skill.steps().size() + ")";
        if (waitFor != null) {
            // Says what the body is waiting for and how long it has: a sealed hole waiting for day read
            // as a tactic that never ends from outside.
            return where + " waiting for '" + waitFor.text() + "' " + (stepTicks / 20) + "s";
        }
        return where;
    }
}
