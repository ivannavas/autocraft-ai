package io.github.ivannavas.autocraftai.mob.goal;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import io.github.ivannavas.autocraftai.mob.MobBody;
import io.github.ivannavas.autocraftai.mob.MobControl;
import io.github.ivannavas.autocraftai.mob.MobGoal;
import io.github.ivannavas.autocraftai.mob.ai.Sighting;
import io.github.ivannavas.autocraftai.mob.ai.WastedEffort;
import io.github.ivannavas.autocraftai.mob.ai.objective.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Walks up to a block and breaks it.
 *
 * <p>Two halves: out of range it is an approach, in range it holds the attack on the block. Breaking goes
 * through the same {@code MultiPlayerGameMode} calls the mouse would drive, so the server sees an ordinary
 * player mining and nothing needs installing on it.
 *
 * <h2>Why it swings only at what it is actually aimed at</h2>
 * The face comes from a raycast rather than from arithmetic on the body's position: vanilla mines what you
 * are looking at, and anything else asks the server to accept a break the player is not aimed at. Until the
 * head has finished turning, this goal turns and does not swing.
 *
 * <p>The raycast is its own rather than {@link Minecraft#hitResult}, and that is a bug fix. The crosshair
 * picks entities as well as blocks and reports whichever is nearer — and the item a block drops lands
 * exactly between the eye and the next block to break. So after one successful swing the crosshair could
 * be reporting a floating log while the body stood there, aimed at the tree, mining nothing. This one asks
 * only about blocks.
 *
 * <h2>Any tick that gets nowhere counts, and the count survives a restart</h2>
 * Not being able to see the block is only one way to get nowhere. The swing can be refused — the server
 * says no, the slot is wrong, the game is between block-break delays — and that looks identical from the
 * outside: aimed at the tree, arm not moving. So the counter is about progress rather than about sight,
 * and any tick that does not land a blow adds to it.
 *
 * <p>And it is not reset when the goal starts. A goal can be started several times inside one decision —
 * something with a higher claim on the legs takes them for a while and gives them back — and each of those
 * cancels the break in progress. Counting from each restart meant a goal that was being interrupted every
 * two seconds could never reach any limit at all, and would sit there aiming for the rest of its
 * commitment. The count belongs to the attempt, and the attempt is the whole decision.
 *
 * <h2>The right thing in the hand</h2>
 * Before the first swing it puts the block's tool in the hand if the hotbar has one. Which tool that is
 * comes from the objective when the planner named it and from Minecraft's own mineable tags otherwise —
 * see {@link Tool}. Without this the body swings with whatever was selected, which for most of a run is
 * nothing: punching stone drops no cobblestone at all, so an objective asking for it could never complete
 * however well the table had learned to choose mining.
 *
 * <p>When there is no such tool to be had it swings anyway and the time is booked to
 * {@link WastedEffort}. Refusing to swing would leave the tables with nothing to learn from — a move that
 * does nothing is invisible — where a move that costs something is a move they can learn to stop choosing.
 *
 * <h2>Why it refuses to be interrupted</h2>
 * A log takes about sixty ticks of punching; the brain re-decides every twenty. Every swap calls
 * {@link MultiPlayerGameMode#stopDestroyBlock()} and throws the progress away, so mining could only ever
 * finish if the same action came up three times running — which, before it has learned that mining pays,
 * it has no reason to. The goal would never once be rewarded for the thing it exists to do. So once a
 * break is genuinely under way this goal declares itself uninterruptable and runs to the end.
 */
public final class MineSightingGoal implements MobGoal {

    private static final Set<MobControl> CONTROLS = EnumSet.of(MobControl.MOVE, MobControl.LOOK);

    /** Kept under the server's reach so a block is never swung at from too far to land. */
    private static final double REACH_MARGIN = 0.5;
    /** A block that has not given way in this long is not going to; let the brain choose again. */
    private static final int GIVE_UP_TICKS = 300;
    /** In reach and getting nowhere for this long: something is wrong that standing there will not fix. */
    private static final int STALLED_TICKS = 30;
    private static final float SPEED = 1.0F;

    private final Sighting sighting;
    private final BlockPos target;
    private final Tool tool;

    // Counted from when this goal was made rather than from when it was last started, because a restart
    // is a symptom of the problem and not a fresh chance at it.
    private int ticksRunning;
    private int ticksStalled;
    /** What this attempt has to show for itself: ground covered on the way, blows landed once there. */
    private final Advance advance = new Advance();
    private boolean breaking;
    private boolean equipped;
    private boolean blocked;

    public MineSightingGoal(Sighting sighting, Tool tool) {
        this.sighting = sighting;
        this.target = sighting.blockPos();
        this.tool = tool == null ? Tool.HAND : tool;
    }

    @Override
    public Set<MobControl> controls() {
        return CONTROLS;
    }

    @Override
    public boolean canUse(MobBody body) {
        return target != null && !body.level().getBlockState(target).isAir();
    }

    @Override
    public boolean canContinueToUse(MobBody body) {
        // The two flags first: when the answer is already no, there is no reason to go and read the world.
        return !blocked && ticksRunning < GIVE_UP_TICKS && canUse(body);
    }

    /**
     * Whether this attempt has been abandoned: in reach of the block, and getting nowhere at it.
     *
     * <p>Asked by the brain, and only because of the rule that forces mining on sight. A rule with no way
     * out would insist on the same unreachable log for the rest of the run, so it yields to a block the
     * body has already proved it cannot get at.
     */
    public boolean gaveUp() {
        return blocked;
    }

    /** Only once the block is actually coming apart — before that there is no progress worth protecting. */
    @Override
    public boolean isInterruptable() {
        return !breaking;
    }

    /**
     * Getting nowhere here is one of two things, and both count: not closing on the block while walking to
     * it, and not landing a blow once standing at it. Like the counters above it belongs to the attempt
     * rather than to the run of it, so a goal that keeps losing the legs and getting them back does not
     * come back looking fresh each time.
     */
    @Override
    public int stalledTicks() {
        return advance.stalledTicks();
    }

    @Override
    public void start(MobBody body) {
        // Only what the stop actually undid: the break was cancelled and the held item may have changed
        // hands. The counters are the attempt's, and the attempt did not start again just because the
        // body came back.
        breaking = false;
        equipped = false;
    }

    @Override
    public void tick(MobBody body) {
        ticksRunning++;
        Vec3 centre = Vec3.atCenterOf(target);
        body.lookControl().lookAt(centre);

        if (!withinReach(body, centre)) {
            body.moveControl().moveTo(centre, SPEED);
            advance.walking(body);
            return;
        }

        // Close enough to swing: stand still, or the body drifts into the block it is breaking and the
        // straight-line steering starts shoving at the wall.
        body.moveControl().stop();
        equip(body);
        swing(body);
    }

    @Override
    public void stop(MobBody body) {
        breaking = false;
        body.moveControl().stop();
        gameMode().ifPresent(MultiPlayerGameMode::stopDestroyBlock);
    }

    private boolean withinReach(MobBody body, Vec3 centre) {
        double reach = body.player().blockInteractionRange() - REACH_MARGIN;
        return body.player().getEyePosition().distanceToSqr(centre) <= reach * reach;
    }

    /**
     * Selects the tool once, on arrival rather than every tick. Switching slots mid-break is what
     * {@code stopDestroyBlock} is for, and doing it repeatedly would cancel the break it is here to speed
     * up. With no such tool in the hotbar the hand stays as it is: slow beats not at all.
     */
    private void equip(MobBody body) {
        if (equipped) {
            return;
        }
        equipped = true;
        int slot = tool.hotbarSlot(body.player().getInventory());
        if (slot >= 0) {
            body.player().getInventory().setSelectedSlot(slot);
        }
    }

    private void swing(MobBody body) {
        BlockHitResult hit = aimedAtTarget(body);
        if (hit == null || !strike(body, hit)) {
            // Still turning, something solid in the way, or a blow the game would not take. All of them
            // look the same from here and all of them mean this tick got nowhere.
            breaking = false;
            advance.nothing();
            if (++ticksStalled >= STALLED_TICKS) {
                blocked = true;
            }
            return;
        }
        ticksStalled = 0;
        advance.progress();
    }

    /** One blow. False when the game would not take it, which is a tick that got nowhere like any other. */
    private boolean strike(MobBody body, BlockHitResult hit) {
        MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
        if (gameMode == null) {
            return false;
        }
        // continueDestroyBlock starts the break itself when the target is new, so this one call covers
        // both the first tick and every one after it.
        if (!gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
            return false;
        }
        Minecraft.getInstance().level.addBreakingBlockEffect(hit.getBlockPos(), hit.getDirection());
        body.player().swing(InteractionHand.MAIN_HAND);
        breaking = true;
        chargeForABareHandedSwing(body);
        return true;
    }

    /**
     * Books the tick against wasted effort when this swing will not drop anything.
     *
     * <p>The game's own test, not ours: a block that needs no tool comes back correct however empty the
     * hand is, so wood punched by hand costs nothing here and only stone, ore and their like do.
     */
    private void chargeForABareHandedSwing(MobBody body) {
        if (!body.player().hasCorrectToolForDrops(body.level().getBlockState(target))) {
            WastedEffort.get().wastedSwing();
        }
    }

    /**
     * A raycast from the eye to the block, and the face it enters by — or null when something else is in
     * the way or the head has not finished turning.
     *
     * <p>Blocks only. The crosshair the game keeps would do most of this and also picks entities, which is
     * the difference between mining a tree and standing in front of one because the log you just broke is
     * floating between you and the next.
     */
    private BlockHitResult aimedAtTarget(MobBody body) {
        LocalPlayer player = body.player();
        BlockHitResult hit = body.level().clip(new ClipContext(
                player.getEyePosition(), Vec3.atCenterOf(target),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target) ? hit : null;
    }

    private Optional<MultiPlayerGameMode> gameMode() {
        return Optional.ofNullable(Minecraft.getInstance().gameMode);
    }

    @Override
    public String name() {
        return "Mine(" + sighting.kind() + (tool == Tool.HAND ? "" : ", " + tool.name()) + ")";
    }
}
