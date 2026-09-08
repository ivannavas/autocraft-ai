package io.github.ivannavas.autocraftai.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import lombok.extern.slf4j.Slf4j;

/**
 * Everything the control surface has to be told rather than work out for itself.
 *
 * <p>Three layers, most specific first: a system property, then the environment, then
 * {@code control.properties} beside the learned tables. The order is what lets one jar run on a
 * developer's machine and on the streaming box without either editing the other's file — the box sets the
 * environment from its service definition and leaves the file for the things that are not secret.
 *
 * <p>Secrets — the API token, OBS's password, the stream key — are read the same way but never written
 * back and never logged. {@link #describe()} exists so the status endpoint can say whether one is set
 * without saying what it is.
 */
@Slf4j
public final class Settings {

    /** Read once at startup. A change means a restart, which is also when the streaming box gets one. */
    private static final String FILE = "control.properties";

    private final Properties file = new Properties();

    public Settings(Path directory) {
        Path path = directory.resolve(FILE);
        if (!Files.isRegularFile(path)) {
            log.info("No {} in {}; the control API falls back to its defaults", FILE, directory);
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            file.load(in);
            log.info("Read {} settings from {}", file.size(), path);
        } catch (IOException e) {
            log.warn("Could not read {}; carrying on with defaults", path, e);
        }
    }

    /**
     * Which address the server answers on.
     *
     * <p>Loopback by default, because the overlay is a window onto the player's own session and a browser
     * source on the same machine does not need more. The streaming box sets this to {@code 0.0.0.0} so the
     * proxy on the other host can reach the control endpoints — and {@link #token()} is what makes that
     * safe rather than merely possible.
     */
    public String bind() {
        return value("control.bind", "127.0.0.1");
    }

    /**
     * The bearer token the endpoints ask for, or empty for none.
     *
     * <p>Empty is only tolerated on loopback. {@link Control} refuses to answer a request that arrived
     * from anywhere else without one, because the endpoints behind it delete a world and start a
     * broadcast.
     */
    public String token() {
        return value("control.token", "");
    }

    public String obsUrl() {
        return value("obs.url", "ws://127.0.0.1:4455");
    }

    public String obsPassword() {
        return value("obs.password", "");
    }

    /** The scene the mod owns. Created if missing, reused — never duplicated — if already there. */
    public String scene() {
        return value("obs.scene", "AutoCraft AI");
    }

    /**
     * How the game gets into the scene.
     *
     * <p>A display capture rather than a game capture, whichever platform this is on. A game capture hooks
     * the graphics API the game is drawing through, and on the streaming box that is a virtualised driver
     * with nothing stable to hook; a display capture only asks the compositor for what is already on
     * screen and does not care how it got there. On a machine with a real GPU {@code game_capture} is the
     * better answer, and this is the key that says so.
     *
     * <p>The default follows the platform because OBS names the same idea differently on each: the
     * streaming box runs X11, so it wants {@code xshm_input_v2}. The {@code _v2} is not decoration —
     * obs-websocket matches the id exactly, and the unversioned name it had years ago is refused with a
     * message about versioning that is easy to read as being about something else. A Wayland session
     * would want {@code pipewire-screen-capture-source}, which is a setting rather than a code change;
     * ask OBS for {@code GetInputKindList} if in doubt about a particular build.
     */
    public String captureKind() {
        return value("obs.capture.kind", defaultCaptureKind());
    }

    private static String defaultCaptureKind() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "monitor_capture";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "screen_capture";
        }
        return "xshm_input_v2";
    }

    /** Which display the capture takes, when the capture is a display. */
    public int captureMonitor() {
        return number("obs.capture.monitor", 0);
    }

    /**
     * Whether to reopen the world that is already saved when the game reaches the title screen.
     *
     * <p>Off by default: launching the mod on your own machine and having it walk into a world by itself
     * is not what anyone asked for. On a box where the game is a service, it is the difference between a
     * restart costing fifteen seconds and a restart leaving a title screen on air until somebody notices.
     */
    public boolean autoOpen() {
        return Boolean.parseBoolean(value("world.autoopen", "false"));
    }

    /**
     * The frame rate the game runs at while a world is open.
     *
     * <p>Set from here rather than left to {@code options.txt}, so it is one number in one place and the
     * game cannot come back from a restart on whatever it happened to save last.
     */
    public int gameFps() {
        return number("game.fps", 60);
    }

    /**
     * The frame rate the game drops to when the run is stopped.
     *
     * <p>Low on purpose. The title screen is an animated panorama and will happily render it at full
     * speed forever; on a small box that is a warm, audible machine drawing something nobody is looking
     * at.
     */
    public int gameIdleFps() {
        return number("game.idle.fps", 10);
    }

    /**
     * What the browser source in the scene points at.
     *
     * <p>OBS renders this itself rather than picking it off the desktop, so it stays on loopback even when
     * {@link #bind()} does not: the browser is inside OBS, on the same machine as the game.
     */
    public String overlayUrl() {
        return value("overlay.url", "http://127.0.0.1:" + QTableServer.PORT + QTableServer.BASE);
    }

    /**
     * Where the broadcast is sent.
     *
     * <p>YouTube's ingest is a fixed address, unlike the per-broadcast URL TikTok hands out, so it can
     * usefully be a default and leave only the key to be pasted. RTMPS rather than RTMP because it is
     * the same endpoint over TLS and YouTube asks for it on anything that matters; the plain
     * {@code rtmp://a.rtmp.youtube.com/live2} still works if a build turns out to lack TLS.
     *
     * <p>Nothing here is YouTube-specific beyond this string — the scene is published through
     * {@code rtmp_custom}, so any ingest that speaks RTMP works by overriding it.
     */
    public String streamServer() {
        return value("stream.server", "rtmps://a.rtmps.youtube.com:443/live2");
    }

    /**
     * The key that says which channel this is.
     *
     * <p>YouTube's persists across broadcasts, so unlike TikTok's it is worth keeping on the box rather
     * than pasting before every stream.
     */
    public String streamKey() {
        return value("stream.key", "");
    }

    /**
     * Horizontal by default, which is what a YouTube stream is.
     *
     * <p>720p rather than 1080p on purpose: every pixel here is drawn by a CPU that is also encoding
     * them, and the measured headroom at this size is large enough to be comfortable rather than merely
     * sufficient. Raise it if the box ever gets a graphics card.
     */
    public int width() {
        return number("stream.width", 1280);
    }

    public int height() {
        return number("stream.height", 720);
    }

    public int fps() {
        return number("stream.fps", 30);
    }

    /** How wide the overlay sits over the game, as a fraction of the canvas. */
    public double overlayWidth() {
        return fraction("overlay.width", 1.0);
    }

    public double overlayHeight() {
        return fraction("overlay.height", 0.32);
    }

    /**
     * Measured from the top. The default parks the panel across the bottom third.
     *
     * <p>The overlay is drawn over the game rather than beside it, so this is how much of the picture it
     * costs. A third is enough for the objective and the top of the goal table on a 16:9 canvas, and
     * leaves the game — including its own HUD — the part people are actually watching.
     */
    public double overlayTop() {
        return fraction("overlay.top", 0.68);
    }

    /**
     * The viewport the overlay page is laid out in, before it is scaled into its box on the canvas.
     *
     * <p>Wider than the box on purpose: the q-table is a column per action and a vertical canvas is not
     * wide enough for all of them, so the page is rendered somewhere it fits and shrunk to suit. Lower
     * this for bigger text on screen and fewer columns; zero means "render at the size of the box", which
     * is right for a scene wide enough not to need the trick.
     */
    public int overlayRenderWidth() {
        return number("overlay.render.width", 1280);
    }

    /**
     * What is configured, without saying what any of it is.
     *
     * <p>The frontend has to be able to tell "no stream key" from "wrong stream key", and the first of
     * those is answerable without ever putting the key on the wire.
     */
    public String describe() {
        return "{\"bind\":" + quote(bind())
                + ",\"tokenSet\":" + !token().isEmpty()
                + ",\"obsUrl\":" + quote(obsUrl())
                + ",\"obsPasswordSet\":" + !obsPassword().isEmpty()
                + ",\"scene\":" + quote(scene())
                + ",\"captureKind\":" + quote(captureKind())
                + ",\"overlayUrl\":" + quote(overlayUrl())
                + ",\"streamServerSet\":" + !streamServer().isEmpty()
                + ",\"streamKeySet\":" + !streamKey().isEmpty()
                + ",\"width\":" + width()
                + ",\"height\":" + height()
                + ",\"fps\":" + fps()
                + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private int number(String key, int fallback) {
        String raw = value(key, "");
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("{} is not a whole number ({}); using {}", key, raw, fallback);
            return fallback;
        }
    }

    private double fraction(String key, double fallback) {
        String raw = value(key, "");
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            // The comma is swapped for a point first: the box is Spanish, and a decimal written the way
            // the rest of the machine writes it should not be the reason the overlay lands off-screen.
            return Double.parseDouble(raw.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            log.warn("{} is not a number ({}); using {}", key, raw, fallback);
            return fallback;
        }
    }

    /**
     * @param key dotted, as it appears in the file and — under an {@code autocraft.} prefix — as a system
     *            property
     */
    private String value(String key, String fallback) {
        String property = System.getProperty("autocraft." + key);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String environment = System.getenv(environmentName(key));
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }
        return file.getProperty(key, fallback).trim();
    }

    /** {@code stream.key} becomes {@code AUTOCRAFT_STREAM_KEY}, which is what a service file can set. */
    private static String environmentName(String key) {
        return "AUTOCRAFT_" + key.replace('.', '_').toUpperCase(Locale.ROOT);
    }
}
