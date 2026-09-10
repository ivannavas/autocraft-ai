package io.github.ivannavas.autocraftai.mob.ai.objective.planner;

import java.io.IOException;
import java.util.Locale;

/**
 * What actually went wrong with a call to the model, and whether it is worth trying again at once.
 *
 * <p>The client wraps everything as "Anthropic chat request failed", so a log line built from the top
 * exception's message says nothing: an overloaded API, a rate limit, a bad key and a dropped connection
 * all read the same, and the run's own log could not tell them apart while the body wandered.
 */
public final class Failure {

    private Failure() {
    }

    /** The chain, innermost message first: "429 rate limit ... (via Anthropic chat request failed)". */
    public static String describe(Throwable thrown) {
        if (thrown == null) {
            return "unknown";
        }
        Throwable root = thrown;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < 8) {
            root = root.getCause();
        }
        String inner = root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getClass().getSimpleName() + ": " + root.getMessage();
        String outer = thrown.getMessage();
        return root == thrown || outer == null || outer.isBlank() ? inner : inner + " (via " + outer + ")";
    }

    /**
     * Whether trying again in a few seconds might work: the API overloaded or rate-limiting, a gateway
     * between here and it, or the connection itself. A bad key or a malformed request would fail the
     * same way for the rest of the run and is left to the ordinary rest.
     */
    public static boolean worthRetrying(Throwable thrown) {
        String text = describe(thrown).toLowerCase(Locale.ROOT);
        if (text.contains("401") || text.contains("403") || text.contains("invalid")
                || text.contains("authentication") || text.contains("400")) {
            return false;
        }
        for (Throwable at = thrown; at != null && at.getCause() != at; at = at.getCause()) {
            if (at instanceof IOException || at instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
        }
        return text.contains("429") || text.contains("500") || text.contains("502") || text.contains("503")
                || text.contains("529") || text.contains("overloaded") || text.contains("rate")
                || text.contains("timeout") || text.contains("timed out") || text.contains("connection")
                || text.contains("reset") || text.contains("eof");
    }
}
