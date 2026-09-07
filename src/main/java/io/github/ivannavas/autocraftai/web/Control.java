package io.github.ivannavas.autocraftai.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.github.ivannavas.autocraftai.mob.ai.QLearningBrain;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;

/**
 * The endpoints something outside the game presses.
 *
 * <p>Three things can be asked for — forget what was learned, start a fresh world, go live on TikTok — and
 * they are asked for from a panel on another machine, which is what shapes the rest of this class.
 *
 * <h2>Everything that touches the game goes through the game thread</h2>
 * The brain, the level and the world-creation flow are all owned by the client thread and none of them is
 * safe to poke from a request handler. So a handler's job is to turn an HTTP request into something
 * submitted to {@link Minecraft} as an executor, and then to wait — with a bound — for the answer. What
 * comes back to the caller is what actually happened, not what was scheduled.
 *
 * <h2>Long work answers 202, not a held connection</h2>
 * Generating a world on a machine with no graphics card takes minutes, and a proxy two hosts away will
 * time the request out long before then. So the slow endpoint reports that it has started and
 * {@code /autocraft/status} reports how it is going. The short grace period before giving up on a synchronous
 * answer exists so that the failures which are immediate — already busy, nothing configured — still come
 * back as errors on the request that caused them.
 *
 * <h2>Authorisation</h2>
 * The overlay is a read-only window and stays open. The endpoints are not: they delete worlds and start
 * broadcasts, so it wants a bearer token whenever the request did not come from this machine. Binding to
 * anything but loopback without setting one is refused at startup rather than served insecurely.
 */
@Slf4j
public final class Control {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * How long a handler waits for the game thread before answering "started" instead of "done".
     *
     * <p>Short on purpose. It is not there to see slow work through, it is there so that work which fails
     * at once fails on the request that asked for it.
     */
    private static final long GRACE_SECONDS = 8;

    /** Wide enough to see the framing on a panel, narrow enough to be sent over a home network. */
    private static final int PREVIEW_WIDTH = 360;

    /** Frames a second for the live preview. Smooth enough to watch; well under what the box can do. */
    private static final int LIVE_FPS = 12;

    /**
     * How many people may watch at once.
     *
     * <p>Each viewer costs a thread and an OBS connection for as long as they watch, and a panel left
     * open in a few tabs is the ordinary way that number grows. Refusing the fifth is better than
     * quietly making the stream that is actually going out compete for the machine.
     */
    private final java.util.concurrent.Semaphore viewers = new java.util.concurrent.Semaphore(4);

    /** Separates frames in the multipart response. Any token works; this one is legible in a capture. */
    private static final String BOUNDARY = "autocraftframe";

    /** MIME wants CRLF, whatever this file happens to be saved with. */
    private static final String CRLF = "\r\n";

    /** Clearing the tables is a handful of map operations; if that is slow, something else is wrong. */
    private static final long RESET_SECONDS = 30;

    private final Settings settings;
    private final QLearningBrain brain;
    private final NewWorld newWorld;

    public Control(Settings settings, QLearningBrain brain, NewWorld newWorld) {
        this.settings = settings;
        this.brain = brain;
        this.newWorld = newWorld;
    }

    /**
     * Whether the server may listen where it has been told to.
     *
     * @return the reason it may not, or empty if it may
     */
    public static String refuseToStart(Settings settings) {
        boolean loopback = settings.bind().equals("127.0.0.1") || settings.bind().equals("::1");
        if (!loopback && settings.token().isEmpty()) {
            return "control.bind is " + settings.bind() + " but no control.token is set. These endpoints "
                    + "delete worlds and start broadcasts; refusing to expose them unauthenticated.";
        }
        return "";
    }

    /**
     * The one entry point the HTTP server registers.
     *
     * <p>Routing by hand rather than a context per endpoint: there are six of them, they share the token
     * check and the error shape, and a table here is easier to read than six registrations somewhere else.
     */
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        try {
            if (!authorised(exchange)) {
                reply(exchange, 401, "{\"ok\":false,\"error\":\"Missing or wrong bearer token\"}");
                return;
            }
            switch (path) {
                case QTableServer.BASE + "status" -> require(exchange, method, "GET", this::status);
                case QTableServer.BASE + "learning/reset" -> require(exchange, method, "POST", this::resetLearning);
                case QTableServer.BASE + "world/new" -> require(exchange, method, "POST", this::startWorld);
                case QTableServer.BASE + "stop" -> require(exchange, method, "POST", this::stopEverything);
                case QTableServer.BASE + "stream/scene" -> require(exchange, method, "POST", this::stageScene);
                case QTableServer.BASE + "stream/start" -> require(exchange, method, "POST", this::startStream);
                case QTableServer.BASE + "stream/stop" -> require(exchange, method, "POST", this::stopStream);
                case QTableServer.BASE + "stream/status" -> require(exchange, method, "GET", this::streamStatus);
                case QTableServer.BASE + "stream/preview" -> require(exchange, method, "GET", this::preview);
                case QTableServer.BASE + "stream/live" -> require(exchange, method, "GET", this::live);
                default -> reply(exchange, 404, "{\"ok\":false,\"error\":\"No such endpoint\"}");
            }
        } catch (Exception e) {
            // A handler throwing must not take the server's thread with it, and the caller deserves to
            // be told something more useful than a closed socket.
            log.warn("{} {} blew up", method, path, e);
            reply(exchange, 500, error(String.valueOf(e.getMessage())));
        }
    }

    private interface Handler {
        void run(HttpExchange exchange) throws IOException;
    }

    private void require(HttpExchange exchange, String method, String wanted, Handler handler)
            throws IOException {
        if (!wanted.equals(method)) {
            exchange.getResponseHeaders().set("Allow", wanted);
            reply(exchange, 405, "{\"ok\":false,\"error\":\"Use " + wanted + "\"}");
            return;
        }
        handler.run(exchange);
    }

    /**
     * Whether this request may do what it is asking.
     *
     * <p>A request that arrived over loopback is the player's own machine — the overlay's browser source,
     * a script on the box — and is let through. Anything else has to carry the token.
     */
    private boolean authorised(HttpExchange exchange) {
        String token = settings.token();
        if (token.isEmpty()) {
            return true;
        }
        InetAddress from = exchange.getRemoteAddress().getAddress();
        if (from != null && from.isLoopbackAddress()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String offered = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim()
                : String.valueOf(exchange.getRequestHeaders().getFirst("X-Autocraft-Token")).trim();
        // Constant time: the token is a secret and this is the one place it is compared.
        return java.security.MessageDigest.isEqual(
                offered.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /** Where everything stands, cheaply enough to be polled by a panel a couple of times a second. */
    private void status(HttpExchange exchange) throws IOException {
        Minecraft client = Minecraft.getInstance();
        boolean inWorld = client.level != null && client.player != null;
        String player = inWorld ? client.player.getName().getString() : null;
        reply(exchange, 200, "{\"ok\":true"
                + ",\"inWorld\":" + inWorld
                + ",\"player\":" + (player == null ? "null" : quote(player))
                + ",\"health\":" + (inWorld ? String.format(Locale.ROOT, "%.1f", client.player.getHealth()) : "null")
                + ",\"food\":" + (inWorld ? client.player.getFoodData().getFoodLevel() : 0)
                + ",\"world\":" + quote(newWorld.state())
                + ",\"worldBusy\":" + newWorld.busy()
                // Whether the run is down because it was told to, rather than because something broke.
                + ",\"stopped\":" + newWorld.stopped()
                // The game's own frame rate. The question "why does it look slow" has two possible
                // answers and this is the one the panel cannot work out for itself.
                + ",\"fps\":" + client.getFps()
                + ",\"overlayPort\":" + QTableServer.PORT
                + ",\"settings\":" + settings.describe()
                + "}");
    }

    /**
     * Forgets everything, on the game thread.
     *
     * <p>The brain already knows how to do this — it is what the title-screen button calls — and doing it
     * mid-run is fine: {@code clearLearning} drops the episode as well as the tables, so nothing is left
     * holding a state that no longer has a row.
     */
    private void resetLearning(HttpExchange exchange) throws IOException {
        Minecraft client = Minecraft.getInstance();
        CompletableFuture<Void> done = CompletableFuture.runAsync(brain::clearLearning, client);
        try {
            done.get(RESET_SECONDS, TimeUnit.SECONDS);
            log.info("Learning cleared over the control API");
            reply(exchange, 200, "{\"ok\":true,\"message\":\"Everything learned has been thrown away\"}");
        } catch (TimeoutException e) {
            reply(exchange, 504, error("The game thread did not get to it within " + RESET_SECONDS + "s"));
        } catch (ExecutionException e) {
            reply(exchange, 500, error(String.valueOf(e.getCause().getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply(exchange, 500, error("Interrupted"));
        }
    }

    /**
     * Deletes every saved world and starts a fresh survival one on normal.
     *
     * <p>Answers 202 in the ordinary case, because the world is still being generated when this returns.
     */
    private void startWorld(HttpExchange exchange) throws IOException {
        JsonNode body = body(exchange);
        String name = body.path("name").asText("AutoCraft AI");
        OptionalLong seed = body.hasNonNull("seed")
                ? OptionalLong.of(body.path("seed").asLong())
                : OptionalLong.empty();

        CompletableFuture<String> done = newWorld.request(name, seed);
        try {
            String made = done.get(GRACE_SECONDS, TimeUnit.SECONDS);
            reply(exchange, 200, "{\"ok\":true,\"message\":\"'" + escape(made) + "' is up\"}");
        } catch (TimeoutException e) {
            reply(exchange, 202, "{\"ok\":true,\"message\":\"Old worlds deleted, generating the new one\""
                    + ",\"poll\":\"" + QTableServer.BASE + "status\"}");
        } catch (ExecutionException e) {
            // The common one is "already busy", which is the caller's mistake rather than the server's.
            String why = String.valueOf(e.getCause().getMessage());
            reply(exchange, why.contains("already") ? 409 : 500, error(why));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply(exchange, 500, error("Interrupted"));
        }
    }

    /**
     * Puts everything down: off air, out of the world, and staying that way.
     *
     * <p>The stream goes first. Leaving the world takes a few seconds during which the game shows a
     * saving screen and then its menu, and that is not something to broadcast; stopping the other way
     * round would put exactly that on air.
     *
     * <p>OBS failing is not allowed to stop the rest. "Stop everything" that leaves the run playing
     * because the stream could not be reached would be the worst of both, so it is reported alongside
     * the outcome rather than instead of it.
     */
    private void stopEverything(HttpExchange exchange) throws IOException {
        String stream;
        try {
            stream = Obs.with(settings, Obs::halt) ? "Stream stopped" : "Was not streaming";
        } catch (Obs.ObsException e) {
            stream = "Could not reach OBS (" + e.getMessage() + ")";
        }
        try {
            String left = newWorld.stop().get(GRACE_SECONDS, TimeUnit.SECONDS);
            reply(exchange, 200, "{\"ok\":true,\"message\":" + quote(stream + ". " + left) + "}");
        } catch (TimeoutException e) {
            reply(exchange, 202, "{\"ok\":true,\"message\":"
                    + quote(stream + ". Leaving the world, which takes a moment")
                    + ",\"poll\":\"" + QTableServer.BASE + "status\"}");
        } catch (ExecutionException e) {
            String why = String.valueOf(e.getCause().getMessage());
            reply(exchange, why.contains("already") ? 409 : 500, error(stream + ". " + why));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply(exchange, 500, error("Interrupted"));
        }
    }

    /**
     * Builds the scene and puts it on air in OBS, without broadcasting anything.
     *
     * <p>Separate from going live because checking the framing and starting a broadcast are different
     * decisions, and the one that can be undone should not require making the one that cannot.
     */
    private void stageScene(HttpExchange exchange) throws IOException {
        try {
            String scene = Obs.with(settings, Obs::stage);
            reply(exchange, 200, "{\"ok\":true,\"message\":"
                    + quote("Scene '" + scene + "' rebuilt and on air in OBS") + "}");
        } catch (Obs.ObsException e) {
            reply(exchange, 502, error(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Builds the scene and goes live.
     *
     * <p>The RTMP details may come in the body, because TikTok issues them per broadcast and putting a
     * key that changes every time into a file on the box is a worse workflow than pasting it into the
     * panel. What is in {@link Settings} is the fallback, not the other way round.
     */
    private void startStream(HttpExchange exchange) throws IOException {
        JsonNode body = body(exchange);
        String server = body.path("server").asText(settings.streamServer());
        String key = body.path("key").asText(settings.streamKey());
        try {
            String message = Obs.with(settings, obs -> {
                String scene = obs.stage();
                boolean started = obs.go(server, key);
                return started
                        ? "Live on '" + scene + "'"
                        : "Already live; the scene '" + scene + "' has been rebuilt and put on air";
            });
            log.info("Stream start requested over the control API: {}", message);
            reply(exchange, 200, "{\"ok\":true,\"message\":" + quote(message) + "}");
        } catch (Obs.ObsException e) {
            reply(exchange, 502, error(String.valueOf(e.getMessage())));
        }
    }

    private void stopStream(HttpExchange exchange) throws IOException {
        try {
            boolean stopped = Obs.with(settings, Obs::halt);
            reply(exchange, 200, "{\"ok\":true,\"message\":"
                    + quote(stopped ? "Stream stopped" : "It was not streaming") + "}");
        } catch (Obs.ObsException e) {
            reply(exchange, 502, error(String.valueOf(e.getMessage())));
        }
    }

    private void streamStatus(HttpExchange exchange) throws IOException {
        try {
            reply(exchange, 200, Obs.with(settings, Obs::status));
        } catch (Obs.ObsException e) {
            // Not an error for the panel to shout about: OBS being closed is a normal state, and the
            // panel wants to draw it rather than to show a failed request.
            reply(exchange, 200, "{\"connected\":false,\"streaming\":false,\"error\":"
                    + quote(String.valueOf(e.getMessage())) + "}");
        }
    }

    /**
     * The scene as motion video, for a panel that wants to watch rather than to check.
     *
     * <h2>Why MJPEG</h2>
     * The obvious thing is to poll the still endpoint faster, and it does not work: each frame is a
     * request, and a request every hundred milliseconds is a handshake, a round trip and a render on
     * every one of them. Grabbing a frame costs about seventeen milliseconds here, so the ceiling is not
     * the box — it is the asking. A multipart response asks once and is answered until it is closed.
     *
     * <p>It also needs no client code at all: {@code multipart/x-mixed-replace} is what an ordinary
     * {@code <img>} tag renders as video, so the panel loses its polling loop rather than gaining a
     * decoder.
     *
     * <h2>Why it gets its own thread</h2>
     * This holds its exchange open for as long as somebody is watching — minutes, or all afternoon. The
     * server has a small fixed pool and the event stream shares it, so a handler that never returns
     * would take the pool down with it a viewer at a time. The exchange outlives the handler here, the
     * same way the q-table stream's does.
     */
    private void live(HttpExchange exchange) throws IOException {
        if (!viewers.tryAcquire()) {
            reply(exchange, 503, error("Too many people are already watching the preview"));
            return;
        }
        int width = number(exchange, "width", PREVIEW_WIDTH, 64, 1280);
        int fps = number(exchange, "fps", LIVE_FPS, 1, 30);

        exchange.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=" + BOUNDARY);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        // Zero means chunked: this response ends when the viewer goes away, not at a known length.
        exchange.sendResponseHeaders(200, 0);

        Thread thread = new Thread(() -> pump(exchange, width, fps), "autocraft-ai-preview");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Grabs frames on one OBS connection and writes them until the viewer goes away.
     *
     * <p>One connection for the whole stream rather than one per frame: the handshake is most of what a
     * single still costs, and paying it ten times a second would be the same mistake the polling made.
     */
    private void pump(HttpExchange exchange, int width, int fps) {
        long interval = TimeUnit.SECONDS.toNanos(1) / fps;
        try (Obs obs = Obs.connect(settings); OutputStream out = exchange.getResponseBody()) {
            long next = System.nanoTime();
            while (true) {
                byte[] frame = obs.frame(width);
                if (frame.length == 0) {
                    break;
                }
                // CRLF throughout, and a blank line between a part's headers and its bytes: this is
                // MIME, and a browser decoding the stream is stricter about it than an HTTP body is.
                out.write(("--" + BOUNDARY + CRLF
                        + "Content-Type: image/jpeg" + CRLF
                        + "Content-Length: " + frame.length + CRLF
                        + CRLF).getBytes(StandardCharsets.US_ASCII));
                out.write(frame);
                out.write(CRLF.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                next += interval;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleep);
                } else {
                    // Behind rather than ahead: catch up to now instead of trying to make up frames
                    // that would only put OBS further behind.
                    next = System.nanoTime();
                }
            }
        } catch (IOException e) {
            // The viewer closed the tab. That is how one of these always ends, so it is not a fault.
        } catch (Obs.ObsException e) {
            log.debug("Preview stream ended: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
            viewers.release();
        }
    }

    /** A bounded query parameter, because these size a render loop and arrive from a URL. */
    private static int number(HttpExchange exchange, String key, int fallback, int min, int max) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            if (pair.startsWith(key + "=")) {
                try {
                    return Math.clamp(Integer.parseInt(pair.substring(key.length() + 1)), min, max);
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    /**
     * A still of the scene, so the panel can show what is going out without being the thing going out.
     *
     * <p>Answers 200 with {@code image: null} when OBS is not there. A panel polling this every few
     * seconds should draw "no preview" in that case, not a failed request every few seconds.
     */
    private void preview(HttpExchange exchange) throws IOException {
        int wanted = number(exchange, "width", PREVIEW_WIDTH, 64, 1280);
        try {
            String image = Obs.with(settings, obs -> obs.shot(wanted));
            reply(exchange, 200, "{\"ok\":true,\"image\":" + quote(image) + "}");
        } catch (Obs.ObsException e) {
            reply(exchange, 200, "{\"ok\":false,\"image\":null,\"error\":"
                    + quote(String.valueOf(e.getMessage())) + "}");
        }
    }

    /** An absent or unreadable body is an empty one: every field these endpoints take is optional. */
    private static JsonNode body(HttpExchange exchange) {
        try (var in = exchange.getRequestBody()) {
            byte[] raw = in.readAllBytes();
            return raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private static String error(String message) {
        return "{\"ok\":false,\"error\":" + quote(message) + "}";
    }

    private static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
