package io.github.ivannavas.autocraftai.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Throws away every saved world and starts a fresh one, on the game thread, over several ticks.
 *
 * <h2>Why this is a state machine and not a method</h2>
 * The obvious version — leave the world, delete the folder, make a new one — is three things that cannot
 * happen in the same tick. Leaving is asynchronous: the integrated server keeps running for a while after
 * the client has let go of the level, and while it runs it holds {@code session.lock} open in the very
 * directory that is about to be deleted. On Windows an open handle is not merely untidy, it is a failed
 * delete. So each step waits for the last one to actually be finished rather than for it to have been
 * asked for.
 *
 * <p>The HTTP handler does not sit on the game thread waiting for any of this. It is handed a future,
 * which this completes when the world is either up or definitely not coming, and the endpoint answers
 * with whichever happened.
 *
 * <h2>What counts as a world</h2>
 * Only a directory with a {@code level.dat} in it. The saves folder is a place people put things, and an
 * endpoint that empties a directory is a much worse thing to get wrong than one that leaves a stray
 * folder behind.
 */
@Slf4j
public final class NewWorld {

    /**
     * Where the request came from and what it is waiting for.
     *
     * @param create false to only leave the world, which is how the run is put down without replacing
     *               it: the game ends up sitting on its title screen with nothing to decide
     */
    private record Request(String name, OptionalLong seed, boolean create,
                           CompletableFuture<String> done) {
    }

    private enum Step {
        /** Nothing in flight. */
        IDLE,
        /** Asked to leave; waiting for the integrated server to actually stop. */
        LEAVING,
        /** Server gone; deleting what is on disk, retrying while a handle lingers. */
        CLEANING,
        /** Handed to the game's own world creation, which shows its progress screen. */
        CREATING
    }

    /** Ticks in a second, which every wait below is expressed in. */
    private static final int SECOND = 20;
    /** How long to wait for the integrated server to shut down before giving up on it. */
    private static final int LEAVE_TIMEOUT = 45 * SECOND;
    /** How long to keep retrying a delete that a lingering file handle is refusing. */
    private static final int CLEAN_TIMEOUT = 20 * SECOND;
    /** How long to wait for the new world to actually have a player in it. */
    private static final int CREATE_TIMEOUT = 120 * SECOND;
    /**
     * A breath between the server disappearing and the first delete.
     *
     * <p>{@code getSingleplayerServer() == null} means the client has let go, not that the server thread
     * has finished closing its region files. One second costs nothing here and removes the common case of
     * the first attempt failing.
     */
    private static final int SETTLE = SECOND;

    private final AtomicReference<Request> queued = new AtomicReference<>();

    /** Whether the world already on disk should be walked back into when the game comes up. */
    private final boolean autoOpen;

    /** Once per process. A world that was left on purpose should stay left. */
    private boolean autoOpenTried;

    /**
     * Raised when the game has drawn its title screen.
     *
     * <p>Which screen is up is not something {@link Minecraft} exposes any more, so this comes from the
     * same screen event the title-screen button is installed with rather than from polling a field.
     */
    private volatile boolean atTitle;

    private Request current;
    private Step step = Step.IDLE;
    private int waited;

    /**
     * Where the intent to stay stopped is written down.
     *
     * <p>A file rather than a field, because the thing it has to survive is the process. Otherwise
     * stopping the run and then having the box reboot — or the service restart on its own — would put
     * the game straight back into the world, which is the opposite of what was asked for and would
     * happen quietly, hours later.
     */
    private final Path idleMarker;

    public NewWorld(boolean autoOpen, Path directory) {
        this.autoOpen = autoOpen;
        this.idleMarker = directory.resolve("idle");
    }

    /**
     * Listens for the title screen, so {@link #resume(Minecraft)} knows when the game is ready to be
     * handed a world. A no-op unless reopening was asked for, so nothing is registered that is not used.
     */
    public void install() {
        if (!autoOpen) {
            log.info("Reopening the saved world is off; the run waits to be asked for one");
            return;
        }
        log.info("Reopening the saved world is on; waiting for the title screen");
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                atTitle = true;
            }
        });
    }

    /**
     * Asks for a new world. Safe to call from any thread; the work happens on the next client tick.
     *
     * @return a future carrying the name of the world that came up, or failing with why it did not
     */
    public CompletableFuture<String> request(String name, OptionalLong seed) {
        return submit(new Request(name, seed, true, new CompletableFuture<>()));
    }

    /**
     * Puts the run down: leaves the world and stays out of it.
     *
     * <p>The brain has nothing to decide without a player — its tick drops the episode and saves — so
     * leaving is all "stop" has to mean. Nothing is deleted and nothing is lost; the world is still
     * there for {@code /world/new} or a restart to pick up.
     *
     * @return a future that completes once the game is back at its title screen
     */
    public CompletableFuture<String> stop() {
        return submit(new Request("", OptionalLong.empty(), false, new CompletableFuture<>()));
    }

    private CompletableFuture<String> submit(Request request) {
        CompletableFuture<String> done = request.done();
        if (!queued.compareAndSet(null, request)) {
            done.completeExceptionally(new IllegalStateException(
                    "Something is already being done to the world; wait for it to finish"));
            return done;
        }
        if (step != Step.IDLE) {
            queued.set(null);
            done.completeExceptionally(new IllegalStateException(
                    "Something is already being done to the world; wait for it to finish"));
        }
        return done;
    }

    /** Whether the run was stopped on purpose and should stay stopped across a restart. */
    public boolean stopped() {
        return Files.exists(idleMarker);
    }

    private void mark(boolean idle) {
        try {
            if (idle) {
                Files.writeString(idleMarker, "Stopped from the control API. Delete this, or ask for a "
                        + "new world, to let the run start itself again.\n");
            } else {
                Files.deleteIfExists(idleMarker);
            }
        } catch (IOException e) {
            // Worth saying, not worth failing over: the request itself still did what it said.
            log.warn("Could not write {}; the stop will not survive a restart", idleMarker, e);
        }
    }

    /** What the status endpoint reports: which step is running, or that none is. */
    public String state() {
        return step.name().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean busy() {
        return step != Step.IDLE;
    }

    /**
     * One step per tick, on the game thread.
     *
     * <p>Every branch either moves on, keeps waiting, or fails — a step that can do none of those would be
     * a machine that sits in a state forever, which is why each waiting branch is bounded.
     */
    public void tick(Minecraft client) {
        switch (step) {
            case IDLE -> begin(client);
            case LEAVING -> leaving(client);
            case CLEANING -> cleaning(client);
            case CREATING -> creating(client);
        }
    }

    private void begin(Minecraft client) {
        Request request = queued.getAndSet(null);
        if (request == null) {
            resume(client);
            return;
        }
        autoOpenTried = true;
        current = request;
        waited = 0;
        // Asking for a world clears the intent to stay stopped; asking to stop sets it.
        mark(!request.create());
        boolean empty = client.level == null && client.getSingleplayerServer() == null;
        if (empty) {
            if (!request.create()) {
                log.info("Stop requested with nothing open; already idle");
                finish("Already sitting at the menu");
                return;
            }
            log.info("New world requested with nothing open; going straight to cleaning up");
            step = Step.CLEANING;
            return;
        }
        log.info(request.create()
                ? "New world requested; leaving the one that is open"
                : "Stop requested; leaving the world");
        // Saving on the way out of a world that is about to be deleted looks wasteful, and is: this is
        // the call that stops the server cleanly, and a clean stop is what closes the file handles.
        client.disconnectWithSavingScreen();
        step = Step.LEAVING;
    }

    /**
     * Walks back into the world that is already saved, once, when the game first sits at the title.
     *
     * <p>Waiting for the title screen rather than only for the level to be null is what makes this safe
     * to call every tick: the title screen is the one moment the game is idle and ready to be handed a
     * world, and it is reached exactly once per launch.
     *
     * <p>Only ever the most recently played one. There is normally only one — the endpoint that makes a
     * world deletes the rest — and picking the newest is the same answer in the case where somebody has
     * left an older one behind.
     */
    private void resume(Minecraft client) {
        if (!autoOpen || autoOpenTried || !atTitle || client.level != null) {
            return;
        }
        autoOpenTried = true;
        if (stopped()) {
            log.info("The run was stopped on purpose; leaving the game at its menu");
            return;
        }
        String level = newest(client.getLevelSource().getBaseDir());
        if (level == null) {
            log.info("Nothing saved to reopen; waiting for a world to be asked for");
            return;
        }
        log.info("Reopening the saved world '{}'", level);
        client.createWorldOpenFlows().openWorld(level, () -> {
        });
    }

    /** @return the directory name of the most recently touched saved world, or null if there is none */
    private static String newest(Path saves) {
        if (!Files.isDirectory(saves)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(saves)) {
            return entries.filter(NewWorld::isWorld)
                    .max(Comparator.comparingLong(NewWorld::touched))
                    .map(world -> world.getFileName().toString())
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Could not look in {} for a world to reopen", saves, e);
            return null;
        }
    }

    /** Unreadable sorts oldest, so a directory that cannot be stat'd never wins the pick. */
    private static long touched(Path world) {
        try {
            return Files.getLastModifiedTime(world.resolve("level.dat")).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private void leaving(Minecraft client) {
        if (client.level == null && client.getSingleplayerServer() == null) {
            waited = 0;
            // Send the game back to its menu, because nothing else will.
            //
            // disconnectWithSavingScreen() puts up "Saving world" for the duration of the disconnect
            // and leaves it there: in vanilla the caller — the pause menu's Save and Quit — sets the
            // title screen once it returns. Without this the server has stopped, the level is gone and
            // the client reports itself out of the world, while the screen still says it is saving.
            // That reads as a hung save rather than as a finished one, and it is what goes out on air.
            client.setScreenAndShow(new TitleScreen());
            if (!current.create()) {
                log.info("Stopped; the game is at its menu with nothing to decide");
                finish("Stopped — Minecraft is sitting at the menu");
                return;
            }
            step = Step.CLEANING;
            return;
        }
        if (++waited > LEAVE_TIMEOUT) {
            fail(client, "The game did not finish leaving the open world within "
                    + LEAVE_TIMEOUT / SECOND + "s");
        }
    }

    private void cleaning(Minecraft client) {
        if (++waited < SETTLE) {
            return;
        }
        Path saves = client.getLevelSource().getBaseDir();
        try {
            int removed = wipe(saves);
            log.info("Removed {} saved world(s) from {}", removed, saves);
            waited = 0;
            step = Step.CREATING;
            create(client);
        } catch (IOException e) {
            // Almost always a handle that has not been let go yet, so it is worth another tick rather
            // than an error the operator can do nothing about.
            if (waited > CLEAN_TIMEOUT) {
                fail(client, "Could not delete the old worlds in " + saves + ": " + e.getMessage());
            }
        }
    }

    /**
     * Deletes every saved world under {@code saves}.
     *
     * @return how many went
     * @throws IOException if one of them would not go, with nothing half-deleted left unreported
     */
    private static int wipe(Path saves) throws IOException {
        if (!Files.isDirectory(saves)) {
            return 0;
        }
        List<Path> worlds;
        try (Stream<Path> entries = Files.list(saves)) {
            worlds = entries.filter(NewWorld::isWorld).toList();
        }
        for (Path world : worlds) {
            // Deepest first, because a directory only goes once it is empty.
            try (Stream<Path> tree = Files.walk(world)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        return worlds.size();
    }

    private static boolean isWorld(Path candidate) {
        return Files.isDirectory(candidate)
                && (Files.exists(candidate.resolve("level.dat"))
                        || Files.exists(candidate.resolve("level.dat_old")));
    }

    /**
     * Hands the new world to the game's own creation flow.
     *
     * <p>Survival and normal, as asked, and not hardcore: a hardcore death ends the run rather than
     * teaching the table anything, and the whole point of the thing on screen is that it keeps trying.
     */
    private void create(Minecraft client) {
        String name = current.name();
        LevelSettings settings = new LevelSettings(
                name,
                GameType.SURVIVAL,
                new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false),
                false,
                WorldDataConfiguration.DEFAULT);
        WorldOptions options = current.seed().isPresent()
                ? new WorldOptions(current.seed().getAsLong(), true, false)
                : WorldOptions.defaultWithRandomSeed();
        try {
            // The directory id is fixed rather than derived from the name: everything else was just
            // deleted, so there is nothing to collide with, and a stable folder is one less thing that
            // changes shape between runs.
            client.createWorldOpenFlows().createFreshLevel(
                    "autocraft-ai", settings, options, WorldPresets::createNormalWorldDimensions,
                    new TitleScreen());
        } catch (Exception e) {
            log.warn("Could not create the new world", e);
            fail(client, "The game refused to create the world: " + e.getMessage());
        }
    }

    /**
     * Waits for the world to have a player standing in it.
     *
     * <p>Reporting success the moment {@code createFreshLevel} returned would be reporting that the work
     * was started. Generating a world takes a while on a machine with no graphics card, and the panel that
     * asked for it wants to know when the run is actually going.
     */
    private void creating(Minecraft client) {
        if (client.player != null && client.level != null) {
            log.info("New world '{}' is up", current.name());
            current.done().complete(current.name());
            current = null;
            step = Step.IDLE;
            return;
        }
        if (++waited > CREATE_TIMEOUT) {
            fail(client, "The world was created but nobody spawned into it within "
                    + CREATE_TIMEOUT / SECOND + "s");
        }
    }

    private void finish(String what) {
        if (current != null) {
            current.done().complete(what);
            current = null;
        }
        step = Step.IDLE;
        waited = 0;
    }

    /**
     * Gives up on the request in hand and puts the game somewhere it can be looked at.
     *
     * <p>The screen matters as much as the message. Every way this fails leaves the game on whatever
     * progress screen the step was showing — "Saving world" most often — and a stuck progress screen is
     * indistinguishable from a hung one to anyone watching, including on the stream.
     */
    private void fail(Minecraft client, String why) {
        log.warn("The world request failed: {}", why);
        if (client.level == null) {
            client.setScreenAndShow(new TitleScreen());
        }
        if (current != null) {
            current.done().completeExceptionally(new IllegalStateException(why));
            current = null;
        }
        step = Step.IDLE;
        waited = 0;
    }
}
