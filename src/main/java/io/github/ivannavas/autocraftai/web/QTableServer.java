package io.github.ivannavas.autocraftai.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ivannavas.autocraftai.mob.ai.CraftLog;
import io.github.ivannavas.autocraftai.mob.ai.QTableSnapshot;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves the live q-table as a page you can point OBS at.
 *
 * <p>Bound to loopback and nothing else. This is a window onto the player's own session; it has no business
 * being reachable from the network, and a browser source on the same machine does not need it to be.
 *
 * <p>The page is pushed to, not polled: it opens one server-sent event stream and the server writes down it
 * the instant a decision lands, so what is on screen is what the brain just did rather than what it was
 * doing up to a second ago. The machine's own load rides the same stream on its own once-a-second beat.
 *
 * <p>The handlers never touch the brain. They read one {@link QTableSnapshot} reference that the game
 * thread replaces each decision, so what goes out is a finished, immutable object no matter when it lands.
 */
@Slf4j
public final class QTableServer {

    /** Beside Minecraft's own 25565, so it is easy to remember and unlikely to be in use. */
    public static final int PORT = 25585;

    private static final String PAGE_RESOURCE = "/assets/autocraft-ai/web/qtable.html";

    /** How often the machine's numbers go out. They move on their own clock, not the brain's. */
    private static final long STATS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final AtomicReference<QTableSnapshot> latest;

    /** Every open stream. Copy-on-write: the broadcaster walks it far more often than it changes. */
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /** Raised by the game thread when a snapshot lands, drained by the broadcaster. */
    private final Semaphore published = new Semaphore(0);

    private final AtomicBoolean pending = new AtomicBoolean();

    private HttpServer server;
    private Thread broadcaster;

    public QTableServer(List<String> actions) {
        this.latest = new AtomicReference<>(QTableSnapshot.empty(actions));
    }

    /**
     * Called from the game thread every decision. Hands the snapshot over and wakes the broadcaster; the
     * writing to sockets happens over there, because a browser that has stopped reading must never be able
     * to stall the game loop behind it.
     */
    public void publish(QTableSnapshot snapshot) {
        latest.set(snapshot);
        // One permit however many snapshots pile up between wake-ups. The broadcaster always sends the
        // newest, and a queue of superseded tables would only make it spend the socket on stale ones.
        if (pending.compareAndSet(false, true)) {
            published.release();
        }
    }

    /**
     * Starts serving, or logs why it could not and leaves the game alone. An overlay failing to come up is
     * never a reason to take the mod down with it.
     */
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
            server.createContext("/", this::handlePage);
            server.createContext("/events", this::handleEvents);
            server.createContext("/texture/", this::handleTexture);
            // Daemon threads on purpose. The clean path is stop() on client shutdown, but a request
            // thread left running must never be the reason the game's process outlives its window and
            // holds this port against the next launch.
            server.setExecutor(Executors.newFixedThreadPool(2, daemon("autocraft-ai-overlay")));
            server.start();
            broadcaster = daemon("autocraft-ai-overlay-events").newThread(this::broadcast);
            broadcaster.start();
            log.info("Q-table overlay at http://localhost:{}/ — add it to OBS as a browser source", PORT);
        } catch (IOException e) {
            log.warn("Could not start the q-table overlay on port {}; carrying on without it", PORT, e);
            server = null;
        }
    }

    public void stop() {
        if (broadcaster != null) {
            broadcaster.interrupt();
            broadcaster = null;
        }
        subscribers.forEach(Subscriber::close);
        subscribers.clear();
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * The only thread that ever writes to a subscriber.
     *
     * <p>It waits on the game's next decision and sends the table the moment one arrives; when the wait runs
     * out first, that second's stats go instead. So a decision reaches the page as fast as the socket
     * carries it, and the machine's numbers keep their own pace even through a run of decisions.
     */
    private void broadcast() {
        long nextStats = System.nanoTime();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long wait = nextStats - System.nanoTime();
                if (wait > 0 && published.tryAcquire(wait, TimeUnit.NANOSECONDS)) {
                    // Cleared before the read, never after: a publish landing in between then raises the
                    // flag again and goes out next time round, where the other order would drop it.
                    pending.set(false);
                    send("qtable", Json.of(latest.get()));
                    continue;
                }
                nextStats = System.nanoTime() + STATS_INTERVAL_NANOS;
                send("stats", Json.of(Stats.sample()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(String event, String data) {
        for (Subscriber subscriber : subscribers) {
            if (!subscriber.send(event, data)) {
                subscribers.remove(subscriber);
                subscriber.close();
            }
        }
    }

    private void handlePage(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            respond(exchange, 404, "text/plain; charset=utf-8", "Not here".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream page = QTableServer.class.getResourceAsStream(PAGE_RESOURCE)) {
            if (page == null) {
                respond(exchange, 500, "text/plain; charset=utf-8",
                        "Overlay page missing from the jar".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, "text/html; charset=utf-8", page.readAllBytes());
        }
    }

    /**
     * Opens an event stream and hands it to the broadcaster.
     *
     * <p>The exchange deliberately outlives this method: the request thread goes straight back to the pool
     * and the broadcaster writes down the stream from then on, until the browser goes away or the game shuts
     * down. Holding the thread here instead would let two open browser sources use up the pool between them.
     *
     * <p>The words go out first and the table and the stats before the stream is registered, so a page that
     * connects between two decisions has something to draw at once, in the right language, rather than a
     * second of dashes.
     */
    private void handleEvents(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        // Length zero means chunked here: the response ends when the server says so, which is the point.
        exchange.sendResponseHeaders(200, 0);
        Subscriber subscriber = new Subscriber(exchange);
        if (subscriber.send("labels", Labels.json())
                && subscriber.send("qtable", Json.of(latest.get()))
                && subscriber.send("stats", Json.of(Stats.sample()))) {
            subscribers.add(subscriber);
        } else {
            subscriber.close();
        }
    }

    /**
     * An item's sprite, straight out of the resource pack. The bytes were resolved on the game thread when
     * the craft was recorded, so this only reads a cache — the resource manager is never touched from here.
     */
    private void handleTexture(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String item = path.substring(path.lastIndexOf('/') + 1).replace(".png", "");
        byte[] texture = CraftLog.get().texture(item);
        if (texture.length == 0) {
            respond(exchange, 404, "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
        respond(exchange, 200, "image/png", texture);
    }

    private void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * One open event stream, written to only by the broadcaster. The handler that opened it let go long ago,
     * so nothing in here is shared with a request thread.
     */
    private static final class Subscriber {

        private final HttpExchange exchange;
        private final OutputStream out;

        Subscriber(HttpExchange exchange) {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
        }

        /**
         * @return false once the browser has gone, which is the only way one of these ever ends. A closed
         *         tab is the normal case rather than a fault, so it does not earn a line in the log.
         */
        boolean send(String event, String data) {
            try {
                // The JSON never contains a raw newline — the writer escapes them — so a single data: line
                // is always enough, and the blank line after it is what marks the event complete.
                out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        void close() {
            exchange.close();
        }
    }
}
