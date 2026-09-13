package io.github.ivannavas.autocraftai.mob.ai;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

/**
 * Every move the run has made, written down as it happens.
 *
 * <h2>Why this exists</h2>
 * What the tables hold is not the run's experience, it is the <em>digest</em> of it: a hundred visits to
 * a state boiled down to eleven numbers, with everything that led to them thrown away. That is fine while
 * the only thing anyone ever wants is the next decision, and it is ruinous the moment anyone wants to
 * change how the learning works. Reprice a reward, widen a state, add a column, swap a table for a
 * network — and every hour the body has ever spent playing is gone, because the only record of it was a
 * summary computed under the old rules. Every experiment costs another night of real time, and at fifteen
 * to twenty-eight decisions a minute a night is not many decisions.
 *
 * <p>With the moves themselves on disk, those become re-runs of an afternoon's file rather than re-runs
 * of the world. A reward function can be compared against another over the same hundred thousand moves.
 * A network can be trained back up from scratch after its columns change. A question like "how often is
 * the argmax in this state a move that has never once been tried" can be answered by reading, instead of
 * by adding a counter and waiting a day.
 *
 * <p>It is deliberately the first thing built and the last thing to have an opinion. Nothing here decides
 * anything; it writes a line per move per layer and stays out of the way.
 *
 * <h2>Cheap enough to leave on</h2>
 * A line is about two hundred bytes and there are eight layers, so a run writes a couple of megabytes an
 * hour. The game thread only ever puts a record on a queue; a daemon thread does the encoding and the
 * writing, and drops records rather than block if it ever falls behind. Files roll at
 * {@link #MOST_BYTES} and the oldest goes, so an unattended run cannot fill a disk.
 */
@Slf4j
public final class Experience {

    private static final Experience INSTANCE = new Experience();

    private static final String FOLDER = "experience";
    private static final String CURRENT = "moves.jsonl";
    /** Where a file rolls, and how many rolled ones are kept. About a day and a half of a run in all. */
    private static final long MOST_BYTES = 64L * 1024 * 1024;
    private static final int KEEP_FILES = 4;
    /** Records waiting to be written. Beyond this the writer has fallen behind and records are dropped. */
    private static final int QUEUE = 8192;
    /**
     * The longest a written move may sit in the buffer before it is on the disk.
     *
     * <p>The writer used to flush only when it found nothing to do, which is fine for a run that ends by
     * being asked to and wrong for one that ends any other way: a client killed outright — which is how
     * an unattended trial is stopped — left whatever had not filled a buffer unwritten, and the file ran
     * a few hundred moves behind the run. A second's worth is the most that may now be lost, and a flush
     * a second of a few kilobytes costs nothing worth measuring.
     */
    private static final long FLUSH_EVERY_MILLIS = 1000L;

    /** One move, as the learning rule saw it. */
    private record Move(long at, String layer, String pursuit, String state, String action,
                        double reward, int steps, String next, String legal, boolean terminal) {
    }

    /**
     * What a second's reward was made of, written as its own line.
     *
     * <p>The gap that made a whole afternoon of reward work guesswork. The log held the total and
     * nothing else, so "why is ninety per cent of every move negative" could only be answered by
     * reasoning about which cost was probably dominating — and the answer arrived at that way is a
     * hypothesis, not a measurement. With the parts written down the same question is a sum over a
     * column, and a rebalance can be argued from the file instead of from the shape of the totals.
     *
     * <p>Its own line rather than a field on the move, so nothing that reads moves has to change and a
     * reader that does not care can skip it by its layer.
     */
    private record Parts(long at, String pursuit, String state, String parts) {
    }

    private final BlockingQueue<Object> pending = new LinkedBlockingQueue<>(QUEUE);
    private final AtomicBoolean running = new AtomicBoolean();
    private Path folder;
    private Thread writer;
    private long dropped;

    private Experience() {
    }

    public static Experience get() {
        return INSTANCE;
    }

    /** Starts writing under the given directory. Called once, with the learned tables' own folder. */
    public synchronized void open(Path directory) {
        if (running.get()) {
            return;
        }
        this.folder = directory.resolve(FOLDER);
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            log.warn("Could not make {}; moves will not be written down", folder, e);
            return;
        }
        running.set(true);
        writer = new Thread(this::drain, "autocraft-experience");
        writer.setDaemon(true);
        writer.start();
        log.info("Writing every move to {}", folder.resolve(CURRENT));
    }

    public synchronized void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (writer != null) {
            writer.interrupt();
            writer = null;
        }
        if (dropped > 0) {
            log.info("{} moves went unwritten: the writer could not keep up", dropped);
        }
    }

    /**
     * One move and what it earned.
     *
     * @param legal the mask the next state offered, so a reader can recompute a bootstrap without
     *              having to know this build's legality rules
     */
    public void record(String layer, String pursuit, String state, String action,
                       double reward, int steps, String next, boolean[] legal, boolean terminal) {
        if (!running.get()) {
            return;
        }
        Move move = new Move(System.currentTimeMillis(), layer, pursuit, state, action,
                reward, steps, next == null ? "" : next, mask(legal), terminal);
        if (!pending.offer(move)) {
            dropped++;
        }
    }

    /**
     * What the second's reward was made of: names to values, as the scoring produced them.
     *
     * @param parts already formatted as {@code name=value} pairs separated by semicolons, since the
     *              caller is the only thing that knows the names and building a map to take it apart
     *              again once a second is work for nobody
     */
    public void breakdown(String pursuit, String state, String parts) {
        if (!running.get()) {
            return;
        }
        if (!pending.offer(new Parts(System.currentTimeMillis(), pursuit, state, parts))) {
            dropped++;
        }
    }

    private static String mask(boolean[] legal) {
        if (legal == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(legal.length);
        for (boolean allowed : legal) {
            out.append(allowed ? '1' : '0');
        }
        return out.toString();
    }

    private void drain() {
        try (BufferedWriter out = open()) {
            BufferedWriter sink = out;
            long written = size();
            long flushed = System.currentTimeMillis();
            while (running.get() || !pending.isEmpty()) {
                Object entry = pending.poll(1, TimeUnit.SECONDS);
                if (entry == null) {
                    sink.flush();
                    flushed = System.currentTimeMillis();
                    continue;
                }
                String line = entry instanceof Move move ? encode(move) : encode((Parts) entry);
                sink.write(line);
                sink.write('\n');
                written += line.length() + 1;
                long now = System.currentTimeMillis();
                if (now - flushed >= FLUSH_EVERY_MILLIS) {
                    sink.flush();
                    flushed = now;
                }
                if (written >= MOST_BYTES) {
                    sink.flush();
                    sink.close();
                    roll();
                    sink = open();
                    written = 0;
                }
            }
            sink.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.warn("Stopped writing moves down: {}", e.getMessage());
        }
    }

    private BufferedWriter open() throws IOException {
        return Files.newBufferedWriter(folder.resolve(CURRENT), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private long size() {
        try {
            Path path = folder.resolve(CURRENT);
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** The full file put aside under the hour it finished, and the oldest let go. */
    private void roll() {
        try {
            Path rolled = folder.resolve("moves-" + System.currentTimeMillis() + ".jsonl");
            Files.move(folder.resolve(CURRENT), rolled);
            try (var files = Files.list(folder)) {
                List<Path> old = files
                        .filter(path -> path.getFileName().toString().startsWith("moves-"))
                        .sorted()
                        .toList();
                for (int i = 0; i < old.size() - KEEP_FILES; i++) {
                    Files.deleteIfExists(old.get(i));
                }
            }
        } catch (IOException e) {
            log.warn("Could not roll the move log: {}", e.getMessage());
        }
    }

    /** JSON by hand: one shape, written millions of times, and no reason to build a tree for it. */
    private static String encode(Move move) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"t\":").append(move.at())
                .append(",\"layer\":\"").append(escape(move.layer()))
                .append("\",\"pursuit\":\"").append(escape(move.pursuit()))
                .append("\",\"state\":\"").append(escape(move.state()))
                .append("\",\"action\":\"").append(escape(move.action()))
                .append("\",\"reward\":").append(String.format(java.util.Locale.ROOT, "%.4f", move.reward()))
                .append(",\"steps\":").append(move.steps())
                .append(",\"next\":\"").append(escape(move.next()))
                .append("\",\"legal\":\"").append(move.legal())
                .append("\",\"terminal\":").append(move.terminal())
                .append('}');
        return out.toString();
    }

    /** The same one-shape-only JSON, for a line of parts. */
    private static String encode(Parts parts) {
        return "{\"t\":" + parts.at()
                + ",\"layer\":\"reward\",\"pursuit\":\"" + escape(parts.pursuit())
                + "\",\"state\":\"" + escape(parts.state())
                + "\",\"parts\":\"" + escape(parts.parts()) + "\"}";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
