package io.github.ivannavas.autocraftai.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the last minute of the run in memory and writes it out whenever an objective is reached.
 *
 * <h2>Why a buffer rather than a recording</h2>
 * The interesting part of a run is the minute before something happened, and the rest is a body walking
 * about. Recording continuously and cutting it up afterwards means writing gigabytes an hour to throw
 * nearly all of it away, on a box whose disk also holds the world. OBS's replay buffer keeps its window
 * in memory and lets the rest fall off the end, so discarding is not a job anybody has to do — it is
 * what the thing does when left alone.
 *
 * <h2>Off the game thread</h2>
 * Reaching an objective is announced from the client tick. Saving means a websocket round trip and then
 * waiting for OBS to finish encoding a minute of video, which is seconds — so what the game thread does
 * here is drop a name into a queue and carry on. One worker, because two saves at once would have the
 * pair of them racing to name the same file and OBS writing one buffer twice.
 */
@Slf4j
public final class Clips {

    /** Sortable and unambiguous, and it puts the clips in order in any file listing. */
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Settings settings;

    /**
     * One thread, and a daemon.
     *
     * <p>Single so saves queue rather than collide. Daemon because a clip half-written at shutdown is
     * not worth holding the process open for — the game closing is a worse thing to delay than a
     * recording is to lose.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "autocraft-ai-clips");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicInteger saved = new AtomicInteger();

    public Clips(Settings settings) {
        this.settings = settings;
    }

    /**
     * How many clips are on disk. Reported by the status endpoint.
     *
     * <p>What this session happened to write was the old answer, and it was wrong in both directions:
     * zero after a restart with a folder full of clips, and unchanged after a wipe or a prune had taken
     * them away. Counted from the directory, and remembered for a moment because the panel asks about
     * once a second and the answer only changes when a clip is written.
     */
    public int count() {
        long now = System.currentTimeMillis();
        if (now - countedAt > COUNT_FOR_MILLIS) {
            counted = list().size();
            countedAt = now;
        }
        return counted;
    }

    /** How long a count of the directory stands before it is taken again. */
    private static final long COUNT_FOR_MILLIS = 2_000L;
    private volatile int counted;
    private volatile long countedAt;

    /** Where the clips are, or empty when OBS was left to record wherever it already did. */
    public Path directory() {
        String configured = settings.clipsDir();
        return configured.isBlank() ? null : Path.of(configured);
    }

    /**
     * The clips on disk, newest first.
     *
     * <p>Only files matching what this wrote. The recording directory may hold other things, and a
     * listing that offers to hand out whatever is in a folder is a much worse thing to get wrong.
     */
    public List<Path> list() {
        Path directory = directory();
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(Clips::isClip)
                    .sorted(Comparator.comparing(Clips::touched).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list {}", directory, e);
            return List.of();
        }
    }

    /**
     * Resolves one clip by name, for serving it.
     *
     * <p>The name arrives from a URL, so it is matched against the shape this writes rather than
     * trusted: anything else — a path, a traversal, a file somebody else put there — resolves to
     * nothing. Belt and braces, the result is also checked to be a direct child of the directory.
     *
     * @return the file, or null if there is no such clip
     */
    public Path find(String name) {
        Path directory = directory();
        if (directory == null || name == null || !isClip(Path.of(name).getFileName())) {
            return null;
        }
        Path candidate = directory.resolve(name).normalize();
        if (!candidate.getParent().equals(directory.normalize()) || !Files.isRegularFile(candidate)) {
            return null;
        }
        return candidate;
    }

    /**
     * Asks for the buffer to be written out, naming the file after what was just achieved.
     *
     * <p>Safe to call from the game thread: it returns immediately.
     */
    public void reached(String objective) {
        long since = System.currentTimeMillis() - lastSavedAt;
        long window = settings.clipSeconds() * 1000L;
        if (lastSavedAt != 0L && since < window) {
            // The buffer only holds one window, and objectives arrive in bursts — planks, sticks, a
            // table, a pickaxe, four of them inside a minute. Saving each wrote four near-copies of the
            // same video, and worse: a save queued behind another lands seconds later, by which time
            // the window has moved on, so the file named after one objective showed the next.
            log.info("Not clipping '{}': the clip of '{}' {}s ago already covers it", objective,
                    lastObjective, since / 1000);
            return;
        }
        worker.execute(() -> save(objective));
    }

    /** When the last clip was written, and of what, so a burst of objectives makes one clip and not five. */
    private volatile long lastSavedAt;
    private volatile String lastObjective = "";

    /**
     * How much of the death screen to let into the clip before the buffer is written out.
     *
     * <p>The buffer holds the minute up to the moment it is saved, so saving at the instant the health
     * hits zero would cut the clip on the blow. A couple of seconds more shows the screen and what the
     * server said did it, which is the whole point of keeping a death: the body respawns two seconds in,
     * so this is about as much as there is to see.
     */
    private static final long DEATH_TAIL_MILLIS = 2_000L;

    /**
     * Asks for the buffer to be written out because the body has just died, naming the file after what
     * killed it.
     *
     * <p>The other moment worth a minute of video, and for the opposite reason: reaching an objective is
     * the run at its best, and a death is the minute in which it went wrong — which is where the next
     * thing worth changing tends to be. Safe to call from the game thread: it returns immediately, and
     * the wait for the death screen happens on the worker.
     *
     * @param cause the server's own sentence, "Player was slain by Zombie", or empty when none came
     */
    public void died(String cause) {
        String what = cause == null || cause.isBlank() ? "death" : "death " + cause;
        worker.execute(() -> {
            try {
                Thread.sleep(DEATH_TAIL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            save(what);
        });
    }

    /**
     * Throws away every clip on disk.
     *
     * <p>Called when the worlds are deleted. A clip is a minute of a particular run, and once that run's
     * world is gone the clip is of somewhere nobody can go back to — a list still offering "get 4 log"
     * from a world that no longer exists is worse than an empty one. It is also the only thing that ever
     * empties the directory on purpose: {@link #prune} only ever trims the tail.
     *
     * <p>Queued rather than done here, for the same reason saves are: a save asked for a moment before the
     * world was wiped is still being encoded, and going through the one worker is what puts the clearing
     * behind it instead of racing it and leaving the last clip of the old run behind.
     */
    public void clear() {
        worker.execute(() -> {
            empty();
            countedAt = 0L;
        });
    }

    private void empty() {
        int gone = 0;
        for (Path clip : list()) {
            try {
                if (Files.deleteIfExists(clip)) {
                    gone++;
                }
            } catch (IOException e) {
                log.warn("Could not delete the clip {}", clip.getFileName(), e);
            }
        }
        // The count is of this run, and there is about to be a different one.
        saved.set(0);
        if (gone > 0) {
            log.info("Removed {} clip(s) belonging to the world that was just deleted", gone);
        }
    }

    private void save(String objective) {
        try {
            String written = Obs.with(settings, obs -> {
                // Reaching an objective is also the moment most likely to find the buffer stopped —
                // OBS may have been restarted since the scene was built — so make sure before saving
                // rather than discovering it from an empty answer.
                obs.buffer();
                return obs.save();
            });
            if (written.isBlank()) {
                log.warn("Asked OBS for a clip of '{}' but no file appeared", objective);
                return;
            }
            Path clip = rename(Path.of(written), objective);
            saved.incrementAndGet();
            countedAt = 0L;
            lastSavedAt = System.currentTimeMillis();
            lastObjective = objective;
            log.info("Clip of '{}' saved to {}", objective, clip);
            prune(clip.getParent());
        } catch (Obs.ObsException e) {
            // Losing a clip is not worth interrupting a run over. It is worth being told about.
            log.warn("Could not save a clip of '{}': {}", objective, e.getMessage());
        }
    }

    /**
     * Renames the file OBS wrote so it says what it is.
     *
     * <p>OBS names replays after the clock alone, which makes a directory of them unreadable — the one
     * thing you want to know when looking for a clip is which objective it is of. Renaming rather than
     * asking OBS to do it because its filename format is a profile-wide setting, and this should not
     * change how the rest of somebody's OBS behaves.
     *
     * @return where the clip ended up, which is the original path if it could not be moved
     */
    private static Path rename(Path written, String objective) {
        String extension = written.getFileName().toString().replaceFirst("^.*(?=\\.)", "");
        String name = LocalDateTime.now().format(WHEN) + "_" + slug(objective) + extension;
        Path target = written.resolveSibling(name);
        try {
            return Files.move(written, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not rename {} to {}; leaving it where OBS put it", written, name, e);
            return written;
        }
    }

    /** Objective names are prose — "get 4 logs" — and a filename is not. */
    private static String slug(String objective) {
        String cleaned = objective.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        String trimmed = cleaned.replaceAll("(^-|-$)", "");
        return trimmed.isEmpty() ? "objective" : trimmed;
    }

    /**
     * Deletes the oldest clips once there are more than were asked for.
     *
     * <p>A minute of 720p is tens of megabytes and a run reaches objectives all day. Unbounded, this
     * fills the disk the world is stored on, which is a much worse outcome than losing the clip of
     * something that happened yesterday.
     */
    private void prune(Path directory) {
        int keep = settings.clipsKeep();
        if (keep <= 0 || directory == null) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> clips = files.filter(Files::isRegularFile)
                    .filter(Clips::isClip)
                    .sorted(Comparator.comparing(Clips::touched).reversed())
                    .toList();
            for (Path old : clips.stream().skip(keep).toList()) {
                Files.deleteIfExists(old);
                log.info("Removed the oldest clip {} to stay under {}", old.getFileName(), keep);
            }
        } catch (IOException e) {
            log.warn("Could not tidy up {}", directory, e);
        }
    }

    /**
     * Only files this wrote.
     *
     * <p>The recording directory is somewhere a person may also keep things, and a routine that deletes
     * the oldest file in a folder is a much worse thing to get wrong than one that leaves a stray clip.
     */
    private static boolean isClip(Path file) {
        String name = file.getFileName().toString();
        return name.matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_.+\\..+");
    }

    /** Unreadable sorts oldest, so a file that cannot be stat'd is never mistaken for a recent one. */
    private static long touched(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }
}
