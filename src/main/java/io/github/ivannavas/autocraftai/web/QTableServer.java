package io.github.ivannavas.autocraftai.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ivannavas.autocraftai.mob.ai.QTableSnapshot;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves the live q-table as a page you can point OBS at.
 *
 * <p>Bound to loopback and nothing else. This is a window onto the player's own session; it has no business
 * being reachable from the network, and a browser source on the same machine does not need it to be.
 *
 * <p>The handlers never touch the brain. They read one {@link QTableSnapshot} reference that the game
 * thread replaces each decision, so a request is a read of a finished, immutable object no matter when it
 * lands.
 */
@Slf4j
public final class QTableServer {

    /** Beside Minecraft's own 25565, so it is easy to remember and unlikely to be in use. */
    public static final int PORT = 25585;

    private static final String PAGE_RESOURCE = "/assets/autocraft-ai/web/qtable.html";

    private final AtomicReference<QTableSnapshot> latest;

    private HttpServer server;

    public QTableServer(List<String> actions) {
        this.latest = new AtomicReference<>(QTableSnapshot.empty(actions));
    }

    /** Called from the game thread every decision. */
    public void publish(QTableSnapshot snapshot) {
        latest.set(snapshot);
    }

    /**
     * Starts serving, or logs why it could not and leaves the game alone. An overlay failing to come up is
     * never a reason to take the mod down with it.
     */
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
            server.createContext("/", this::handlePage);
            server.createContext("/qtable.json", this::handleJson);
            // Daemon threads on purpose. The clean path is stop() on client shutdown, but a request
            // thread left running must never be the reason the game's process outlives its window and
            // holds this port against the next launch.
            server.setExecutor(Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "autocraft-ai-overlay");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            log.info("Q-table overlay at http://localhost:{}/ — add it to OBS as a browser source", PORT);
        } catch (IOException e) {
            log.warn("Could not start the q-table overlay on port {}; carrying on without it", PORT, e);
            server = null;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
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

    private void handleJson(HttpExchange exchange) throws IOException {
        byte[] body = Json.of(latest.get()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        respond(exchange, 200, "application/json; charset=utf-8", body);
    }

    private void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
