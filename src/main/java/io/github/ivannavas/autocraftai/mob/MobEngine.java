package io.github.ivannavas.autocraftai.mob;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Runs the local player as a mob.
 *
 * <p>There is nothing to switch on. As soon as the mod is loaded the keyboard and the mouse stop reaching
 * the body: the mixins in {@code io.github.ivannavas.autocraftai.mixin} throw the human's input away and
 * hand the game whatever the goals decided instead. With no goal running that is an empty command buffer,
 * so the body simply stands there. The player is a passenger in its own head for as long as the mod is
 * installed.
 *
 * <p>Goals can be added and removed at any time. The arbitration is vanilla's: goals are tried in priority
 * order (lowest number first), a goal only starts when every control it wants is free, and a running goal
 * loses its controls to a higher priority one unless it declared itself uninterruptable.
 */
@Slf4j
public final class MobEngine {

    private static final MobEngine INSTANCE = new MobEngine();

    // Copy on write so a goal can register or drop another goal from inside its own tick: the change lands
    // on the next pass instead of breaking the iteration it was made during.
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Map<MobControl, Entry> owners = new EnumMap<>(MobControl.class);
    private final MobBody body = new MobBody();

    private MobEngine() {
    }

    public static MobEngine get() {
        return INSTANCE;
    }

    /** The body the goals drive. Read by the input mixins to replace the human's input. */
    public MobBody body() {
        return body;
    }

    // --- goals ------------------------------------------------------------------------------------

    /** @param priority lower runs first and wins contested controls */
    public void addGoal(int priority, MobGoal goal) {
        entries.add(new Entry(priority, goal));
        entries.sort(Comparator.comparingInt(entry -> entry.priority));
        log.debug("Goal added: {} (priority {})", goal.name(), priority);
    }

    public boolean removeGoal(MobGoal goal) {
        Entry entry = entries.stream().filter(candidate -> candidate.goal == goal).findFirst().orElse(null);
        if (entry == null) {
            return false;
        }
        stop(entry);
        entries.remove(entry);
        log.debug("Goal removed: {}", goal.name());
        return true;
    }

    public void clearGoals() {
        entries.forEach(this::stop);
        entries.clear();
    }

    public List<MobGoal> goals() {
        return entries.stream().map(entry -> entry.goal).toList();
    }

    /** Whether this goal currently holds its controls. */
    public boolean isRunning(MobGoal goal) {
        return entries.stream().anyMatch(entry -> entry.goal == goal && entry.running);
    }

    /**
     * Whether this goal is running and has said it must not be interrupted. Callers that swap goals from
     * outside the engine have to ask, or {@link MobGoal#isInterruptable()} would only protect a goal from
     * its peers and not from whatever installed it.
     */
    public boolean isCommitted(MobGoal goal) {
        return entries.stream()
                .anyMatch(entry -> entry.goal == goal && entry.running && !goal.isInterruptable());
    }

    /** Goals currently holding controls, in priority order. */
    public List<MobGoal> runningGoals() {
        return entries.stream().filter(entry -> entry.running).map(entry -> entry.goal).toList();
    }

    // --- tick -------------------------------------------------------------------------------------

    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || player.isRemoved() || player.isDeadOrDying()) {
            // No body to drive: let go rather than leaving stale commands latched on a player that may come
            // back as a different entity after a respawn or a dimension change.
            release();
            return;
        }

        body.beginTick(player);
        selectGoals();
        entries.stream().filter(entry -> entry.running).forEach(entry -> entry.goal.tick(body));
        body.driveControls();
    }

    /** Stops every goal and empties the command buffer, so nothing stale is left driving the body. */
    private void release() {
        entries.forEach(this::stop);
        owners.clear();
        body.reset();
    }

    private void selectGoals() {
        for (Entry entry : entries) {
            if (entry.running && !entry.goal.canContinueToUse(body)) {
                stop(entry);
            }
        }

        owners.clear();
        for (Entry entry : entries) {
            if (entry.running) {
                entry.goal.controls().forEach(control -> owners.put(control, entry));
            }
        }

        for (Entry entry : entries) {
            if (entry.running || !controlsAvailableFor(entry) || !entry.goal.canUse(body)) {
                continue;
            }
            for (MobControl control : entry.goal.controls()) {
                stop(owners.get(control));
                owners.put(control, entry);
            }
            entry.running = true;
            entry.goal.start(body);
            log.debug("Goal started: {}", entry.goal.name());
        }
    }

    private boolean controlsAvailableFor(Entry entry) {
        for (MobControl control : entry.goal.controls()) {
            Entry owner = owners.get(control);
            if (owner != null && (owner.priority <= entry.priority || !owner.goal.isInterruptable())) {
                return false;
            }
        }
        return true;
    }

    private void stop(Entry entry) {
        if (entry == null || !entry.running) {
            return;
        }
        entry.running = false;
        entry.goal.controls().forEach(control -> owners.remove(control, entry));
        entry.goal.stop(body);
        log.debug("Goal stopped: {}", entry.goal.name());
    }

    private static final class Entry {
        private final int priority;
        private final MobGoal goal;
        private boolean running;

        private Entry(int priority, MobGoal goal) {
            this.priority = priority;
            this.goal = goal;
        }
    }
}
