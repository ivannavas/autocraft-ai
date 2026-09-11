package io.github.ivannavas.autocraftai.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Talks to OBS over obs-websocket v5, and knows the one scene this mod owns.
 *
 * <h2>Why the whole scene is built from here</h2>
 * The alternative is a scene collection someone assembled by hand and must not touch again. That does not
 * survive a reinstall of the streaming box, and it cannot be reasoned about from the control panel: "start
 * the stream" would mean "hope the scene is still the way I left it". Building it means the endpoint can
 * promise what goes out — a capture of the game with the q-table page over it, at the size TikTok wants —
 * rather than merely pressing OBS's own button.
 *
 * <h2>Torn down and rebuilt, not patched</h2>
 * {@link #stage()} removes the two sources it owns before creating them again. Patching in place would
 * have to reconcile settings, transforms <em>and</em> stacking order against whatever the last version of
 * this code left behind, and getting the stacking order wrong is the difference between an overlay and a
 * hidden source. Creating them in order — game first, page second — puts the page on top by construction,
 * which is the one property that is hard to assert after the fact. Nothing else in the collection is
 * touched: the scene is the mod's, the rest of OBS is the streamer's.
 *
 * <h2>Connections are per-call</h2>
 * A control endpoint fires a handful of requests and is done, and the game may sit for hours between two
 * of them. Holding a socket open across that would mean owning a reconnect loop for no gain, so each call
 * connects, does its work and closes. The cost is a handshake per request, which is nothing next to the
 * thing being asked for.
 */
@Slf4j
public final class Obs implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The protocol version this speaks. obs-websocket refuses the handshake if it cannot serve it. */
    private static final int RPC_VERSION = 1;

    /** Long enough for OBS to start a capture on a cold source, short enough that a hung OBS is reported. */
    private static final long TIMEOUT_SECONDS = 20;

    /** obs-websocket says everything went fine with exactly this. */
    private static final int OK = 100;

    /** How long to wait for the buffer to actually come up after OBS says it accepted the request. */
    private static final int START_ATTEMPTS = 20;
    private static final long START_POLL_MILLIS = 250;

    /** How long to wait for a saved replay to appear on disk. Encoding a minute takes a moment. */
    private static final int SAVE_ATTEMPTS = 60;
    private static final long SAVE_POLL_MILLIS = 250;

    /** How many times to look for a removed source before deciding OBS is not going to let it go. */
    private static final int REMOVE_ATTEMPTS = 20;
    private static final long REMOVE_POLL_MILLIS = 100;

    /** The names the mod claims inside its scene. Anything else in the collection is left alone. */
    private static final String GAME_SOURCE = "AutoCraft AI - game";
    private static final String OVERLAY_SOURCE = "AutoCraft AI - qtable";
    private static final String SOUND_SOURCE = "AutoCraft AI - sound";

    private final Settings settings;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final CompletableFuture<JsonNode> identified = new CompletableFuture<>();
    private final AtomicLong ids = new AtomicLong();

    private WebSocket socket;

    private Obs(Settings settings) {
        this.settings = settings;
    }

    /**
     * Connects and completes the handshake, or throws with what went wrong.
     *
     * <p>The caller gets a connected client or an exception; there is no half-open state to check for.
     */
    public static Obs connect(Settings settings) throws ObsException {
        Obs obs = new Obs(settings);
        try {
            obs.socket = obs.http.newWebSocketBuilder()
                    .subprotocols("obswebsocket.json")
                    .connectTimeout(java.time.Duration.ofSeconds(TIMEOUT_SECONDS))
                    .buildAsync(URI.create(settings.obsUrl()), obs.new Incoming())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            obs.identified.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return obs;
        } catch (TimeoutException e) {
            obs.close();
            throw new ObsException("OBS did not answer at " + settings.obsUrl()
                    + " within " + TIMEOUT_SECONDS + "s. Is it running with the websocket server enabled?");
        } catch (ExecutionException e) {
            obs.close();
            throw new ObsException(reason(e.getCause(), settings));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            obs.close();
            throw new ObsException("Interrupted while connecting to OBS");
        }
    }

    private static String reason(Throwable cause, Settings settings) {
        String message = cause == null ? "unknown error" : String.valueOf(cause.getMessage());
        if (cause instanceof java.net.ConnectException || message.contains("Connection refused")) {
            return "Nothing is listening at " + settings.obsUrl()
                    + ". Start OBS and turn on Tools > WebSocket Server Settings.";
        }
        return "Could not reach OBS at " + settings.obsUrl() + ": " + message;
    }

    /**
     * Builds the scene and makes it the one on air.
     *
     * <p>Everything here is expressed against the canvas rather than in pixels, so the same recipe lays
     * out a 720x1280 box and a 1080x1920 one without a second set of numbers.
     *
     * @return the scene that is now live
     */
    public String stage() throws ObsException {
        String scene = settings.scene();
        int width = settings.width();
        int height = settings.height();

        // The canvas first: a source is laid out against it, so sizing it afterwards would move
        // everything that had already been placed.
        canvas(width, height);

        if (!scenes().contains(scene)) {
            call("CreateScene", JSON.createObjectNode().put("sceneName", scene));
            log.info("Created the OBS scene '{}'", scene);
        }

        // Removing an input takes its scene item with it, in this scene and in any other. Both of these
        // are named for the mod, so nothing that was not created here can be caught by it.
        List<String> inputs = inputs();
        for (String owned : List.of(GAME_SOURCE, OVERLAY_SOURCE, SOUND_SOURCE)) {
            if (inputs.contains(owned)) {
                discard(owned);
            }
        }

        // Order is the whole point: OBS puts a new source at the top of the scene, so the game going in
        // first and the page second is what makes the page an overlay rather than something behind it.
        int game = create(scene, GAME_SOURCE, settings.captureKind(), captureSettings());
        // The game covers the canvas; the desktop it is capturing is set to the same shape, so "cover"
        // crops nothing. If it ever is a different shape, cropping the edges beats bars down the sides.
        fit(scene, game, 0, 0, width, height, "OBS_BOUNDS_SCALE_OUTER");

        // The page is optional; the one above it was discarded with the game's source either way, so
        // turning it off takes it off the air at the next rebuild rather than leaving a stale copy.
        if (settings.overlayEnabled()) {
            int overlayWidth = (int) Math.round(width * settings.overlayWidth());
            int overlayHeight = (int) Math.round(height * settings.overlayHeight());
            int overlay = create(scene, OVERLAY_SOURCE, "browser_source",
                    overlaySettings(overlayWidth, overlayHeight));
            int overlayTop = (int) Math.round(height * settings.overlayTop());
            fit(scene, overlay, 0, overlayTop, overlayWidth, overlayHeight, "OBS_BOUNDS_SCALE_INNER");
        } else {
            log.info("Scene built without the overlay: overlay.enabled is false");
        }
        sound(scene);

        call("SetCurrentProgramScene", JSON.createObjectNode().put("sceneName", scene));

        // Recording is not optional the way broadcasting is: the clips are the point, and the stream is
        // one thing that can be done with the same scene. So the buffer comes up with the scene rather
        // than with the broadcast, and a restart that rebuilds one rebuilds the other.
        //
        // A buffer that will not start is reported and not thrown: the scene is built and on air by
        // this point, and refusing to admit that because the recording half failed would take a
        // working broadcast down over a directory permission.
        try {
            buffer();
        } catch (ObsException e) {
            log.warn("Scene is up but the replay buffer is not: {}", e.getMessage());
        }
        return scene;
    }

    /**
     * Points OBS at TikTok and starts sending.
     *
     * <p>{@code rtmp_custom} rather than one of OBS's named services. A named service would tie the
     * scene to one platform and hide the address; a custom one takes whatever ingest it is given, so the
     * destination is a setting rather than a code change. The default happens to be YouTube's.
     *
     * @return false if it was already streaming, which is not a failure — it is the state that was asked for
     */
    public boolean go(String server, String key) throws ObsException {
        if (server.isBlank()) {
            throw new ObsException("No stream server. Set stream.server in control.properties (or "
                    + "AUTOCRAFT_STREAM_SERVER) to the ingest — YouTube's is "
                    + "rtmps://a.rtmps.youtube.com:443/live2 — or type it in the panel.");
        }
        if (key.isBlank()) {
            throw new ObsException("No stream key. Paste it in the panel, or set stream.key in "
                    + "control.properties. YouTube's is in YouTube Studio under Go Live -> Stream "
                    + "settings, and it does not change between broadcasts.");
        }
        call("SetStreamServiceSettings", JSON.createObjectNode()
                .put("streamServiceType", "rtmp_custom")
                .set("streamServiceSettings", JSON.createObjectNode()
                        .put("server", server)
                        .put("key", key)
                        .put("use_auth", false)));
        if (streaming()) {
            return false;
        }
        call("StartStream", JSON.createObjectNode());
        return true;
    }

    /** @return false if it was not streaming to begin with */
    public boolean halt() throws ObsException {
        if (!streaming()) {
            return false;
        }
        call("StopStream", JSON.createObjectNode());
        return true;
    }

    public boolean streaming() throws ObsException {
        return call("GetStreamStatus", JSON.createObjectNode()).path("outputActive").asBoolean(false);
    }

    /** What the control panel shows about the broadcast: on air, for how long, and how much was dropped. */
    public String status() throws ObsException {
        JsonNode stream = call("GetStreamStatus", JSON.createObjectNode());
        JsonNode scene = call("GetCurrentProgramScene", JSON.createObjectNode());
        return "{\"connected\":true"
                + ",\"streaming\":" + stream.path("outputActive").asBoolean(false)
                // The buffer is the thing that is always meant to be running; the broadcast is not.
                + ",\"buffering\":" + buffering()
                + ",\"durationMs\":" + stream.path("outputDuration").asLong(0)
                + ",\"droppedFrames\":" + stream.path("outputSkippedFrames").asLong(0)
                + ",\"totalFrames\":" + stream.path("outputTotalFrames").asLong(0)
                + ",\"bytes\":" + stream.path("outputBytes").asLong(0)
                + ",\"scene\":\"" + scene.path("currentProgramSceneName").asText("").replace("\"", "\\\"")
                + "\"}";
    }

    /**
     * A still of what the scene is putting out, as a {@code data:} URI.
     *
     * <p>OBS renders this on demand from the scene itself rather than from the encoder, so it works
     * whether or not anything is being broadcast — which is the point: the panel is used to check the
     * shot is right <em>before</em> going live, not only to watch it afterwards.
     *
     * <p>JPEG rather than PNG, and narrow. This is polled, it travels over a home network to a browser,
     * and it is a thumbnail of a thing whose full-size version is a click away in the page below it.
     *
     * @param width in pixels; the height follows the canvas so the preview cannot lie about the framing
     */
    public String shot(int width) throws ObsException {
        int bounded = Math.clamp(width, 8, 4096);
        int height = Math.clamp(Math.round(bounded * (float) settings.height() / settings.width()), 8, 4096);
        return call("GetSourceScreenshot", JSON.createObjectNode()
                .put("sourceName", settings.scene())
                .put("imageFormat", "jpg")
                .put("imageWidth", bounded)
                .put("imageHeight", height)
                .put("imageCompressionQuality", 70))
                .path("imageData").asText("");
    }

    /**
     * The same still as {@link #shot(int)}, decoded.
     *
     * <p>A data URI is what a JSON answer wants and the wrong shape for anything else: it is a third
     * larger than the bytes it wraps, and a video stream would be paying that on every frame.
     */
    public byte[] frame(int width) throws ObsException {
        String uri = shot(width);
        int comma = uri.indexOf(',');
        return comma < 0 ? new byte[0] : Base64.getDecoder().decode(uri.substring(comma + 1));
    }

    /**
     * Makes sure OBS is holding the last stretch of video in memory, ready to be written out.
     *
     * <p>A replay buffer rather than a recording. What is wanted is a minute either side of something
     * interesting and nothing else, and recording continuously would mean writing gigabytes an hour to
     * throw nearly all of it away — on a box whose disk also holds the world. The buffer keeps its
     * window in memory and drops what falls out of it, so the discarding is not a job anyone has to do.
     *
     * <p>Settings before start, because OBS reads them when the output is created: changing the length
     * of a running buffer does nothing until it is stopped and started again.
     *
     * @return false if it was already running, which is the state that was wanted either way
     */
    public boolean buffer() throws ObsException {
        String directory = settings.clipsDir();
        if (!directory.isBlank()) {
            profile("SimpleOutput", "FilePath", directory);
        }
        profile("SimpleOutput", "RecRB", "true");
        profile("SimpleOutput", "RecRBTime", Integer.toString(settings.clipSeconds()));

        if (buffering()) {
            return false;
        }
        call("StartReplayBuffer", JSON.createObjectNode());

        // Accepting the request is not the same as starting. OBS answers "ok" and then fails on its
        // own thread — a directory it cannot write to says only "Recording stopped because of bad
        // output path", in its log, seconds later. Reporting success on the strength of the reply
        // meant the run believed it was recording for as long as nobody looked.
        for (int attempt = 0; attempt < START_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(START_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ObsException("Interrupted waiting for the replay buffer");
            }
            if (buffering()) {
                log.info("Replay buffer running: the last {}s are kept in memory",
                        settings.clipSeconds());
                return true;
            }
        }
        throw new ObsException("OBS accepted the replay buffer but it never started. Its log will say "
                + "why; a recording path it cannot write to is the usual one — check that "
                + settings.clipsDir() + " exists and that OBS is allowed to write there (a Flatpak "
                + "needs 'flatpak override --filesystem=' for anything outside home).");
    }

    /**
     * Sizes the canvas, only when it is not that size already.
     *
     * <p>OBS refuses {@code SetVideoSettings} while any output is running, and the replay buffer is an
     * output: it is started by this very scene and outlives the game, so every rebuild after the first
     * — every restart of the game — was refused before it had removed or created anything, and the
     * broadcast kept the old scene, overlay and all. Asking first is what makes the ordinary rebuild a
     * no-op here; a canvas that really has to change stops the buffer for it, and {@link #buffer()}
     * starts it again at the end as it always did. A live broadcast is left alone: a canvas that
     * changes shape under a stream is not a thing anyone wants done for them.
     */
    private void canvas(int width, int height) throws ObsException {
        JsonNode video = call("GetVideoSettings", JSON.createObjectNode());
        boolean same = video.path("baseWidth").asInt() == width
                && video.path("baseHeight").asInt() == height
                && video.path("outputWidth").asInt() == width
                && video.path("outputHeight").asInt() == height
                && video.path("fpsNumerator").asInt() == settings.fps()
                && video.path("fpsDenominator").asInt() == 1;
        if (same) {
            return;
        }
        if (streaming()) {
            log.warn("OBS canvas is not {}x{}@{} but a broadcast is live; leaving it as it is",
                    width, height, settings.fps());
            return;
        }
        if (buffering()) {
            call("StopReplayBuffer", JSON.createObjectNode());
            for (int attempt = 0; attempt < START_ATTEMPTS && buffering(); attempt++) {
                try {
                    Thread.sleep(START_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ObsException("Interrupted waiting for the replay buffer to stop");
                }
            }
        }
        call("SetVideoSettings", JSON.createObjectNode()
                .put("baseWidth", width)
                .put("baseHeight", height)
                .put("outputWidth", width)
                .put("outputHeight", height)
                .put("fpsNumerator", settings.fps())
                .put("fpsDenominator", 1));
    }

    public boolean buffering() throws ObsException {
        // 604 comes back when the output does not exist yet, which is "not running" rather than a fault.
        try {
            return call("GetReplayBufferStatus", JSON.createObjectNode())
                    .path("outputActive").asBoolean(false);
        } catch (ObsException e) {
            return false;
        }
    }

    /**
     * Writes the buffer out and returns where it landed.
     *
     * <p>Saving is asynchronous: the request is accepted long before the file is closed, and asking OBS
     * where the last replay went straight afterwards returns the one before it. So this waits for the
     * answer to change, which is the only signal there is that this save — rather than the last one —
     * is on disk.
     *
     * @return the path OBS wrote, or empty if it never appeared
     */
    public String save() throws ObsException {
        String previous = lastReplay();
        call("SaveReplayBuffer", JSON.createObjectNode());
        for (int attempt = 0; attempt < SAVE_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(SAVE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            String now = lastReplay();
            if (!now.isBlank() && !now.equals(previous)) {
                return now;
            }
        }
        return "";
    }

    private String lastReplay() {
        try {
            return call("GetLastReplayBufferReplay", JSON.createObjectNode())
                    .path("savedReplayPath").asText("");
        } catch (ObsException e) {
            return "";
        }
    }

    private void profile(String category, String name, String value) throws ObsException {
        call("SetProfileParameter", JSON.createObjectNode()
                .put("parameterCategory", category)
                .put("parameterName", name)
                .put("parameterValue", value));
    }

    private List<String> scenes() throws ObsException {
        return names(call("GetSceneList", JSON.createObjectNode()).path("scenes"), "sceneName");
    }

    private List<String> inputs() throws ObsException {
        return names(call("GetInputList", JSON.createObjectNode()).path("inputs"), "inputName");
    }

    private static List<String> names(JsonNode array, String field) {
        return array.isArray()
                ? array.valueStream().map(node -> node.path(field).asText("")).toList()
                : List.of();
    }

    /**
     * Removes an input and waits for OBS to actually let go of its name.
     *
     * <p>{@code RemoveInput} answers as soon as the request is accepted, not when the source is gone:
     * OBS drops its own reference and destroys the source once whatever else is holding it lets go. So
     * creating the replacement on the next line fails with "a source already exists by that name",
     * intermittently, depending on what the render thread happened to be doing. Polling the list is the
     * only signal there is that the name is free again.
     */
    private void discard(String name) throws ObsException {
        call("RemoveInput", JSON.createObjectNode().put("inputName", name));
        for (int attempt = 0; attempt < REMOVE_ATTEMPTS; attempt++) {
            if (!inputs().contains(name)) {
                return;
            }
            try {
                Thread.sleep(REMOVE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ObsException("Interrupted while waiting for OBS to drop '" + name + "'");
            }
        }
        throw new ObsException("OBS still has a source called '" + name + "' a couple of seconds after "
                + "being asked to remove it. Something else in the scene collection is holding it.");
    }

    /** @return the scene item id, which is what a transform is addressed by */
    /**
     * Puts the game's sound into the scene.
     *
     * <p>A display capture is picture only, and a scene built of nothing else went out silent for as
     * long as it has been going out: the box has PipeWire with a null sink the game plays into and
     * OBS has the socket to hear it, and nothing ever asked. An output capture on the default sink's
     * monitor is the whole of it. Not thrown on failure — a box with no sound server, or an OBS build
     * without the plugin, still gets its picture; the log says what it did not get.
     */
    private void sound(String scene) {
        String device = settings.audioDevice();
        if (device.isBlank()) {
            log.info("Scene built without sound: obs.audio.device is empty");
            return;
        }
        try {
            create(scene, SOUND_SOURCE, settings.audioKind(),
                    JSON.createObjectNode().put("device_id", device));
            log.info("Sound in the scene: {} on '{}'", settings.audioKind(), device);
        } catch (ObsException e) {
            log.warn("Scene is up but silent: OBS refused the sound capture ({} on '{}'): {}",
                    settings.audioKind(), device, e.getMessage());
        }
    }

    private int create(String scene, String name, String kind, ObjectNode inputSettings) throws ObsException {
        JsonNode created = call("CreateInput", JSON.createObjectNode()
                .put("sceneName", scene)
                .put("inputName", name)
                .put("inputKind", kind)
                .put("sceneItemEnabled", true)
                .set("inputSettings", inputSettings));
        return created.path("sceneItemId").asInt();
    }

    /**
     * Places a source in a box on the canvas.
     *
     * <p>Bounds rather than a scale factor: the source's own size is not known here and would change under
     * us anyway — a display capture is whatever the desktop currently is — whereas a box is a promise
     * about the layout that holds whatever goes in it.
     */
    private void fit(String scene, int item, int x, int y, int width, int height, String bounds)
            throws ObsException {
        call("SetSceneItemTransform", JSON.createObjectNode()
                .put("sceneName", scene)
                .put("sceneItemId", item)
                .set("sceneItemTransform", JSON.createObjectNode()
                        .put("positionX", x)
                        .put("positionY", y)
                        // 5 is left|top: the position is the box's own corner, so the arithmetic above
                        // is about where the box is rather than where its centre lands.
                        .put("alignment", 5)
                        .put("boundsType", bounds)
                        .put("boundsAlignment", 0)
                        .put("boundsWidth", width)
                        .put("boundsHeight", height)));
    }

    /**
     * What the capture is told about itself.
     *
     * <p>The display is always named, never left out. A display capture created with no settings does not
     * fall back to the first screen — X11's starts on "[Select a display to capture]", which is a source
     * that produces no frames at all and reports itself as zero by zero. The scene comes up looking like
     * the game is off rather than like something was not configured, which is a much harder thing to
     * work out from a black rectangle.
     *
     * <p>The key that names a display is spelled differently by each platform's capture, so it is chosen
     * from the kind rather than assumed.
     */
    private ObjectNode captureSettings() {
        return JSON.createObjectNode()
                .put(displayKey(settings.captureKind()), settings.captureMonitor());
    }

    private static String displayKey(String kind) {
        return switch (kind) {
            case "monitor_capture" -> "monitor";
            case "screen_capture" -> "display";
            default -> "screen";
        };
    }

    /**
     * The overlay browser, sized to render rather than to fit.
     *
     * <p>A browser source is a real browser: its size is the viewport the page is laid out in, and the
     * transform scales the result afterwards. Those are separate decisions and the page cares about the
     * first one. The q-table has a column per action, and laid out in a 720-wide viewport — the width of
     * a vertical canvas — the last two fall off the right edge. So it is rendered at a width the page
     * fits in and scaled down into its box, which trades some text size for showing the whole table.
     *
     * <p>{@code overlay.render.width} is the knob: lower it for bigger text and a narrower table.
     *
     * @param boxWidth  how wide the overlay is on the canvas
     * @param boxHeight how tall, which fixes the render height by the box's own shape
     */
    private ObjectNode overlaySettings(int boxWidth, int boxHeight) {
        int render = settings.overlayRenderWidth();
        int width = render > 0 ? render : boxWidth;
        int height = (int) Math.round(width * (double) boxHeight / boxWidth);
        return JSON.createObjectNode()
                .put("url", settings.overlayUrl())
                .put("width", width)
                .put("height", height)
                .put("reroute_audio", false)
                // The page is drawn over the game, so whatever it does not paint should be the game.
                .put("css", "body { background: transparent; margin: 0; overflow: hidden; }");
    }

    /**
     * Sends one request and waits for its answer.
     *
     * <p>obs-websocket answers out of order and without a channel per request, so every call carries an id
     * and the listener hands the reply to whoever is waiting on it.
     */
    private JsonNode call(String type, ObjectNode data) throws ObsException {
        String id = Long.toString(ids.incrementAndGet());
        ObjectNode message = JSON.createObjectNode();
        message.put("op", 6);
        message.set("d", JSON.createObjectNode()
                .put("requestType", type)
                .put("requestId", id)
                .set("requestData", data));

        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        pending.put(id, answer);
        try {
            socket.sendText(message.toString(), true);
            JsonNode reply = answer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            JsonNode status = reply.path("requestStatus");
            if (status.path("code").asInt() != OK) {
                throw new ObsException(type + " was refused by OBS: "
                        + status.path("comment").asText(status.path("code").asText()));
            }
            return reply.path("responseData");
        } catch (TimeoutException e) {
            throw new ObsException(type + " got no answer from OBS within " + TIMEOUT_SECONDS + "s");
        } catch (ExecutionException e) {
            throw new ObsException(type + " failed: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ObsException("Interrupted waiting for OBS");
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            socket = null;
        }
        pending.values().forEach(future ->
                future.completeExceptionally(new ObsException("The OBS connection closed")));
        pending.clear();
    }

    /**
     * The handshake, and the routing of every reply after it.
     *
     * <p>A text message can arrive in pieces, so the parts are collected until the socket says the last
     * one has landed. Parsing half a frame is the kind of bug that only shows up under a big scene list.
     */
    private final class Incoming implements WebSocket.Listener {

        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            socket.request(1);
            if (!last) {
                return null;
            }
            String complete = partial.toString();
            partial.setLength(0);
            try {
                dispatch(socket, JSON.readTree(complete));
            } catch (Exception e) {
                log.warn("Could not make sense of a message from OBS", e);
                identified.completeExceptionally(e);
            }
            return null;
        }

        /**
         * @param socket the one the listener was handed, never {@link Obs#socket}. The Hello can land
         *               before {@code buildAsync} has completed and that field has been assigned, so
         *               reading it here is a race that shows up as a null socket on a fast connection.
         */
        private void dispatch(WebSocket socket, JsonNode message) throws ObsException {
            JsonNode d = message.path("d");
            switch (message.path("op").asInt(-1)) {
                // Hello. Answer it with an Identify, signed if it asked to be.
                case 0 -> socket.sendText(identify(d).toString(), true);
                // Identified: the connection is usable from here.
                case 2 -> identified.complete(d);
                // A reply to one of ours.
                case 7 -> {
                    CompletableFuture<JsonNode> waiting = pending.get(d.path("requestId").asText());
                    if (waiting != null) {
                        waiting.complete(d);
                    }
                }
                default -> {
                    // Events and anything a newer OBS invents. Nothing here subscribes to them.
                }
            }
        }

        private ObjectNode identify(JsonNode hello) throws ObsException {
            ObjectNode d = JSON.createObjectNode()
                    .put("rpcVersion", RPC_VERSION)
                    // No events: every answer this needs is a reply to something it asked.
                    .put("eventSubscriptions", 0);
            JsonNode challenge = hello.path("authentication");
            if (!challenge.isMissingNode()) {
                d.put("authentication", sign(
                        challenge.path("salt").asText(), challenge.path("challenge").asText()));
            }
            ObjectNode message = JSON.createObjectNode();
            message.put("op", 1);
            message.set("d", d);
            return message;
        }

        /**
         * obs-websocket's challenge: hash the password with the salt, hash that with the challenge.
         *
         * <p>Both rounds are base64 of a SHA-256, and the intermediate goes in as its base64 text rather
         * than its bytes — which is the part that is easy to get wrong and shows up only as a rejected
         * handshake.
         */
        private String sign(String salt, String challenge) throws ObsException {
            String password = settings.obsPassword();
            if (password.isEmpty()) {
                throw new ObsException("OBS is asking for a password and none is set. Put it in "
                        + "control.properties as obs.password, or turn authentication off in OBS.");
            }
            String secret = hash(password + salt);
            return hash(secret + challenge);
        }

        private String hash(String value) throws ObsException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return Base64.getEncoder()
                        .encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new ObsException("This JVM has no SHA-256, which should not be possible");
            }
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            identified.completeExceptionally(error);
            pending.values().forEach(future -> future.completeExceptionally(error));
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            ObsException closed = new ObsException("OBS closed the connection: " + status + " " + reason);
            identified.completeExceptionally(closed);
            pending.values().forEach(future -> future.completeExceptionally(closed));
            return null;
        }
    }

    /** Anything that went wrong talking to OBS, carrying a message meant for whoever pressed the button. */
    public static final class ObsException extends Exception {

        public ObsException(String message) {
            super(message);
        }
    }

    /** Convenience for the endpoints: run something against a fresh connection and always close it. */
    public static <T> T with(Settings settings, Work<T> work) throws ObsException {
        try (Obs obs = connect(settings)) {
            return work.apply(obs);
        }
    }

    @FunctionalInterface
    public interface Work<T> {
        T apply(Obs obs) throws ObsException;
    }
}
