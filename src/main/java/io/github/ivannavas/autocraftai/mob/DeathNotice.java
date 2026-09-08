package io.github.ivannavas.autocraftai.mob;

/**
 * The last thing the server said about how the body died.
 *
 * <p>The client's own combat tracker is never fed — damage is worked out on the server, and what the
 * client gets is the finished sentence, "Player was slain by Zombie", in the packet that puts the death
 * screen up. So the sentence is caught on its way past (see {@code ClientPacketListenerMixin}) and kept
 * here until the brain, which notices the death a tick or so later, comes to ask what happened.
 *
 * <p>Handed over once. A message left lying about would be pinned on the next death, which may have
 * been something else entirely.
 */
public final class DeathNotice {

    private static volatile String last = "";

    private DeathNotice() {
    }

    /** Called from the packet handler, on whichever thread it arrives on. */
    public static void record(String message) {
        last = message == null ? "" : message.strip();
    }

    /** The message for the death just noticed, or empty when none arrived. Cleared by the taking. */
    public static String take() {
        String message = last;
        last = "";
        return message;
    }
}
