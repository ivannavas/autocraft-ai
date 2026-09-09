package io.github.ivannavas.autocraftai.web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * The last few thousand log lines, kept in memory for the overlay.
 *
 * <p>The game writes {@code logs/latest.log} and {@code logs/debug.log}, and reading a run back has
 * always meant a shell on the machine and a grep. This is the same stream caught on its way past: a
 * log4j appender hung on the root logger, keeping the mod's own lines down to DEBUG — which is where
 * "Chose MINE", "Cutting TRAVEL short" and every goal starting and stopping live — and everything
 * else from INFO up, so a server warning or a crash is on the page too without the chunk-loading
 * chatter that fills the debug file.
 *
 * <p>A ring of {@link #CAPACITY} entries, each with a sequence number, so a page can ask for what
 * it has not seen ({@code logs?after=N}) and follow the rest as it arrives over the event stream.
 */
public final class LogBuffer extends AbstractAppender {

    /** One line as the page sees it. */
    public record Entry(long seq, long millis, String level, String logger, String thread, String message,
                        boolean own) {
    }

    /** How many lines are kept. Half an hour of a busy run, a whole quiet one. */
    private static final int CAPACITY = 3000;
    /** The loggers kept down to DEBUG: the mod's own. */
    private static final String OWN = "io.github.ivannavas.autocraftai";
    /** The most characters of one message kept; a stack trace is cut to its first lines. */
    private static final int MOST_CHARS = 2000;

    private static final LogBuffer INSTANCE = new LogBuffer();

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long seq;
    private volatile Runnable listener;
    private volatile boolean installed;

    private LogBuffer() {
        super("autocraft-ai-overlay", null, null, true, Property.EMPTY_ARRAY);
    }

    public static LogBuffer get() {
        return INSTANCE;
    }

    /**
     * Hangs the buffer on the root logger. Safe to call more than once; done once, at the first
     * server start. Any failure leaves the page without logs and the game with everything else.
     */
    public static void install() {
        if (INSTANCE.installed) {
            return;
        }
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = context.getConfiguration();
            INSTANCE.start();
            configuration.addAppender(INSTANCE);
            LoggerConfig root = configuration.getRootLogger();
            root.addAppender(INSTANCE, Level.ALL, null);
            context.updateLoggers();
            INSTANCE.installed = true;
        } catch (RuntimeException | LinkageError e) {
            LogManager.getLogger(LogBuffer.class).warn("The overlay will show no logs: {}", e.toString());
        }
    }

    /** Told after every line kept; the server uses it to wake its broadcaster. */
    public void onAppend(Runnable listener) {
        this.listener = listener;
    }

    @Override
    public void append(LogEvent event) {
        Level level = event.getLevel();
        String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
        boolean own = logger.startsWith(OWN);
        if (own ? level.isMoreSpecificThan(Level.DEBUG) == false : level.isMoreSpecificThan(Level.INFO) == false) {
            return;
        }
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        Throwable thrown = event.getThrown();
        if (thrown != null) {
            message = message + " — " + thrown;
            StackTraceElement[] trace = thrown.getStackTrace();
            for (int i = 0; i < Math.min(4, trace.length); i++) {
                message += "\n    at " + trace[i];
            }
        }
        if (message.length() > MOST_CHARS) {
            message = message.substring(0, MOST_CHARS) + "…";
        }
        Entry entry;
        synchronized (entries) {
            entry = new Entry(++seq, event.getTimeMillis(), level.name(), shortName(logger),
                    event.getThreadName() == null ? "" : event.getThreadName(), message, own);
            entries.addLast(entry);
            if (entries.size() > CAPACITY) {
                entries.removeFirst();
            }
        }
        Runnable told = listener;
        if (told != null) {
            told.run();
        }
    }

    /** The lines after a sequence number, oldest first, at most {@code limit} of the newest. */
    public List<Entry> since(long after, int limit) {
        List<Entry> found = new ArrayList<>();
        synchronized (entries) {
            for (Entry entry : entries) {
                if (entry.seq() > after) {
                    found.add(entry);
                }
            }
        }
        if (found.size() > limit) {
            return new ArrayList<>(found.subList(found.size() - limit, found.size()));
        }
        return found;
    }

    /** The sequence number of the newest line, or zero. */
    public long last() {
        synchronized (entries) {
            return seq;
        }
    }

    /** {@code io.github...QLearningBrain} as {@code QLearningBrain}; {@code net.minecraft.client.Minecraft} as {@code Minecraft}. */
    private static String shortName(String logger) {
        int dot = logger.lastIndexOf('.');
        return dot < 0 ? logger : logger.substring(dot + 1);
    }

    @Override
    public String toString() {
        return "LogBuffer[" + entries.size() + " lines]";
    }
}
